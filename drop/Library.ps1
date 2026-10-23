# LeanTV Library's background worker: scans the shared folders and keeps the TV's adb tunnel alive.
# Runs in its own runspace (so the tray menu never freezes) and talks to the tray through $shared:
#   in:  Folders (string[]), ScanNow (bool), Quit (bool)
#   out: Files (id -> path), Json (UTF-8 bytes for /api/library), Count, Shows, TvOk, Status, LastScan

# Turns a release-style file name into display fields:
#   "www.UIndex.org - Nathan for You S02E08 1080p AMZN WEB-DL" -> show "Nathan for You", season 2, episode 8
#   "Some.Movie.2019.1080p.BluRay.x264"                        -> title "Some Movie", year 2019
function ConvertFrom-ReleaseName([string]$name) {
    $junk = '(?i)[\s\[\(]*\b(2160p|1080p|720p|576p|480p|4k|uhd|hdr|web[\s-]?dl|web[\s-]?rip|web|bluray|blu[\s-]?ray|brrip|bdrip|hdrip|dvdrip|hdtv|x26[45]|h[\s.]?26[45]|hevc|avc|aac\d?|ac3|e?ac3|ddp?\s?\d|dts|atmos|amzn|nf|dsnp|hmax|atvp|pcok|hulu|proper|repack|10bit|8bit|remux|extended|unrated|imax)\b.*$'
    $s = $name -replace '(?i)^\s*(www\.)?[a-z0-9-]+\.(org|com|net|to|me|io|cc|tv)\s*-+\s*', ''
    $s = ($s -replace '[._]+', ' ' -replace '\s+', ' ').Trim()
    $r = @{ title = $s; show = $null; s = 0; e = 0; y = 0 }
    if ($s -match '^(?<show>.*?)[\s\-\[\(]*\bS(?<s>\d{1,2})\s?E(?<e>\d{1,3})(?:\s?-?\s?E\d{1,3})?\b(?<rest>.*)$' -or
        $s -match '^(?<show>.*?)[\s\-\[\(]*\b(?<s>\d{1,2})x(?<e>\d{2,3})\b(?<rest>.*)$') {
        $show = ($Matches.show -replace '\s*\b(19|20)\d{2}\s*$', '' -replace '[\s\-\(\[]+$', '').Trim()
        $rest = (($Matches.rest -replace $junk, '') -replace '^[\s\-\]\)]+', '' -replace '[\s\-\(\[]+$', '').Trim()
        if ($show) {
            if ($show -ceq $show.ToLowerInvariant()) { $show = (Get-Culture).TextInfo.ToTitleCase($show) }
            $r.show = $show; $r.s = [int]$Matches.s; $r.e = [int]$Matches.e; $r.title = $rest
            return $r
        }
    }
    if ($s -match '^(?<t>.+?)[\s\(\[]+(?<y>(19|20)\d{2})\b') {
        $r.title = $Matches.t.Trim(); $r.y = [int]$Matches.y
    } else {
        $t = ($s -replace $junk, '').Trim()
        if ($t) { $r.title = $t }
    }
    $r.title = ($r.title -replace '[\s\-\(\[]+$', '').Trim()
    if ($r.title -ceq $r.title.ToLowerInvariant()) { $r.title = (Get-Culture).TextInfo.ToTitleCase($r.title) }
    return $r
}

