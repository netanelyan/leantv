<#
LeanTV Drop: drag video files onto the window (or onto the "LeanTV Drop" shortcut) and they play on the TV.

Stream (default): the file stays on this computer and is served to the TV through the adb connection
  (adb reverse -> 127.0.0.1 on the TV), so nothing is stored on the TV and playback starts at once.
  Keep this window open while you watch.
Copy to TV: the file is copied into LeanTV Home's own folder on the TV (Videos tile on the home screen)
  and plays without this computer. The TV has little free space, so this is for short clips.

Run via "LeanTV Drop.bat" (or: powershell -STA -File drop\LeanTVDrop.ps1 [files...]).
#>
param([string[]]$Paths)

. "$PSScriptRoot\..\scripts\common.ps1"
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$Pkg = 'com.leantv.home'
$RemoteDir = "/sdcard/Android/data/$Pkg/files/videos"
$VideoExt = '.mp4', '.m4v', '.mkv', '.mov', '.webm', '.ts', '.3gp'

# ---------------------------------------------------------------- streaming server (loopback only)

. "$PSScriptRoot\Server.ps1"
$Shared = [hashtable]::Synchronized(@{ Files = [hashtable]::Synchronized(@{}); Next = 0 })

# ---------------------------------------------------------------- TV side

function Test-Tv {
    try {
        $s = (AdbD get-state | Select-Object -Last 1)
        if ($s -ne 'device') { Adb connect (Get-Device) | Out-Null; $s = (AdbD get-state | Select-Object -Last 1) }
        return $s -eq 'device'
    } catch { return $false }
}

function Get-TvFreeBytes {
    $line = (Sh "df -k $RemoteDir | tail -1") -join ' '
    $f = $line -split '\s+'
    if ($f.Count -ge 4 -and $f[3] -match '^\d+$') { return [long]$f[3] * 1024 }
    return -1
}

function B64([string]$s) { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($s)) }

function Start-Player([string]$uri, [string]$title) {
    Sh 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Sh "am start -n $Pkg/.PlayerActivity -a android.intent.action.VIEW -d '$uri' --es title_b64 '$(B64 $title)'" | Out-Null
}

# ---------------------------------------------------------------- window

$bg = [Drawing.Color]::FromArgb(17, 20, 24)
$panelBg = [Drawing.Color]::FromArgb(27, 31, 37)
$fg = [Drawing.Color]::FromArgb(242, 244, 247)
$muted = [Drawing.Color]::FromArgb(150, 158, 170)
$accent = [Drawing.Color]::FromArgb(242, 244, 247)
$ok = [Drawing.Color]::FromArgb(70, 200, 120)
$bad = [Drawing.Color]::FromArgb(235, 90, 80)

$form = New-Object Windows.Forms.Form
$form.Text = 'LeanTV Drop'
$form.ClientSize = New-Object Drawing.Size(520, 420)
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.StartPosition = 'CenterScreen'
$form.BackColor = $bg
$form.ForeColor = $fg
$form.Font = New-Object Drawing.Font('Segoe UI', 10)
$form.AllowDrop = $true
$icoPath = Join-Path $PSScriptRoot 'drop.ico'
if (Test-Path $icoPath) { $form.Icon = New-Object Drawing.Icon($icoPath) }

$title = New-Object Windows.Forms.Label
$title.Text = 'LeanTV Drop'
$title.Font = New-Object Drawing.Font('Segoe UI Semibold', 18)
$title.AutoSize = $true
$title.Location = New-Object Drawing.Point(28, 22)
$form.Controls.Add($title)

$conn = New-Object Windows.Forms.Label
$conn.AutoSize = $true
$conn.Font = New-Object Drawing.Font('Segoe UI', 9.5)
$conn.ForeColor = $muted
$conn.Location = New-Object Drawing.Point(31, 62)
$form.Controls.Add($conn)

