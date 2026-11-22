param(
    [switch]$PlayBundle,
    [string]$SigningProperties
)

$ErrorActionPreference = 'Stop'
if ($SigningProperties -and -not $PlayBundle) { throw '-SigningProperties is for -PlayBundle' }
$signingFile = if ($SigningProperties) { (Resolve-Path -LiteralPath $SigningProperties).Path }
$taskDrive = @('R:', 'S:', 'T:', 'U:') | Where-Object { -not (Test-Path "$_\") } | Select-Object -First 1
if (-not $taskDrive) { throw 'No free drive letter for an ASCII build path' }
subst.exe $taskDrive $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Could not map project path' }
try {
    Set-Location "$taskDrive\"
    $env:JAVA_HOME = Join-Path $PSScriptRoot '.tools\jdk17.0.21_0'
    $gradleTasks = if ($PlayBundle) { @(':app:bundleRelease', ':app:testReleaseUnitTest', ':lineartCore:test', ':app:lintRelease') }
    else { @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug') }
    if ($signingFile) { $gradleTasks += "-PshotguidePlaySigningFile=$signingFile" }
    & '.\.tools\gradle-8.11.1\bin\gradle.bat' @gradleTasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed' }
    $output = if ($PlayBundle) { 'app\build\outputs\bundle\release\app-release.aab' }
    else { 'app\build\outputs\apk\debug\app-debug.apk' }
    Write-Output (Join-Path $PSScriptRoot $output)
} finally {
    Set-Location -LiteralPath $PSScriptRoot
    subst.exe $taskDrive /D
}
