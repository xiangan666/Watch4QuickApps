[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string[]]$Tasks = @(':protocol:test', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug'),
    [switch]$UpdateLocks
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$mappedDrive = $null
$pushed = $false

try {
    if ($JavaHome) {
        if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome 未指向有效 JDK' }
        $env:JAVA_HOME = $JavaHome
    } elseif (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '请安装 JDK 17 或 21，并设置 JAVA_HOME' }
    if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (-not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms/android-36/android.jar'))) {
        throw '请在 Android SDK 中安装 Android 36 平台，或通过 -SdkRoot 指定现有 SDK'
    }
    $env:ANDROID_HOME = $SdkRoot
    $buildRoot = $projectRoot
    # AGP 不接受中文工程绝对路径；临时盘符仅指向当前项目，不移动文件。
    if ($projectRoot -match '[^\x00-\x7F]') {
        foreach ($letter in @('Z', 'Y', 'X', 'V', 'U', 'T', 'S', 'R', 'Q', 'P')) {
            $candidate = $letter + ':'
            if (-not ([System.IO.Directory]::GetLogicalDrives() -contains ($candidate + '\'))) {
                & subst.exe $candidate $projectRoot
                if ($LASTEXITCODE -ne 0) { throw '创建临时构建盘符失败' }
                $mappedDrive = $candidate
                $buildRoot = $candidate + '\'
                break
            }
        }
        if (-not $mappedDrive) { throw '没有空闲的临时盘符，请释放一个盘符后重试' }
    }
    Push-Location -LiteralPath $buildRoot
    $pushed = $true
    $gradleArguments = @('--project-dir', $buildRoot, '--console', 'plain', '--no-daemon') + $Tasks
    if ($UpdateLocks) { $gradleArguments += @('--write-locks', '--write-verification-metadata', 'sha256') }
    & (Join-Path $buildRoot 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle 验证失败，退出码 $LASTEXITCODE" }
} finally {
    if ($pushed) { Pop-Location }
    if ($mappedDrive) {
        & subst.exe $mappedDrive /D
        if ($LASTEXITCODE -ne 0) { Write-Warning "临时盘符 $mappedDrive 清理失败，请执行 subst $mappedDrive /D" }
    }
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
}
