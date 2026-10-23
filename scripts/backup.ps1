<#
Snapshots the TV's state into backups\<timestamp>\ before anything is changed.
The very first snapshot becomes the restore BASELINE and is never replaced.

  .\scripts\backup.ps1              # package states, settings, home screen, screenshot
  .\scripts\backup.ps1 -PullApks    # also copy the APKs of user-installed apps to the PC
#>
param([switch]$PullApks)
. "$PSScriptRoot\common.ps1"
Assert-Online

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dir = Join-Path $Backups $stamp
New-Item -ItemType Directory -Force $dir | Out-Null
Write-Host "Backing up $(Get-Device) -> $dir"

function Save([string]$file, $lines) {
    $lines | Set-Content -Encoding UTF8 (Join-Path $dir $file)
}

Save 'props.txt' (Sh 'getprop')
Save 'packages_all.txt' (Sh 'pm list packages -f -u -i')       # path, installer, incl. uninstalled-for-user
Save 'installed.txt' (Get-Packages)
Save 'installed_or_not.txt' (Get-Packages '-u')
Save 'disabled.txt' (Get-Packages '-d')
Save 'suspended.txt' (Get-Suspended)
Save 'enabled.txt' (Get-Packages '-e')
Save 'system.txt' (Get-Packages '-s')
Save 'third_party.txt' (Get-Packages '-3')
foreach ($ns in 'global', 'secure', 'system') { Save "settings_$ns.txt" (Sh "settings list $ns") }
Save 'home.txt' (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1)
Save 'home_candidates.txt' (Sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
Save 'preferred_activities.txt' (Sh 'dumpsys package preferred-xml')
Save 'meminfo.txt' (Sh 'dumpsys meminfo -s')
Save 'storage.txt' (Sh 'df -h /data')
Save 'running.txt' (Sh 'ps -A -o PID,RSS,NAME')
Save 'appops_run_any.txt' (Sh 'cmd appops query-op RUN_ANY_IN_BACKGROUND ignore')

if ($PullApks) {
    $apkDir = Join-Path $dir 'apks'
    New-Item -ItemType Directory -Force $apkDir | Out-Null
    foreach ($pkg in (Get-Packages '-3')) {
        $paths = Sh "pm path $pkg" | Where-Object { $_ -like 'package:*' } | ForEach-Object { $_ -replace '^package:', '' }
        $i = 0
        foreach ($p in $paths) {
            $name = if ($paths.Count -gt 1) { "$pkg.$i.apk" } else { "$pkg.apk" }
            AdbD pull $p (Join-Path $apkDir $name) | Out-Null
            $i++
        }
        Write-Host "  pulled $pkg"
    }
}

Save-Shot '00_home_before' $dir | Out-Null

$baselineFile = Join-Path $Backups 'BASELINE'
if (-not (Test-Path $baselineFile)) {
    Set-Content -Encoding ASCII $baselineFile $stamp
    Write-Host "This is the restore BASELINE: $stamp" -ForegroundColor Green
}
Write-Change "backup $stamp"
$n = (Get-Content (Join-Path $dir 'installed.txt')).Count
$d = (Get-Content (Join-Path $dir 'disabled.txt') | Where-Object { $_ }).Count
Write-Host "Done: $n packages ($d already disabled), home = $(Get-Content (Join-Path $dir 'home.txt'))"
