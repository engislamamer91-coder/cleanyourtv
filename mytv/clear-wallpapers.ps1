# Deletes every wallpaper from the TV and switches the launcher to a plain background.
#   powershell -ExecutionPolicy Bypass -NoExit -File .\clear-wallpapers.ps1
$ErrorActionPreference = 'Continue'
$adb = Join-Path (Split-Path -Parent $PSScriptRoot) 'adb.exe'
Set-Location $PSScriptRoot
$uri = 'content://com.gathin.tvhome.theme'
& $adb shell "content delete --uri $uri/wallpapers"
& $adb shell "content delete --uri $uri/wallpapers/captions.txt"
& $adb shell "content delete --uri $uri/wallpaper.jpg"
& $adb push theme\config.json /data/local/tmp/cyt-theme | Out-Null
& $adb shell "content write --uri $uri/config.json < /data/local/tmp/cyt-theme; rm -f /data/local/tmp/cyt-theme"
Set-Content -Path "$env:TEMP\theme_revision" -Value ([guid]::NewGuid().ToString('N')) -NoNewline -Encoding ascii
& $adb push "$env:TEMP\theme_revision" /data/local/tmp/cyt-theme | Out-Null
& $adb shell "content write --uri $uri/theme_revision < /data/local/tmp/cyt-theme; rm -f /data/local/tmp/cyt-theme"
& $adb shell am force-stop com.gathin.tvhome
& $adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
Write-Host 'Wallpapers deleted from the TV.'
