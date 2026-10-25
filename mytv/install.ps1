# Installs the launcher and the two add-ons on the TV and makes the launcher Home.
# The Google TV launcher is left enabled as a fallback. Run from this folder:
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
$ErrorActionPreference = 'Continue'
$adb = Join-Path (Split-Path -Parent $PSScriptRoot) 'adb.exe'
if (-not (Test-Path $adb)) { throw "adb.exe not found next to this folder ($adb)" }
Set-Location $PSScriptRoot

foreach ($apk in 'launcher.apk', 'prayer-times.apk', 'matches.apk') {
    Write-Host "== $apk"
    & $adb push $apk /data/local/tmp/cyt.apk | Out-Null
    & $adb shell pm install -r /data/local/tmp/cyt.apk
    & $adb shell rm -f /data/local/tmp/cyt.apk
}
Write-Host '== Home'
& $adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
& $adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity
& $adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
Write-Host 'Done. Look at the TV.'
