<#
Finds the TV on the LAN and connects ADB over Wi-Fi.

  .\scripts\connect.ps1                         # auto: known IP, then scan the subnet for port 5555
  .\scripts\connect.ps1 -Ip 192.168.0.52
  .\scripts\connect.ps1 -Pair 192.168.0.52:37123 -Code 123456 -Ip 192.168.0.52:41234
        # Android 11+ "Wireless debugging": pair once with the code shown on the TV, then connect.

On the TV first: Settings > Device Preferences > About > click "Build" 7 times,
then Settings > Device Preferences > Developer options > turn on "USB debugging"
(and "Network debugging" / "ADB over network" if the box has it).
#>
param(
    [string]$Ip,
    [string]$Pair,
    [string]$Code
)
. "$PSScriptRoot\common.ps1"

if ($Pair) {
    if (-not $Code) { throw '-Pair needs -Code (the 6-digit code on the TV)' }
    Adb pair $Pair $Code
}

function Try-Connect([string]$target) {
    if ($target -notmatch ':') { $target = "${target}:5555" }
    $r = (Adb connect $target) -join ' '
    Write-Host "  adb connect $target -> $r"
    if ($r -match 'connected to') { return $target }
    return $null
}

$candidates = @()
if ($Ip) { $candidates += $Ip }
if (Test-Path $DeviceFile) { $candidates += (Get-Content $DeviceFile -Raw).Trim() }
$candidates += '192.168.0.52'   # the SEI Robotics box found on 2026-10-23 (Cast name "yes")

# Wireless-debugging devices advertise themselves over mDNS.
Adb mdns services | Where-Object { $_ -match '_adb-tls-connect' } | ForEach-Object {
    if ($_ -match '(\d+\.\d+\.\d+\.\d+:\d+)') { $candidates += $Matches[1] }
}

$connected = $null
foreach ($c in ($candidates | Select-Object -Unique)) {
    $connected = Try-Connect $c
    if ($connected) { break }
}

if (-not $connected) {
    Write-Host 'Scanning the local subnet for ADB (port 5555)...'
    $prefix = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway } | Select-Object -First 1).IPv4Address.IPAddress -replace '\.\d+$', ''
    $probes = 1..254 | ForEach-Object {
        $cl = New-Object System.Net.Sockets.TcpClient
        [pscustomobject]@{ ip = "$prefix.$_"; c = $cl; t = $cl.ConnectAsync("$prefix.$_", 5555) }
    }
    Start-Sleep -Milliseconds 2500
    $open = $probes | Where-Object { $_.t.Status -eq 'RanToCompletion' -and $_.c.Connected } | ForEach-Object { $_.ip }
    $probes | ForEach-Object { $_.c.Dispose() }
    foreach ($o in $open) { $connected = Try-Connect $o; if ($connected) { break } }
}

if (-not $connected) {
    Write-Host ''
    Write-Host 'Could not reach ADB on the TV. Enable it on the TV:' -ForegroundColor Yellow
    Write-Host '  Settings > Device Preferences > About > press OK on "Build" 7 times'
    Write-Host '  Settings > Device Preferences > Developer options > USB debugging = ON'
    Write-Host '  (if listed: "Network debugging" / "ADB debugging over network" = ON)'
    Write-Host '  Android 11+: Developer options > Wireless debugging > Pair device with pairing code,'
    Write-Host '  then run: .\scripts\connect.ps1 -Pair <ip:pairport> -Code <code> -Ip <ip:port>'
    exit 1
}

# First connection from this PC shows "Allow USB debugging?" on the TV. Wait for it to be accepted.
Write-Host "Waiting for authorisation on the TV (tick 'Always allow from this computer' and press OK)..."
$deadline = (Get-Date).AddMinutes(3)
do {
    $line = Adb devices | Where-Object { $_ -like "$connected*" } | Select-Object -First 1
    if ($line -match '\sdevice$') { break }
    Start-Sleep 2
} while ((Get-Date) -lt $deadline)
if ($line -notmatch '\sdevice$') { throw "TV did not authorise this PC ($line)" }

Set-Content -Encoding ASCII $DeviceFile $connected
$env:LEANTV_DEVICE = $connected
Write-Host ''
Write-Host "Connected: $connected" -ForegroundColor Green
foreach ($p in 'ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.fingerprint') {
    Write-Host ("  {0,-28} {1}" -f $p, ((Sh "getprop $p") -join ''))
}
