[CmdletBinding()]
param([switch]$SkipBuild, [string]$JavaHome = $env:JAVA_HOME)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $SkipBuild) { & (Join-Path $PSScriptRoot 'build.ps1') -JavaHome $JavaHome }
$version = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot 'version.properties')) {
    if ($line -match '^([^=]+)=(.*)$') { $version[$Matches[1]] = $Matches[2] }
}
$name = 'Watch4QuickApps-' + $version.versionName
$metadata = Get-Content -LiteralPath (Join-Path $projectRoot 'app/build/outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
if ($metadata.elements[0].versionName -ne $version.versionName -or $metadata.elements[0].versionCode -ne [int]$version.versionCode) {
    throw 'APK 与版本事实源不一致，请重新构建'
}
$dist = Join-Path $projectRoot 'dist'
$stage = Join-Path $projectRoot ('.local/package/' + [guid]::NewGuid().ToString('N') + '/' + $name)
New-Item -ItemType Directory -Path $dist, $stage -Force | Out-Null

$rootFiles = @('README.md', 'CHANGELOG.md', 'COPYRIGHT.md', 'THIRD_PARTY_NOTICES.md', 'CONTRIBUTING.md', 'SECURITY.md', '.editorconfig', '.gitattributes', '.gitignore',
    'settings.gradle', 'build.gradle', 'gradle.properties', 'version.properties', 'gradlew', 'gradlew.bat',
    'app/build.gradle', 'protocol/build.gradle', 'app/gradle.lockfile', 'protocol/gradle.lockfile')
$files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $projectRoot $_) -ErrorAction Stop })
foreach ($directory in @('app/src', 'protocol/src', 'gradle', 'scripts', 'docs', '.github')) {
    $files += Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -Recurse -File
}
foreach ($file in $files) {
    $relative = [System.IO.Path]::GetRelativePath($projectRoot, $file.FullName)
    if ($relative.StartsWith('..') -or $file.Attributes.HasFlag([System.IO.FileAttributes]::ReparsePoint)) {
        throw '归档文件超出项目范围或包含重解析点'
    }
    $target = Join-Path $stage $relative
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target
}
$apk = Join-Path $dist ($name + '-android-debug.apk')
$zip = Join-Path $dist ($name + '-source.zip')
$sums = Join-Path $dist 'SHA256SUMS.txt'
foreach ($artifact in @($apk, $zip, $sums)) {
    if (Test-Path -LiteralPath $artifact) {
        $backup = Join-Path $projectRoot ('.local/backups/packages/' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $backup -Force | Out-Null
        Copy-Item -LiteralPath $artifact -Destination $backup
    }
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $apk
Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryZip = Join-Path (Split-Path $stage -Parent) 'source.zip'
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $temporaryZip,
    [System.IO.Compression.CompressionLevel]::Optimal, $true)
Copy-Item -LiteralPath $temporaryZip -Destination $zip
$hashes = foreach ($artifact in @($apk, $zip)) {
    (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + (Split-Path $artifact -Leaf)
}
[System.IO.File]::WriteAllText($sums, ($hashes -join "`n") + "`n", [System.Text.UTF8Encoding]::new($false))
Get-Item -LiteralPath $apk, $zip, $sums | Select-Object Name, Length, FullName
