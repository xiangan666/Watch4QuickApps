[CmdletBinding()]
param([string]$GhPath = 'gh')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent

function Invoke-Gh {
    param([string[]]$GhArguments)
    $output = & $GhPath @GhArguments
    if ($LASTEXITCODE -ne 0) { throw "GitHub 操作失败：$($GhArguments[0])，退出码 $LASTEXITCODE" }
    return $output
}

Push-Location -LiteralPath $projectRoot
try {
    $status = & git status --porcelain
    if ($LASTEXITCODE -ne 0) { throw '当前目录不是有效 Git 仓库' }
    if ($status) { throw '请先审阅并提交本轮改动，发布要求干净工作区' }
    $version = @{}
    foreach ($line in Get-Content -LiteralPath 'version.properties') {
        if ($line -match '^([^=]+)=(.*)$') { $version[$Matches[1]] = $Matches[2] }
    }
    if ($version.versionName -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$') { throw '版本格式无效' }
    $tag = 'v' + $version.versionName
    $head = & git rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw '读取候选提交失败' }
    $tagCommit = & git rev-parse ($tag + '^{commit}')
    if ($LASTEXITCODE -ne 0 -or $tagCommit -ne $head) { throw '本地版本标签必须指向当前候选提交' }
    $remoteTag = & git ls-remote --tags origin ('refs/tags/' + $tag) ('refs/tags/' + $tag + '^{}')
    if ($LASTEXITCODE -ne 0 -or -not ($remoteTag | Where-Object { $_.StartsWith($head + "`t") })) {
        throw '远程标签缺失或没有指向当前候选提交'
    }
    $repo = Invoke-Gh -GhArguments @('repo', 'view', '--json', 'nameWithOwner,url') | ConvertFrom-Json
    $runs = @(Invoke-Gh -GhArguments @('run', 'list', '--workflow', 'ci.yml', '--commit', $head,
        '--status', 'success', '--json', 'databaseId', '--limit', '1') | ConvertFrom-Json)
    if ($runs.Count -eq 0) { throw '候选提交尚无成功的 Android CI，请先解决检查并等待完成' }

    $name = 'Watch4QuickApps-' + $version.versionName
    $assetNames = @(($name + '-android-debug.apk'), ($name + '-source.zip'), 'SHA256SUMS.txt')
    $assets = @($assetNames | ForEach-Object { Join-Path $projectRoot ('dist/' + $_) })
    foreach ($asset in $assets) { if (-not (Test-Path -LiteralPath $asset)) { throw '缺少分发文件，请先运行 package.ps1' } }
    $metadata = Get-Content -LiteralPath 'app/build/outputs/apk/debug/output-metadata.json' -Raw | ConvertFrom-Json
    if ($metadata.elements[0].versionName -ne $version.versionName -or $metadata.elements[0].versionCode -ne [int]$version.versionCode) {
        throw 'APK 版本与事实源不一致，请重新构建并打包'
    }
    $checks = @(Get-Content -LiteralPath $assets[2] | Where-Object { $_ })
    if ($checks.Count -ne 2) { throw 'SHA256SUMS.txt 必须包含 APK 与源码归档两条记录' }
    for ($index = 0; $index -lt 2; $index++) {
        $hash = (Get-FileHash -LiteralPath $assets[$index] -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($checks[$index] -ne ($hash + '  ' + $assetNames[$index])) { throw '本地分发文件与摘要不一致' }
    }
    if ((Get-FileHash -LiteralPath $assets[0]).Hash -ne (Get-FileHash -LiteralPath 'app/build/outputs/apk/debug/app-debug.apk').Hash) {
        throw '分发 APK 与当前构建不一致'
    }
    $notes = Join-Path $projectRoot ('docs/releases/' + $version.versionName + '.md')
    if (-not (Test-Path -LiteralPath $notes)) { throw '缺少当前版本发布说明' }
    $releases = @(Invoke-Gh -GhArguments @('api', ('repos/' + $repo.nameWithOwner + '/releases?per_page=100')) | ConvertFrom-Json)
    $existing = @($releases | Where-Object { $_.tag_name -eq $tag })
    if ($existing.Count -gt 0 -and -not $existing[0].draft) { throw '该版本已发布，请递增版本后发布新版本' }
    if ($existing.Count -eq 0) {
        Invoke-Gh -GhArguments @('release', 'create', $tag, '--verify-tag', '--draft', '--title',
            ('Watch 4 快应用管理 ' + $version.versionName), '--notes-file', $notes) | Out-Host
    }
    Invoke-Gh -GhArguments (@('release', 'upload', $tag, '--clobber') + $assets) | Out-Host
    $download = Join-Path $projectRoot ('.local/release-verify/' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $download -Force | Out-Null
    Invoke-Gh -GhArguments @('release', 'download', $tag, '--dir', $download) | Out-Host
    $downloaded = @(Get-ChildItem -LiteralPath $download -File)
    if ($downloaded.Count -ne $assetNames.Count) { throw '草稿资产数量与预期不一致' }
    foreach ($asset in $assets) {
        $remoteFile = Join-Path $download (Split-Path $asset -Leaf)
        if ((Get-FileHash -LiteralPath $asset).Hash -ne (Get-FileHash -LiteralPath $remoteFile).Hash) {
            throw '下载资产摘要不匹配，保留草稿供排查'
        }
    }
    $preview = $version.versionName.StartsWith('0.') -or $version.versionName.Contains('-')
    Invoke-Gh -GhArguments @('release', 'edit', $tag, '--draft=false', ('--prerelease=' + $preview.ToString().ToLowerInvariant()),
        '--latest=false') | Out-Host
    $published = Invoke-Gh -GhArguments @('release', 'view', $tag, '--json', 'url,isDraft,isPrerelease,assets') | ConvertFrom-Json
    if ($published.isDraft -or $published.isPrerelease -ne $preview -or $published.assets.Count -ne $assetNames.Count) {
        throw '发布后的状态或资产数量与预期不一致'
    }
    $published | Select-Object url, isDraft, isPrerelease
    Write-Host '全部资产已下载并通过 SHA-256 一致性核对。'
} finally {
    Pop-Location
}
