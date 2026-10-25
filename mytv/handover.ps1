# Makes the new launcher the only Home: disables the Google TV launcher (launcherx), its first-run wizard
# (setupwraith, which grabs Home once launcherx is off) and the Google ambient screensaver (dreamx).
# Safety: stops unless Home already resolves to the new launcher; skips setupwraith if it owns a saved Wi-Fi
# network (disabling it would delete that network and take the TV off Wi-Fi); puts launcherx back if Home
# does not end up on the new launcher. Everything it disables is listed in ..\tv-backup\handover.txt.
#   powershell -ExecutionPolicy Bypass -NoExit -File .\handover.ps1
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root 'adb.exe'
$record = Join-Path $root 'tv-backup\handover.txt'
$ours = 'com.gathin.tvhome'
$homeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
function HomeNow { "$((& $adb shell $homeQuery) | Select-Object -Last 1)".Trim() }

& $adb shell cmd package set-home-activity --user 0 "$ours/.MainActivity" | Out-Null
$h = HomeNow
if ($h -notlike "$ours/*") { Write-Warning "Home is '$h', not the new launcher. Nothing was changed."; return }
Write-Host "Home is the new launcher: $h"

$disable = @('com.google.android.apps.tv.launcherx', 'com.google.android.apps.tv.dreamx')
$wifi = (& $adb shell dumpsys wifi) -join "`n"
$owners = @([regex]::Matches($wifi, 'cname=[^ ,]*') | ForEach-Object { $_.Value } | Sort-Object -Unique)
Write-Host "Saved Wi-Fi owners: $($owners -join ', ')"
if ($owners.Count -eq 0) {
    Write-Warning 'Could not read who owns the saved Wi-Fi networks, so setupwraith is left enabled.'
} elseif ($owners -match 'setupwraith') {
    Write-Warning 'A saved Wi-Fi network belongs to setupwraith, so it is left enabled (disabling it would drop the Wi-Fi).'
} else {
    $disable = @('com.google.android.tungsten.setupwraith') + $disable
}

foreach ($p in $disable) {
    Write-Host "disable $p"
    & $adb shell pm disable-user --user 0 $p
    Add-Content -Path $record -Value $p
}
& $adb shell cmd package set-home-activity --user 0 "$ours/.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$h = HomeNow
if ($h -like "$ours/*") {
    & $adb shell input keyevent KEYCODE_HOME
    Write-Host "Done. Home is $h"
} else {
    Write-Warning "Home resolved to '$h' afterwards; turning the Google TV launcher back on."
    & $adb shell pm enable com.google.android.apps.tv.launcherx
    & $adb shell cmd package set-home-activity --user 0 "$ours/.MainActivity" | Out-Null
}
