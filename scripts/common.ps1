# Shared helpers. Dot-source from the other scripts:  . "$PSScriptRoot\common.ps1"
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
# LEANTV_DATA redirects backups/shots/plan (used to test against an emulator without touching the real baseline).
$Data = if ($env:LEANTV_DATA) { $env:LEANTV_DATA } else { $Root }
$Backups = Join-Path $Data 'backups'
$ChangeLog = Join-Path $Backups 'changes.log'
$DeviceFile = Join-Path $Data '.device'

$Adb = @(
    (Join-Path $Root 'tools\platform-tools\adb.exe'),
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Adb) { $Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $Adb) { throw 'adb not found. Put platform-tools in tools\platform-tools.' }

function Get-Device {
    if ($env:LEANTV_DEVICE) { return $env:LEANTV_DEVICE }
    if (Test-Path $DeviceFile) { return (Get-Content $DeviceFile -Raw).Trim() }
    throw 'No device yet. Run scripts\connect.ps1 first.'
}

# Raw adb call; returns stdout+stderr lines as strings, never throws on stderr.
function Adb {
    $ErrorActionPreference = 'Continue'
    & $Adb @args 2>&1 | ForEach-Object { "$_" }
}

# adb -s <device> ...
function AdbD {
    $ErrorActionPreference = 'Continue'
    & $Adb -s (Get-Device) @args 2>&1 | ForEach-Object { "$_" }
}

# Run a shell command on the TV; returns output lines.
function Sh([string]$cmd) {
    AdbD shell $cmd
}

function Assert-Online {
    $state = (AdbD get-state | Select-Object -Last 1)
    if ($state -ne 'device') {
        # Wi-Fi ADB drops when the TV sleeps; try to reconnect once.
        Adb connect (Get-Device) | Out-Null
        Start-Sleep 2
        $state = (AdbD get-state | Select-Object -Last 1)
        if ($state -ne 'device') { throw "TV $(Get-Device) is not reachable (state: $state)" }
    }
}

# Screenshot from the TV into $Dir\<name>.png. Uses pull (piping binary through PowerShell corrupts it).
function Save-Shot([string]$name, [string]$Dir = (Join-Path $Data 'shots')) {
    New-Item -ItemType Directory -Force $Dir | Out-Null
    $out = Join-Path $Dir "$name.png"
    Sh 'screencap -p /data/local/tmp/leantv_shot.png' | Out-Null
    AdbD pull /data/local/tmp/leantv_shot.png "$out" | Out-Null
    Sh 'rm -f /data/local/tmp/leantv_shot.png' | Out-Null
    if (Test-Path $out) { Write-Host "  screenshot: $out" } else { Write-Warning "screenshot failed: $name" }
    return $out
}

function Write-Change([string]$line) {
    New-Item -ItemType Directory -Force $Backups | Out-Null
    $stamp = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -Encoding UTF8 $ChangeLog "$stamp`t$line"
}

# The first backup ever taken is the restore baseline; later backups never replace it.
function Get-Baseline {
    $f = Join-Path $Backups 'BASELINE'
    if (-not (Test-Path $f)) { return $null }
    return Join-Path $Backups ((Get-Content $f -Raw).Trim())
}

function Get-Packages([string]$flags = '') {
    Sh "pm list packages $flags" | Where-Object { $_ -like 'package:*' } |
        ForEach-Object { ($_ -replace '^package:', '').Trim() } | Sort-Object
}

# Packages suspended for user 0 (pm has no list filter for this, so read it from dumpsys).
function Get-Suspended {
    $pkg = $null
    Sh "dumpsys package packages | grep -E '^  Package \[|User 0:.*suspended='" | ForEach-Object {
        if ($_ -match '^\s*Package \[([^\]]+)\]') { $pkg = $Matches[1] }
        elseif ($pkg -and $_ -match 'suspended=true') { $pkg }
    } | Sort-Object -Unique
}

# Packages that must never be disabled, whatever a plan says.
$Protected = @(
    'android', 'com.android.systemui', 'com.android.settings', 'com.android.tv.settings',
    'com.android.vending', 'com.google.android.gms', 'com.google.android.gsf',
    'com.android.packageinstaller', 'com.google.android.packageinstaller',
    'com.android.permissioncontroller', 'com.google.android.permissioncontroller',
    'com.android.shell', 'com.android.phone', 'com.android.bluetooth', 'com.android.networkstack',
    'com.google.android.networkstack', 'com.android.inputdevices', 'com.android.externalstorage',
    'com.android.webview', 'com.google.android.webview', 'com.android.keychain',
    'com.android.location.fused', 'com.android.wifi.resources', 'com.android.tethering',
    'com.google.android.tungsten.setupwraith', 'com.android.tv.frameworkpackagestubs',
    'com.google.android.tv.remote.service', 'com.google.android.marvin.talkback',
    'com.android.providers.settings', 'com.android.providers.media', 'com.android.providers.downloads',
    'com.android.providers.contacts', 'com.android.providers.tv', 'com.android.providers.media.module',
    'com.google.android.inputmethod.latin', 'com.google.android.leanback.ime',
    'com.leantv.home'
)
