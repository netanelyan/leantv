<#
Switches the LeanTV Home button helper (an accessibility service inside LeanTV Home) on or off.
Needed on firmware that pins its own launcher to the Home button and blocks disabling it (yes+ SEI800Y).
Every change goes to backups\changes.log, so restore.ps1 also switches it off.

  .\scripts\home-button.ps1          # on
  .\scripts\home-button.ps1 -Off     # off: Home goes to the stock launcher again
#>
param([switch]$Off)
. "$PSScriptRoot\common.ps1"
Assert-Online

$svc = 'com.leantv.home/.HomeKeyService'
$prevSvc = ((Sh 'settings get secure enabled_accessibility_services') -join '').Trim()
$prevOn = ((Sh 'settings get secure accessibility_enabled') -join '').Trim()
$list = @($prevSvc -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $svc -and $_ -ne 'com.leantv.home/com.leantv.home.HomeKeyService' })
if (-not $Off) { $list += $svc }
$new = $list -join ':'

if ($new -ne ($prevSvc -replace '^null$', '')) {
    Write-Change "setting`tsecure`tenabled_accessibility_services`tprev=$prevSvc`tnew=$new"
    if ($new) { Sh "settings put secure enabled_accessibility_services $new" | Out-Null }
    else { Sh 'settings delete secure enabled_accessibility_services' | Out-Null }
}
$wantOn = if ($list.Count) { '1' } else { '0' }
if ($prevOn -ne $wantOn) {
    Write-Change "setting`tsecure`taccessibility_enabled`tprev=$prevOn`tnew=$wantOn"
    Sh "settings put secure accessibility_enabled $wantOn" | Out-Null
}

Start-Sleep 2
if (-not $Off) { Sh 'am start -n com.leantv.home/.HomeActivity' | Out-Null; Start-Sleep 1 }
Sh 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep 3
Write-Host ("Home button now opens: " + ((Sh 'dumpsys window | grep mCurrentFocus | head -1') -join '').Trim())
Save-Shot 'home' (Join-Path $Data "shots\home-button-$(Get-Date -Format 'yyyyMMdd-HHmmss')") | Out-Null
