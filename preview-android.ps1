param([switch]$Build)
$ErrorActionPreference = 'Stop'
$env:ANDROID_SDK_ROOT = 'D:\AndroidShotSdk'
$env:ANDROID_AVD_HOME = 'D:\AndroidShotAvd'
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
$emulator = Join-Path $env:ANDROID_SDK_ROOT 'emulator\emulator.exe'
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if ($Build) { & (Join-Path $PSScriptRoot 'build-local.ps1') }
if (!(Test-Path $emulator) -or !(Test-Path (Join-Path $env:ANDROID_AVD_HOME 'ShotGuide_UI.ini'))) {
    throw 'ShotGuide_UI emulator is not installed.'
}
if (!(Test-Path $apk)) { throw 'Build the APK first: .\build-local.ps1' }
$serial = 'emulator-5580'
$devices = & $adb devices
if (!($devices -match "$serial\s+device")) {
    # Visible window is intentional: this is the interactive desktop preview.
    Start-Process -FilePath $emulator -ArgumentList @('-avd', 'ShotGuide_UI', '-port', '5580', '-no-audio', '-no-boot-anim', '-camera-back', 'none', '-camera-front', 'none') -WindowStyle Normal
}
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 2
    # Windows PowerShell 5 treats native stderr as an error while the device boots.
    $ErrorActionPreference = 'Continue'
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
    $ErrorActionPreference = 'Stop'
    if ((Get-Date) -gt $deadline) { throw 'Emulator boot timed out. Check its window.' }
} until ($booted -match '1')
& $adb -s $serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
& $adb -s $serial shell am start -n com.summer.shotguide/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'App launch failed.' }
Write-Output 'App opened in ShotGuide_UI. Camera hardware is intentionally disabled for UI preview.'
