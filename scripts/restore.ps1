<#
Puts the TV back the way the BASELINE backup found it:
  * re-enables every package that is disabled now but was enabled in the baseline
  * re-installs (install-existing) anything hidden from the user since the baseline
  * restores every setting recorded in backups\changes.log to its old value
  * makes the original launcher the Home app again
  * uninstalls LeanTV Home (keep it with -KeepLauncher)
The baseline snapshot is the source of truth, so this works even if changes.log was lost.

  .\scripts\restore.ps1 -WhatIf
  .\scripts\restore.ps1
  .\scripts\restore.ps1 -Only com.google.android.katniss     # re-enable just one package
#>
param(
    [switch]$WhatIf,
    [switch]$KeepLauncher,
    [string[]]$Only
)
. "$PSScriptRoot\common.ps1"
Assert-Online

$base = Get-Baseline
if (-not $base -or -not (Test-Path $base)) { throw 'No baseline backup found in backups\.' }
Write-Host "Restoring to baseline $(Split-Path $base -Leaf)"
$shotDir = Join-Path $Data "shots\restore-$(Get-Date -Format 'yyyyMMdd-HHmmss')"

$baseDisabled = @(Get-Content (Join-Path $base 'disabled.txt') | Where-Object { $_ })
$baseInstalled = @(Get-Content (Join-Path $base 'installed.txt') | Where-Object { $_ })
$nowDisabled = @(Get-Packages '-d')
$nowInstalled = @(Get-Packages)

function Do-It([string]$what, [string]$cmd) {
    if ($WhatIf) { Write-Host "  would: $what"; return }
    $r = (Sh $cmd) -join ' '
    Write-Host "  $what -> $r"
    Write-Change "restore`t$what`t$r"
}

# 1. Packages
$toEnable = $nowDisabled | Where-Object { $baseDisabled -notcontains $_ }
$toUnhide = $baseInstalled | Where-Object { $nowInstalled -notcontains $_ }
# Suspended (firmwares that block disable-user). Baseline suspended.txt is absent on older baselines = none.
$baseSuspFile = Join-Path $base 'suspended.txt'
$baseSuspended = if (Test-Path $baseSuspFile) { @(Get-Content $baseSuspFile | Where-Object { $_ }) } else { @() }
$toUnsuspend = @(Get-Suspended) | Where-Object { $baseSuspended -notcontains $_ }
# Background-run app-ops changed alongside a suspend: oldest logged value per package wins.
$appops = [ordered]@{}
if (Test-Path $ChangeLog) {
    Get-Content $ChangeLog | ForEach-Object {
        $f = $_ -split "`t"
        if ($f[1] -eq 'appop' -and -not $appops.Contains("$($f[2])`t$($f[3])")) {
            $appops["$($f[2])`t$($f[3])"] = ($f[4] -replace '^prev=', '')
        }
    }
}
if ($Only) {
    $toEnable = $toEnable | Where-Object { $Only -contains $_ }
    $toUnhide = $toUnhide | Where-Object { $Only -contains $_ }
    $toUnsuspend = $toUnsuspend | Where-Object { $Only -contains $_ }
}
foreach ($p in $toEnable) { Do-It "enable $p" "pm enable --user 0 $p" }
foreach ($p in $toUnhide) { Do-It "install-existing $p" "cmd package install-existing --user 0 $p" }
foreach ($p in $toUnsuspend) { Do-It "unsuspend $p" "pm unsuspend --user 0 $p" }
foreach ($k in $appops.Keys) {
    $p, $op = $k -split "`t"
    if ($Only -and $Only -notcontains $p) { continue }
    Do-It "appops $p $op = $($appops[$k])" "cmd appops set $p $op $($appops[$k])"
}
if ($Only) { if (-not $WhatIf) { Save-Shot 'after' $shotDir | Out-Null }; return }

# 2. Settings (oldest recorded value wins: that is the pre-LeanTV value)
if (Test-Path $ChangeLog) {
    $orig = [ordered]@{}
    Get-Content $ChangeLog | ForEach-Object {
        $f = $_ -split "`t"
        if ($f[1] -eq 'setting') {
            $k = "$($f[2])`t$($f[3])"
            if (-not $orig.Contains($k)) { $orig[$k] = ($f[4] -replace '^prev=', '') }
        }
    }
    foreach ($k in $orig.Keys) {
        $ns, $key = $k -split "`t"
        $v = $orig[$k]
        if ($v -eq 'null' -or $v -eq '') { Do-It "settings delete $ns $key" "settings delete $ns $key" }
        else { Do-It "settings $ns $key = $v" "settings put $ns $key $v" }
    }
}

# 3. Home screen
$origHome = (Get-Content (Join-Path $base 'home.txt') -Raw).Trim()
if ($origHome -match '/' -and $origHome -notmatch 'ResolverActivity') {
    Do-It "home -> $origHome" "cmd package set-home-activity $origHome"
}

# 4. LeanTV Home itself
if (-not $KeepLauncher -and $baseInstalled -notcontains 'com.leantv.home' -and $nowInstalled -contains 'com.leantv.home') {
    if ($WhatIf) { Write-Host '  would: uninstall com.leantv.home' }
    else { $r = (AdbD uninstall com.leantv.home) -join ' '; Write-Host "  uninstall com.leantv.home -> $r"; Write-Change "restore`tuninstall com.leantv.home`t$r" }
}

if (-not $WhatIf) {
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep 4
    Write-Host "Focus now: $((Sh 'dumpsys window | grep mCurrentFocus | head -1') -join '')"
    Save-Shot 'home-after-restore' $shotDir | Out-Null
    Write-Host 'Restore complete.' -ForegroundColor Green
}