# Drop zone, custom-painted: rounded dashed outline that lights up while something is dragged over it.
$zone = New-Object Windows.Forms.Panel
$zone.Location = New-Object Drawing.Point(28, 96)
$zone.Size = New-Object Drawing.Size(464, 200)
$zone.BackColor = $bg
$zone.AllowDrop = $true
$script:Hover = $false
$zone.Add_Paint({
    param($s, $e)
    $g = $e.Graphics
    $g.SmoothingMode = 'AntiAlias'
    $g.TextRenderingHint = 'ClearTypeGridFit'
    $r = New-Object Drawing.Rectangle(1, 1, ($s.Width - 3), ($s.Height - 3))
    $rad = 22
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $path.AddArc($r.X, $r.Y, $rad, $rad, 180, 90)
    $path.AddArc($r.Right - $rad, $r.Y, $rad, $rad, 270, 90)
    $path.AddArc($r.Right - $rad, $r.Bottom - $rad, $rad, $rad, 0, 90)
    $path.AddArc($r.X, $r.Bottom - $rad, $rad, $rad, 90, 90)
    $path.CloseFigure()
    $fill = if ($script:Hover) { [Drawing.Color]::FromArgb(36, 42, 50) } else { $panelBg }
    $g.FillPath((New-Object Drawing.SolidBrush($fill)), $path)
    $pen = New-Object Drawing.Pen(([Drawing.Color]::FromArgb(($(if ($script:Hover) { 200 } else { 70 })), 255, 255, 255)), 1.5)
    $pen.DashPattern = [single[]](5, 4)
    $g.DrawPath($pen, $path)
    # Play glyph
    $cx = $s.Width / 2; $cy = 70
    $tri = [Drawing.PointF[]]@((New-Object Drawing.PointF(($cx - 11), ($cy - 15))), (New-Object Drawing.PointF(($cx + 16), $cy)), (New-Object Drawing.PointF(($cx - 11), ($cy + 15))))
    $g.FillEllipse((New-Object Drawing.SolidBrush([Drawing.Color]::FromArgb(40, 255, 255, 255))), ($cx - 32), ($cy - 32), 64, 64)
    $g.FillPolygon((New-Object Drawing.SolidBrush($accent)), $tri)
    $sf = New-Object Drawing.StringFormat; $sf.Alignment = 'Center'
    $g.DrawString('Drop videos here', (New-Object Drawing.Font('Segoe UI Semibold', 13)), (New-Object Drawing.SolidBrush($fg)),
        (New-Object Drawing.RectangleF(0, 116, $s.Width, 30)), $sf)
    $sub = if ($script:CopyMode) { 'They are copied to the TV, then play' } else { 'They play on the TV straight away' }
    $g.DrawString($sub, (New-Object Drawing.Font('Segoe UI', 10)), (New-Object Drawing.SolidBrush($muted)),
        (New-Object Drawing.RectangleF(0, 146, $s.Width, 24)), $sf)
})
$form.Controls.Add($zone)

$copy = New-Object Windows.Forms.CheckBox
$copy.Text = 'Copy to the TV (plays without this computer; uses TV storage)'
$copy.AutoSize = $true
$copy.ForeColor = $muted
$copy.Location = New-Object Drawing.Point(30, 310)
$copy.Add_CheckedChanged({ $script:CopyMode = $copy.Checked; $zone.Invalidate() })
$form.Controls.Add($copy)

# Thin progress line + status text
$bar = New-Object Windows.Forms.Panel
$bar.Location = New-Object Drawing.Point(30, 348)
$bar.Size = New-Object Drawing.Size(460, 4)
$bar.BackColor = [Drawing.Color]::FromArgb(45, 50, 58)
$fillBar = New-Object Windows.Forms.Panel
$fillBar.BackColor = $accent
$fillBar.Size = New-Object Drawing.Size(0, 4)
$bar.Controls.Add($fillBar)
$bar.Visible = $false
$form.Controls.Add($bar)

$status = New-Object Windows.Forms.Label
$status.AutoSize = $false
$status.Size = New-Object Drawing.Size(460, 44)
$status.Location = New-Object Drawing.Point(30, 362)
$status.ForeColor = $muted
$status.Text = 'Keep this window open while a streamed video plays.'
$form.Controls.Add($status)

function Set-Status([string]$t, [Drawing.Color]$c = $muted) { $status.Text = $t; $status.ForeColor = $c; [Windows.Forms.Application]::DoEvents() }

function Update-Conn {
    if (Test-Tv) { $conn.Text = "$([char]0x25CF)  TV connected  ($(Get-Device))"; $conn.ForeColor = $ok }
    else { $conn.Text = "$([char]0x25CF)  TV not reachable - is it on? (run scripts\connect.ps1 once if this persists)"; $conn.ForeColor = $bad }
}

# ---------------------------------------------------------------- sending

$script:Push = $null

