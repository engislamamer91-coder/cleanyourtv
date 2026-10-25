# Pushes theme\ (config.json and wallpapers\) to the launcher through its ThemeProvider and restarts Home.
#   powershell -ExecutionPolicy Bypass -File .\theme.ps1
$ErrorActionPreference = 'Continue'
$adb = Join-Path (Split-Path -Parent $PSScriptRoot) 'adb.exe'
Set-Location $PSScriptRoot
$uri = 'content://com.gathin.tvhome.theme'
$tmp = '/data/local/tmp/cyt-theme'

function Put([string]$local, [string]$name) {
    & $adb push $local $tmp | Out-Null
    & $adb shell "content write --uri $uri/$name < $tmp; rm -f $tmp"
}
& $adb shell "content delete --uri $uri/wallpapers"
Put 'theme\config.json' 'config.json'
Put 'theme\wallpapers\captions.txt' 'wallpapers/captions.txt'
$files = Get-ChildItem 'theme\wallpapers' -Filter *.jpg
$i = 0
foreach ($f in $files) {
    $i++
    Write-Host "wallpaper $i / $($files.Count)"
    Put $f.FullName "wallpapers/$($f.Name)"
}
$rev = [guid]::NewGuid().ToString('N')
Set-Content -Path "$env:TEMP\theme_revision" -Value $rev -NoNewline -Encoding ascii
Put "$env:TEMP\theme_revision" 'theme_revision'
& $adb shell am force-stop com.gathin.tvhome
& $adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Null
Write-Host 'Theme pushed. Press Home on the remote.'
