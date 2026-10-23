<#
Classifies every package on the TV using debloat\catalog.csv and writes:
  debloat\report.md   human-readable list with app names
  debloat\plan.txt    the debloat plan. Only clear-cut ads/telemetry/junk are active;
                      everything you might use is commented out (#disable) until you decide.

  .\scripts\audit.ps1            # keeps an existing plan.txt
  .\scripts\audit.ps1 -Force     # regenerate plan.txt
#>
param([switch]$Force, [switch]$NoLabels)
. "$PSScriptRoot\common.ps1"
Assert-Online

$catalog = Import-Csv (Join-Path $Root 'debloat\catalog.csv')
$installed = Get-Packages '-u'          # includes disabled and uninstalled-for-user
$disabled = Get-Packages '-d'
$thirdParty = Get-Packages '-3'
$paths = @{}
Sh 'pm list packages -f -u' | ForEach-Object {
    if ($_ -match '^package:(.+\.apk)=(.+)$') { $paths[$Matches[2].Trim()] = $Matches[1] }
}

# App names: pull small APKs and read their label with aapt2 (the TV has no tool for this).
$aapt2 = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt2.exe" -ErrorAction SilentlyContinue |
    Select-Object -Last 1
$tmp = Join-Path ([IO.Path]::GetTempPath()) 'leantv-apk'
New-Item -ItemType Directory -Force $tmp | Out-Null
function Get-Label([string]$pkg) {
    if ($NoLabels -or -not $aapt2 -or -not $paths[$pkg]) { return '' }
    $size = (Sh "stat -c %s $($paths[$pkg])") -join ''
    if (-not ($size -match '^\d+$') -or [int64]$size -gt 60MB) { return '' }
    $local = Join-Path $tmp 'a.apk'
    AdbD pull $paths[$pkg] $local | Out-Null
    if (-not (Test-Path $local)) { return '' }
    $ErrorActionPreference = 'Continue'
    $line = & $aapt2.FullName dump badging $local 2>$null | Where-Object { $_ -like 'application-label:*' } | Select-Object -First 1
    Remove-Item $local -Force
    if ($line -match "'(.*)'") { return $Matches[1] }
    return ''
}

function Match-Catalog([string]$pkg, [switch]$Wildcard) {
    if (-not $Wildcard) { return $catalog | Where-Object { $_.pattern -eq $pkg } | Select-Object -First 1 }
    return $catalog | Where-Object { $_.pattern -like '*`**' -and $pkg -like $_.pattern } | Select-Object -First 1
}

$rows = foreach ($pkg in $installed) {
    $exact = Match-Catalog $pkg
    $wild = Match-Catalog $pkg -Wildcard
    $isModule = $pkg -like 'com.google.mainline.*' -or $pkg -like 'com.android.*.module*' -or "$($paths[$pkg])" -like '/apex/*'
    if ($Protected -contains $pkg) { $action = 'keep'; $cat = 'core'; $note = 'Protected system component' }
    elseif ($exact) { $action = $exact.action; $cat = $exact.category; $note = $exact.note }
    elseif ($isModule) { $action = 'keep'; $cat = 'system'; $note = 'Android mainline module' }
    elseif ($wild) { $action = $wild.action; $cat = $wild.category; $note = $wild.note }
    elseif ($thirdParty -contains $pkg) { $action = 'ask'; $cat = 'app'; $note = 'Installed app' }
    elseif ($pkg -match '^(com\.android\.|com\.google\.android\.(gms|gsf|ext|modulemetadata|overlay)|android\.)' -or $pkg -match 'overlay|\.auto_generated_rro') {
        $action = 'keep'; $cat = 'system'; $note = 'Android system component'
    }
    else { $action = 'review'; $cat = 'vendor'; $note = 'Manufacturer / operator / Google extra - not in catalog' }
    $label = if ($action -in 'ask', 'review', 'disable') { Get-Label $pkg } else { '' }
    [pscustomobject]@{
        pkg = $pkg; label = $label; action = $action; category = $cat; note = $note
        disabled = ($disabled -contains $pkg); path = $paths[$pkg]
    }
}

# ---- report ----
$model = "$((Sh 'getprop ro.product.manufacturer') -join '') $((Sh 'getprop ro.product.model') -join '')"
$rel = (Sh 'getprop ro.build.version.release') -join ''
$md = @("# Package audit - $model (Android $rel) - $(Get-Date -Format 'yyyy-MM-dd HH:mm')", '')
foreach ($group in @(
        @{ a = 'disable'; t = 'Will be disabled (ads / telemetry / junk)' },
        @{ a = 'ask'; t = 'Your call (apps and features you might use)' },
        @{ a = 'review'; t = 'Manufacturer / operator extras (reviewed one by one)' },
        @{ a = 'keep'; t = 'Kept (system)' })) {
    $g = @($rows | Where-Object action -eq $group.a)
    $md += "## $($group.t) - $($g.Count)", '', '| Package | Name | Category | Note | Already disabled |', '|---|---|---|---|---|'
    foreach ($r in $g) { $md += "| ``$($r.pkg)`` | $($r.label) | $($r.category) | $($r.note) | $(if ($r.disabled) {'yes'}) |" }
    $md += ''
}
New-Item -ItemType Directory -Force (Join-Path $Data 'debloat') | Out-Null
$md | Set-Content -Encoding UTF8 (Join-Path $Data 'debloat\report.md')

# ---- plan ----
function Describe($r, [switch]$Path) {
    $what = if ($Path) { $r.path } else { $r.note }
    if ($r.label) { return "$($r.label) - $what" }
    return $what
}
New-Item -ItemType Directory -Force (Join-Path $Data 'debloat') | Out-Null
$planFile = Join-Path $Data 'debloat\plan.txt'
if ((Test-Path $planFile) -and -not $Force) {
    Write-Host "Kept existing $planFile (use -Force to regenerate)."
} else {
    $plan = @(
        '# LeanTV debloat plan. One command per line:',
        '#   disable <package>        pm disable-user (reversible; nothing is uninstalled)',
        '#   setting <ns> <key> <val> settings put (old value is logged for restore)',
        '# Lines starting with # are ignored. Remove the # to opt a package in.',
        ''
        '# ---- ads / telemetry / junk: disabled by default ----'
    )
    $plan += $rows | Where-Object { $_.action -eq 'disable' -and -not $_.disabled } |
        ForEach-Object { "disable $($_.pkg)    # $(Describe $_)" }
    $plan += '', '# ---- faster UI: halve animation durations ----',
        'setting global window_animation_scale 0.5',
        'setting global transition_animation_scale 0.5',
        'setting global animator_duration_scale 0.5',
        '', '# ---- YOUR CALL: apps / features you might use (left enabled) ----'
    $plan += $rows | Where-Object { $_.action -eq 'ask' -and -not $_.disabled } |
        ForEach-Object { "#disable $($_.pkg)    # $(Describe $_)" }
    $plan += '', '# ---- manufacturer / operator extras (left enabled until reviewed) ----'
    $plan += $rows | Where-Object { $_.action -eq 'review' -and -not $_.disabled } |
        ForEach-Object { "#disable $($_.pkg)    # $(Describe $_ -Path)" }
    $plan | Set-Content -Encoding UTF8 $planFile
    Write-Host "Wrote $planFile"
}
$rows | Group-Object action | ForEach-Object { Write-Host ("  {0,-8} {1}" -f $_.Name, $_.Count) }
Write-Host "Report: $(Join-Path $Data 'debloat\report.md')"