function Send-Files([string[]]$files) {
    $vids = @($files | Where-Object { (Test-Path -LiteralPath $_ -PathType Leaf) -and ($VideoExt -contains [IO.Path]::GetExtension($_).ToLower()) })
    if (-not $vids) { Set-Status 'That is not a video file (mp4, mkv, mov, webm, m4v, ts).' $bad; return }
    if ($script:Push) { Set-Status 'Still copying the previous video...' $bad; return }
    if (-not (Test-Tv)) { Update-Conn; Set-Status 'Cannot reach the TV. Make sure it is on and on the same Wi-Fi.' $bad; return }
    $file = $vids[0]
    $name = [IO.Path]::GetFileNameWithoutExtension($file) -replace '[_]+', ' '
    $more = if ($vids.Count -gt 1) { "  (only the first of $($vids.Count) files is played)" } else { '' }

    if (-not $copy.Checked) {
        AdbD reverse "tcp:$script:Port" "tcp:$script:Port" | Out-Null
        $id = [string](++$Shared.Next)
        $Shared.Files[$id] = $file
        Start-Player "http://127.0.0.1:$script:Port/v/$id" $name
        Set-Status "Playing on the TV: $name$more`nKeep this window open while it plays." $ok
        $script:Streamed = $true
        return
    }

    # Copy mode
    $size = (Get-Item -LiteralPath $file).Length
    $free = Get-TvFreeBytes
    if ($free -ge 0 -and $size -gt ($free - 300MB)) {
        Set-Status ("Not enough space on the TV: {0:N1} GB needed, {1:N1} GB free. Untick 'Copy' to stream it instead." -f ($size / 1GB), ([Math]::Max(0, $free) / 1GB)) $bad
        return
    }
    $safe = ([IO.Path]::GetFileName($file) -replace "[^\p{L}\p{Nd}._ -]", '_')
    $remote = "$RemoteDir/$safe"
    Sh "mkdir -p $RemoteDir" | Out-Null
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = "-s $(Get-Device) push `"$file`" `"$remote`""
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $script:Push = @{ P = [Diagnostics.Process]::Start($psi); Size = $size; Remote = $remote; Name = $name; Started = Get-Date }
    $bar.Visible = $true; $fillBar.Width = 0
    Set-Status "Copying $name to the TV...$more"
    $timer.Start()
}

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 800
$timer.Add_Tick({
    $p = $script:Push
    if (-not $p) { $timer.Stop(); return }
    if ($p.P.HasExited) {
        $timer.Stop(); $script:Push = $null; $bar.Visible = $false
        if ($p.P.ExitCode -eq 0) {
            $u = 'file://' + ((($p.Remote -split '/') | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/')
            Start-Player $u $p.Name
            Set-Status "Copied and playing: $($p.Name)`nIt is also on the TV under Videos." $ok
        } else {
            Set-Status ("Copy failed: " + ($p.P.StandardError.ReadToEnd() + $p.P.StandardOutput.ReadToEnd()).Trim()) $bad
            Sh "rm -f '$($p.Remote)'" | Out-Null
        }
        return
    }
    $now = (Sh "stat -c %s '$($p.Remote)' 2>/dev/null") -join ''
    if ($now -match '^\d+$' -and $p.Size -gt 0) {
        $frac = [Math]::Min(1, [long]$now / $p.Size)
        $fillBar.Width = [int]($bar.Width * $frac)
        $secs = ((Get-Date) - $p.Started).TotalSeconds
        $rate = if ($secs -gt 1) { [long]$now / $secs } else { 0 }
        $eta = if ($rate -gt 0) { [int](($p.Size - [long]$now) / $rate) } else { 0 }
        Set-Status ("Copying {0}...  {1:P0}  ({2:N1} MB/s, about {3}s left)" -f $p.Name, $frac, ($rate / 1MB), $eta)
    }
})

$dragEnter = {
    param($s, $e)
    if ($e.Data.GetDataPresent([Windows.Forms.DataFormats]::FileDrop)) { $e.Effect = 'Copy'; $script:Hover = $true; $zone.Invalidate() }
}
$dragLeave = { $script:Hover = $false; $zone.Invalidate() }
$dragDrop = {
    param($s, $e)
    $script:Hover = $false; $zone.Invalidate()
    Send-Files ([string[]]$e.Data.GetData([Windows.Forms.DataFormats]::FileDrop))
}
foreach ($c in $form, $zone) { $c.Add_DragEnter($dragEnter); $c.Add_DragLeave($dragLeave); $c.Add_DragDrop($dragDrop) }

# Click the zone to pick files instead.
$zone.Cursor = 'Hand'
$zone.Add_Click({
    $dlg = New-Object Windows.Forms.OpenFileDialog
    $dlg.Filter = 'Videos|*.mp4;*.m4v;*.mkv;*.mov;*.webm;*.ts;*.3gp|All files|*.*'
    $dlg.Multiselect = $true
    if ($dlg.ShowDialog($form) -eq 'OK') { Send-Files $dlg.FileNames }
})

$connTimer = New-Object Windows.Forms.Timer
$connTimer.Interval = 15000
$connTimer.Add_Tick({ if (-not $script:Push) { Update-Conn } })

$form.Add_Shown({
    Update-Conn
    $connTimer.Start()
    if ($Paths) { Send-Files $Paths }
})
$form.Add_FormClosing({
    param($s, $e)
    if ($script:Push) {
        $r = [Windows.Forms.MessageBox]::Show('A video is still being copied. Stop and close?', 'LeanTV Drop', 'YesNo', 'Warning')
        if ($r -ne 'Yes') { $e.Cancel = $true; return }
        try { $script:Push.P.Kill() } catch { }
        Sh "rm -f '$($script:Push.Remote)'" | Out-Null
    }
})

$script:Srv = Start-LeanServer -Ports (8765..8785) -Shared $Shared
$script:Port = $script:Srv.Port
try { [void]$form.ShowDialog() } finally { Stop-LeanServer $script:Srv }
