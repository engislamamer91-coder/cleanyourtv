# One-time fix: the first build was signed with a different key, so later builds could not install over it.
# Puts the Google TV launcher back as Home for a moment, removes the three apps, installs the new ones,
# makes the new launcher Home again and pushes the theme (wallpapers included).
#   powershell -ExecutionPolicy Bypass -NoExit -File .\reinstall.ps1
$ErrorActionPreference = 'Continue'
$adb = Join-Path (Split-Path -Parent $PSScriptRoot) 'adb.exe'
Set-Location $PSScriptRoot
& $adb shell pm enable com.google.android.apps.tv.launcherx
& $adb shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
foreach ($p in 'com.gathin.tvhome', 'com.islam.tv.matches', 'com.majnioui.tv.prayer') {
    Write-Host "remove old $p"; & $adb shell pm uninstall $p
}
powershell -ExecutionPolicy Bypass -File .\install.ps1
Start-Sleep -Seconds 3
powershell -ExecutionPolicy Bypass -File .\theme.ps1
powershell -ExecutionPolicy Bypass -File .\handover.ps1
