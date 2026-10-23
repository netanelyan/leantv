# Tiny HTTP server for the TV, shared by LeanTV Drop and LeanTV Library. Listens on loopback only; the TV
# reaches it through "adb reverse", so nothing is opened on the network and no firewall rule is needed.
#
#   GET /v/<key>          the file $Shared.Files[<key>], with Range support (the player seeks with it)
#   GET /api/library      $Shared.Json (UTF-8 bytes), the library listing
#
# Dot-source, then: $s = Start-LeanServer -Ports 8765..8785 -Shared $Shared; ...; Stop-LeanServer $s

$LeanHandler = {
    param($client, $shared)
    $mime = @{ '.mp4' = 'video/mp4'; '.m4v' = 'video/mp4'; '.mkv' = 'video/x-matroska'; '.webm' = 'video/webm';
               '.mov' = 'video/quicktime'; '.ts' = 'video/mp2t'; '.3gp' = 'video/3gpp'; '.avi' = 'video/x-msvideo' }
    $net = $null; $fs = $null
    function Send-Head($net, [string]$h) { $b = [Text.Encoding]::ASCII.GetBytes($h); $net.Write($b, 0, $b.Length) }
    try {
        $client.NoDelay = $true
        $net = $client.GetStream()
        $net.ReadTimeout = 20000
        # Read the request head (up to the blank line).
        $buf = New-Object byte[] 16384; $n = 0
        while ($n -lt $buf.Length) {
            $r = $net.Read($buf, $n, $buf.Length - $n)
            if ($r -le 0) { break }
            $n += $r
            if ([Text.Encoding]::ASCII.GetString($buf, 0, $n).Contains("`r`n`r`n")) { break }
        }
        $head = [Text.Encoding]::ASCII.GetString($buf, 0, $n) -split "`r`n"
        $method, $target = ($head[0] -split ' ')[0, 1]
        $range = ($head | Where-Object { $_ -match '^Range:\s*bytes=' } | Select-Object -First 1)

        if ($target -like '/api/library*') {
            $body = $shared.Json
            if (-not $body) { $body = [Text.Encoding]::UTF8.GetBytes('{"items":[]}') }
            Send-Head $net "HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($body.Length)`r`nCache-Control: no-store`r`nConnection: close`r`n`r`n"
            if ($method -ne 'HEAD') { $net.Write($body, 0, $body.Length) }
            return
        }

        $path = $null
        if ($target -match '^/v/([A-Za-z0-9]+)') { $path = $shared.Files[$Matches[1]] }
        if (-not $path -or -not (Test-Path -LiteralPath $path)) {
            Send-Head $net "HTTP/1.1 404 Not Found`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"; return
        }
        $fs = [IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
        $len = $fs.Length
        $start = 0L; $end = $len - 1; $status = '200 OK'
        if ($range -and $range -match 'bytes=(\d*)-(\d*)') {
            if ($Matches[1] -eq '') { $start = [Math]::Max(0, $len - [long]$Matches[2]) }
            else {
                $start = [long]$Matches[1]
                if ($Matches[2] -ne '') { $end = [Math]::Min($len - 1, [long]$Matches[2]) }
            }
            if ($start -ge $len) {
                Send-Head $net "HTTP/1.1 416 Range Not Satisfiable`r`nContent-Range: bytes */$len`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"; return
            }
            $status = '206 Partial Content'
        }
        $count = $end - $start + 1
        $type = $mime[[IO.Path]::GetExtension($path).ToLower()]; if (-not $type) { $type = 'application/octet-stream' }
        $h = "HTTP/1.1 $status`r`nContent-Type: $type`r`nContent-Length: $count`r`nAccept-Ranges: bytes`r`n"
        if ($status -like '206*') { $h += "Content-Range: bytes $start-$end/$len`r`n" }
        Send-Head $net ($h + "Connection: close`r`n`r`n")
        if ($method -eq 'HEAD') { return }
        $fs.Position = $start
        $chunk = New-Object byte[] 262144
        while ($count -gt 0) {
            $r = $fs.Read($chunk, 0, [int][Math]::Min($chunk.Length, $count))
            if ($r -le 0) { break }
            $net.Write($chunk, 0, $r)
            $count -= $r
        }
    } catch {
        # The player closes connections whenever it seeks; that is normal.
    } finally {
        if ($fs) { $fs.Dispose() }
        if ($net) { $net.Dispose() }
        $client.Close()
    }
}

function Start-LeanServer([int[]]$Ports, [hashtable]$Shared) {
    $listener = $null; $port = 0
    foreach ($p in $Ports) {
        try {
            $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $p)
            $l.Start()
            $listener = $l; $port = $p
            break
        } catch { }
    }
    if (-not $listener) { throw "No free local port ($($Ports[0])-$($Ports[-1]))." }
    $pool = [runspacefactory]::CreateRunspacePool(1, 12)
    $pool.Open()
    $accept = [powershell]::Create()
    [void]$accept.AddScript({
        param($listener, $pool, $handlerText, $shared)
        $handler = [scriptblock]::Create($handlerText)
        $running = New-Object System.Collections.ArrayList
        while ($true) {
            try { $c = $listener.AcceptTcpClient() } catch { break }
            foreach ($j in @($running)) { if ($j.H.IsCompleted) { $j.P.Dispose(); $running.Remove($j) } }
            $ps = [powershell]::Create(); $ps.RunspacePool = $pool
            [void]$ps.AddScript($handler).AddArgument($c).AddArgument($shared)
            [void]$running.Add(@{ P = $ps; H = $ps.BeginInvoke() })
        }
    }).AddArgument($listener).AddArgument($pool).AddArgument($LeanHandler.ToString()).AddArgument($Shared)
    $handle = $accept.BeginInvoke()
    return @{ Listener = $listener; Port = $port; Pool = $pool; Accept = $accept; Handle = $handle }
}

function Stop-LeanServer($server) {
    if (-not $server) { return }
    try { $server.Listener.Stop() } catch { }
    try { AdbD reverse --remove "tcp:$($server.Port)" | Out-Null } catch { }
    try { $server.Pool.Close() } catch { }
}