$LibraryWorker = {
    param($shared, $adb, $device, $port, $parserText)
    . ([scriptblock]::Create($parserText))
    $exts = '.mp4', '.m4v', '.mkv', '.mov', '.webm', '.ts', '.3gp', '.avi'
    $skipDirs = 'node_modules', '.git', 'AppData', '$RECYCLE.BIN', 'System Volume Information', 'Sample', 'Samples', 'Extras', 'Featurettes'
    $sha = [Security.Cryptography.SHA1]::Create()

    function Get-Videos([string]$dir, [int]$depth) {
        if ($depth -gt 6) { return }
        try {
            foreach ($f in [IO.Directory]::EnumerateFiles($dir)) {
                if ($exts -contains [IO.Path]::GetExtension($f).ToLower()) { $f }
            }
            foreach ($d in [IO.Directory]::EnumerateDirectories($dir)) {
                $leaf = [IO.Path]::GetFileName($d)
                if ($skipDirs -contains $leaf -or $leaf.StartsWith('.')) { continue }
                $attr = [IO.File]::GetAttributes($d)
                if ($attr -band ([IO.FileAttributes]::Hidden -bor [IO.FileAttributes]::System -bor [IO.FileAttributes]::ReparsePoint)) { continue }
                Get-Videos $d ($depth + 1)
            }
        } catch { }
    }

    function Invoke-Scan {
        $files = @{}; $items = New-Object System.Collections.ArrayList; $shows = @{}
        foreach ($root in @($shared.Folders)) {
            if (-not $root -or -not [IO.Directory]::Exists($root)) { continue }
            foreach ($path in (Get-Videos $root 0)) {
                try { $fi = New-Object IO.FileInfo($path) } catch { continue }
                # Skip clips too small to be worth a poster, and release "sample" files.
                if ($fi.Length -lt 10MB) { continue }
                $base = [IO.Path]::GetFileNameWithoutExtension($path)
                if ($base -match '(?i)(^|[\s._-])sample([\s._-]|$)' -and $fi.Length -lt 400MB) { continue }
                if ($base -match '(?i)^(screen ?recording|screencast|recording)\b') { continue }
                $p = ConvertFrom-ReleaseName $base
                # Generic file names ("video", "movie") take their name from the folder instead.
                if (-not $p.show -and $base.Length -lt 4) { $p = ConvertFrom-ReleaseName ([IO.Path]::GetFileName($fi.DirectoryName)) }
                $hash = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($path.ToLowerInvariant()))
                $id = (-join ($hash[0..5] | ForEach-Object { $_.ToString('x2') }))
                $files[$id] = $path
                if ($p.show) { $shows[$p.show] = 1 }
                [void]$items.Add([ordered]@{
                    id = $id; title = $p.title; show = $p.show; season = $p.s; episode = $p.e; year = $p.y
                    size = $fi.Length; added = [long]($fi.LastWriteTimeUtc - [datetime]'1970-01-01').TotalMilliseconds
                    ext = [IO.Path]::GetExtension($path).TrimStart('.').ToLower()
                })
            }
        }
        $doc = [ordered]@{ name = $env:COMPUTERNAME; items = @($items) }
        $shared.Files = [hashtable]::Synchronized($files)
        $shared.Json = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $doc -Depth 4 -Compress))
        $shared.Count = $items.Count
        $shared.Shows = $shows.Count
        $shared.LastScan = Get-Date
    }

    function Invoke-Adb { $ErrorActionPreference = 'Continue'; (& $adb @args 2>&1 | ForEach-Object { "$_" }) }

    # The tunnel disappears whenever the TV reboots or Wi-Fi ADB drops; put it back.
    function Update-Tunnel {
        $state = (Invoke-Adb -s $device get-state | Select-Object -Last 1)
        if ($state -ne 'device') {
            Invoke-Adb connect $device | Out-Null
            $state = (Invoke-Adb -s $device get-state | Select-Object -Last 1)
        }
        if ($state -ne 'device') { $shared.TvOk = $false; return }
        $list = (Invoke-Adb -s $device reverse --list) -join "`n"
        if ($list -notmatch "tcp:$port\s+tcp:$port") { Invoke-Adb -s $device reverse "tcp:$port" "tcp:$port" | Out-Null }
        $shared.TvOk = $true
    }

    $nextScan = [datetime]::MinValue; $nextTunnel = [datetime]::MinValue
    while (-not $shared.Quit) {
        $now = Get-Date
        try {
            if ($shared.ScanNow -or $now -ge $nextScan) {
                $shared.ScanNow = $false
                Invoke-Scan
                $nextScan = (Get-Date).AddSeconds(90)
            }
            if ($now -ge $nextTunnel) {
                Update-Tunnel
                $nextTunnel = (Get-Date).AddSeconds(15)
            }
        } catch { $shared.Status = "Error: $_" }
        Start-Sleep -Milliseconds 500
    }
}
