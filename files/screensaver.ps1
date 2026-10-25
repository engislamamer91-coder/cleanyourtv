# Installs the matches add-on (with its screensaver) and makes that screensaver the TV's screensaver.
# The previous screensaver setting is saved to ..\tv-backup\screensaver-before.txt; undo with -Undo.
#   powershell -ExecutionPolicy Bypass -NoExit -File .\screensaver.ps1
#   powershell -ExecutionPolicy Bypass -NoExit -File .\screensaver.ps1 -Undo
param([switch]$Undo)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root 'adb.exe'
$saved = Join-Path $root 'tv-backup\screensaver-before.txt'
Set-Location $PSScriptRoot
& $adb connect 192.168.8.172:5555 | Out-Null

if ($Undo) {
    if (Test-Path $saved) {
        $before = (Get-Content $saved -Raw).Trim()
        if ($before -and $before -ne 'null') { & $adb shell settings put secure screensaver_components $before }
        else { & $adb shell settings delete secure screensaver_components }
    }
    Write-Host 'Screensaver set back to the one before.'
    return
}

if (-not (Test-Path $saved)) {
    "$((& $adb shell settings get secure screensaver_components))".Trim() | Out-File -Encoding ascii $saved
}
& $adb push matches.apk /data/local/tmp/cyt.apk | Out-Null
$out = "$(& $adb shell pm install -r /data/local/tmp/cyt.apk)"
if ($out -match 'UPDATE_INCOMPATIBLE|SIGNATURE') {
    Write-Host 'Older copy signed with another key: removing it first.'
    & $adb shell pm uninstall com.islam.tv.matches
    $out = "$(& $adb shell pm install /data/local/tmp/cyt.apk)"
}
& $adb shell rm -f /data/local/tmp/cyt.apk
Write-Host $out
if ($out -notmatch 'Success') { Write-Warning 'Install failed; the screensaver setting was not changed.'; return }

& $adb shell settings put secure screensaver_enabled 1
& $adb shell settings put secure screensaver_components com.islam.tv.matches/.InfoDream
Write-Host "Screensaver: $((& $adb shell settings get secure screensaver_components))"
Write-Host 'Showing it now for a test. Press any button on the remote to leave it.'
& $adb shell am start -n com.android.systemui/.Somnambulator
