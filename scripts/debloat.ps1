<#
Applies debloat\plan.txt. Everything is reversible: packages are disabled (pm disable-user), never
uninstalled, and every change plus its previous state goes to backups\changes.log.

  .\scripts\debloat.ps1 -WhatIf     # show what would happen
  .\scripts\debloat.ps1             # apply, screenshot after each batch of 8 and at the end
#>
param(
    [string]$Plan,
    [switch]$WhatIf,
    [int]$Batch = 8
)
. "$PSScriptRoot\common.ps1"
if (-not $Plan) { $Plan = Join-Path $Data 'debloat\plan.txt' }
Assert-Online

if (-not (Get-Baseline)) { throw 'No baseline backup. Run scripts\backup.ps1 first.' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$shotDir = Join-Path $Data "shots\debloat-$stamp"

$ops = Get-Content $Plan | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
$installed = Get-Packages '-u'
$disabled = @(Get-Packages '-d') + @(Get-Suspended)

function Test-Healthy {
    # The UI must still be drawing something and the package manager must answer.
    $focus = (Sh 'dumpsys window | grep -E ''mCurrentFocus|mFocusedApp'' | head -2') -join ' '
    $pmOk = (Sh 'pm path android') -match 'package:'
    return @{ ok = ($pmOk -and $focus -notmatch 'Not Responding'); focus = $focus }
}

$done = 0; $failed = @()
foreach ($op in $ops) {
    $parts = $op -split '\s+'
    switch ($parts[0]) {
        'disable' {
            $pkg = $parts[1]
            if ($Protected -contains $pkg) { Write-Warning "skip protected $pkg"; continue }
            if ($installed -notcontains $pkg) { Write-Host "  not on this TV: $pkg"; continue }
            if ($disabled -contains $pkg) { Write-Host "  already disabled: $pkg"; continue }
            if ($WhatIf) { Write-Host "  would disable $pkg"; continue }
            $r = (Sh "pm disable-user --user 0 $pkg") -join ' '
            if ($r -match 'disabled') {
                Write-Change "disable`t$pkg`tprev=enabled"
                Write-Host "  disabled $pkg" -ForegroundColor Green
                $done++
            } elseif ($r -match 'illegal') {
                # Some operator firmwares (yes+) block disable-user. Suspend is the next best reversible
                # switch: the app can't open, its activities stop, and it is barred from running in the
                # background. Undo: pm unsuspend + appops back to the logged mode.
                $prevOp = ((Sh "cmd appops get $pkg RUN_ANY_IN_BACKGROUND") -join ' ') -replace '.*RUN_ANY_IN_BACKGROUND:\s*(\w+).*', '$1'
                if ($prevOp -notmatch '^(allow|ignore|deny|default|foreground)$') { $prevOp = 'default' }
                $s = (Sh "pm suspend --user 0 $pkg") -join ' '
                if ($s -match 'suspended state: true') {
                    Write-Change "suspend`t$pkg`tprev=unsuspended"
                    Sh "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ignore" | Out-Null
                    Write-Change "appop`t$pkg`tRUN_ANY_IN_BACKGROUND`tprev=$prevOp`tnew=ignore"
                    Sh "am force-stop $pkg" | Out-Null
                    Write-Host "  suspended $pkg (disable is blocked on this firmware)" -ForegroundColor Green
                    $done++
                } else {
                    Write-Warning "  failed $pkg : $r / $s"
                    $failed += $pkg
                }
            } else {
                Write-Warning "  failed $pkg : $r"
                $failed += $pkg
            }
        }
        'setting' {
            $ns, $key, $val = $parts[1], $parts[2], ($parts[3..($parts.Count - 1)] -join ' ')
            $old = (Sh "settings get $ns $key") -join ''
            if ($old -eq $val) { Write-Host "  $ns/$key already $val"; continue }
            if ($WhatIf) { Write-Host "  would set $ns/$key = $val (now $old)"; continue }
            Sh "settings put $ns $key $val" | Out-Null
            Write-Change "setting`t$ns`t$key`tprev=$old`tnew=$val"
            Write-Host "  $ns/$key : $old -> $val" -ForegroundColor Green
            $done++
        }
        default { Write-Warning "unknown plan line: $op" }
    }
    if (-not $WhatIf -and $done -gt 0 -and ($done % $Batch) -eq 0) {
        $h = Test-Healthy
        Save-Shot ('after-{0:d3}' -f $done) $shotDir | Out-Null
        if (-not $h.ok) {
            Write-Warning "TV looks unhealthy after the last batch ($($h.focus)). Stopping. Run scripts\restore.ps1 if needed."
            exit 2
        }
    }
}

if (-not $WhatIf) {
    Sh 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep 3
    $h = Test-Healthy
    Save-Shot 'final-home' $shotDir | Out-Null
    Write-Host ''
    Write-Host "Changes applied: $done   Failed: $($failed.Count) $($failed -join ', ')"
    Write-Host "Focus now: $($h.focus)"
    Write-Host "Screenshots: $shotDir"
}
