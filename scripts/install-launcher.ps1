<#
Installs dist\LeanTVHome.apk, makes it the Home app, and checks it with screenshots.
The stock launcher stays installed and enabled as a fallback (restore.ps1 switches back).

  .\scripts\install-launcher.ps1
  .\scripts\install-launcher.ps1 -Dock "com.netflix.ninja,com.google.android.youtube.tv,@settings"
  .\scripts\install-launcher.ps1 -Wallpapers C:\path\to\photos     # add your own photos too
#>
param(
    [string]$Dock,
    [string]$Wallpapers,
    [int]$Interval = 0
)
. "$PSScriptRoot\common.ps1"
Assert-Online
if (-not (Get-Baseline)) { throw 'No baseline backup. Run scripts\backup.ps1 first.' }

$apk = Join-Path $Root 'dist\LeanTVHome.apk'
if (-not (Test-Path $apk)) { & (Join-Path $Root 'build.ps1') }
$component = 'com.leantv.home/.HomeActivity'
$shotDir = Join-Path $Data "shots\launcher-$(Get-Date -Format 'yyyyMMdd-HHmmss')"

$prevHome = (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1).Trim()
Write-Host "Current home: $prevHome"

Write-Host 'Installing...'
$r = (AdbD install -r $apk) -join ' '
if ($r -notmatch 'Success') { throw "install failed: $r" }
Write-Change "install`tcom.leantv.home"

$r = (Sh "cmd package set-home-activity $component") -join ' '
if ($r -notmatch 'Success') {
    Write-Warning "set-home-activity said: $r"
} elseif ($prevHome -ne $component) {
    Write-Change "home`t$component`tprev=$prevHome"
}

if ($Dock) { Sh "am start -n $component --es dock '$Dock'" | Out-Null }
if ($Interval -gt 0) { Sh "am start -n $component --ei interval $Interval" | Out-Null }

if ($Wallpapers) {
    $dest = '/sdcard/Android/data/com.leantv.home/files/wallpapers'
    Sh "mkdir -p $dest" | Out-Null
    Get-ChildItem $Wallpapers -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' } |
        ForEach-Object { AdbD push $_.FullName "$dest/$($_.Name)" | Out-Null; Write-Host "  pushed $($_.Name)" }
    Sh "am start -n $component --ez rescan true" | Out-Null
}

Sh 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep 4
$now = (Sh 'dumpsys window | grep mCurrentFocus | head -1') -join ''
Write-Host "Focus after HOME: $now"
Save-Shot 'home' $shotDir | Out-Null
Sh 'input keyevent KEYCODE_DPAD_RIGHT' | Out-Null
Start-Sleep 1
Save-Shot 'home-focus-moved' $shotDir | Out-Null

if ($now -notmatch 'com\.leantv\.home') {
    Write-Warning 'HOME did not land on LeanTV Home. This box may hard-wire its own launcher to the Home button.'
    Write-Warning 'Run .\scripts\home-button.ps1 to make Home open LeanTV Home anyway.'
}
Write-Host "Screenshots: $shotDir"
