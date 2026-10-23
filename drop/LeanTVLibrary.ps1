<#
LeanTV Library: shares video folders on this computer with LeanTV Home on the TV (Videos tile), like a
very small Jellyfin. Runs in the system tray; right-click the icon to add or remove folders.

The TV reaches it through the adb connection (adb reverse tcp:8790), so nothing is opened on the network.
Folders are listed in library-folders.txt (one per line) next to the scripts.

Run via "LeanTV Library.bat" (or: powershell -STA -File drop\LeanTVLibrary.ps1 [-NoTray] [-Seconds N]).
-NoTray runs headless for -Seconds (used for testing).
#>
param([switch]$NoTray, [int]$Seconds = 0)

. "$PSScriptRoot\..\scripts\common.ps1"
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\Server.ps1"
. "$PSScriptRoot\Library.ps1"

$Port = 8790
$FoldersFile = Join-Path $Data 'library-folders.txt'
$StartupLink = Join-Path ([Environment]::GetFolderPath('Startup')) 'LeanTV Library.lnk'

$mutex = New-Object Threading.Mutex($false, 'Local\LeanTVLibrary')
if (-not $mutex.WaitOne(0)) { exit 0 }   # already running

function Get-Folders {
    if (-not (Test-Path $FoldersFile)) {
        @([Environment]::GetFolderPath('MyVideos'), (Join-Path $env:USERPROFILE 'Downloads')) |
            Set-Content -Encoding UTF8 $FoldersFile
    }
    @(Get-Content -Encoding UTF8 $FoldersFile | ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith('#') })
}

function Save-Folders([string[]]$list) {
    @($list | Select-Object -Unique) | Set-Content -Encoding UTF8 $FoldersFile
    $Shared.Folders = Get-Folders
    $Shared.ScanNow = $true
}

$Shared = [hashtable]::Synchronized(@{
    Files = [hashtable]::Synchronized(@{}); Json = $null; Folders = (Get-Folders)
    ScanNow = $true; Quit = $false; TvOk = $false; Count = 0; Shows = 0; Status = ''
})

$server = Start-LeanServer -Ports @($Port) -Shared $Shared
$worker = [powershell]::Create()
[void]$worker.AddScript($LibraryWorker.ToString()).AddArgument($Shared).AddArgument($Adb).AddArgument((Get-Device)).
    AddArgument($Port).AddArgument("function ConvertFrom-ReleaseName { ${function:ConvertFrom-ReleaseName} }")
$workerHandle = $worker.BeginInvoke()

function Stop-All {
    $Shared.Quit = $true
    try { [void]$worker.EndInvoke($workerHandle) } catch { }
    Stop-LeanServer $server
    $mutex.ReleaseMutex()
}

if ($NoTray) {
    try {
        $end = (Get-Date).AddSeconds([Math]::Max(1, $Seconds))
        while ((Get-Date) -lt $end) { Start-Sleep 1 }
        Write-Host "videos=$($Shared.Count) shows=$($Shared.Shows) tv=$($Shared.TvOk) $($Shared.Status)"
    } finally { Stop-All }
    return
}

# ---------------------------------------------------------------- tray

Add-Type -AssemblyName System.Windows.Forms, System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# Startup shortcut via IShellLinkW (WScript.Shell mangles non-ASCII paths such as a Hebrew Desktop folder).
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class LeanLink {
    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellLinkW {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder f, int c, IntPtr d, int fl);
        void GetIDList(out IntPtr p); void SetIDList(IntPtr p);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder n, int c);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string n);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder d, int c);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string d);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder a, int c);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string a);
        void GetHotkey(out short h); void SetHotkey(short h);
        void GetShowCmd(out int s); void SetShowCmd(int s);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder p, int c, out int i);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string p, int i);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string p, int r);
        void Resolve(IntPtr h, int f);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string f);
    }
    [ComImport, Guid("00021401-0000-0000-C000-000000000046")] class ShellLink { }
    public static void Create(string lnk, string target, string args, string dir, string icon, string desc) {
        var l = (IShellLinkW)new ShellLink();
        l.SetPath(target); l.SetArguments(args); l.SetWorkingDirectory(dir); l.SetDescription(desc);
        l.SetShowCmd(7);
        if (!string.IsNullOrEmpty(icon)) l.SetIconLocation(icon, 0);
        ((System.Runtime.InteropServices.ComTypes.IPersistFile)l).Save(lnk, true);
    }
}
'@

function Set-Autostart([bool]$on) {
    if ($on) {
        $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $args = "-NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File `"$PSCommandPath`""
        [LeanLink]::Create($StartupLink, $ps, $args, $Root, (Join-Path $PSScriptRoot 'drop.ico'), 'LeanTV Library: shares videos with the TV')
    } elseif (Test-Path -LiteralPath $StartupLink) {
        Remove-Item -LiteralPath $StartupLink -Force
    }
}

$tray = New-Object Windows.Forms.NotifyIcon
$icoPath = Join-Path $PSScriptRoot 'drop.ico'
$tray.Icon = if (Test-Path $icoPath) { New-Object Drawing.Icon($icoPath) } else { [Drawing.SystemIcons]::Application }
$tray.Text = 'LeanTV Library'
$menu = New-Object Windows.Forms.ContextMenuStrip

$statusItem = $menu.Items.Add('Starting...'); $statusItem.Enabled = $false
$tvItem = $menu.Items.Add(''); $tvItem.Enabled = $false
[void]$menu.Items.Add((New-Object Windows.Forms.ToolStripSeparator))

$foldersItem = New-Object Windows.Forms.ToolStripMenuItem('Shared folders')
[void]$menu.Items.Add($foldersItem)
function Update-FolderMenu {
    $foldersItem.DropDownItems.Clear()
    foreach ($f in @($Shared.Folders)) {
        $it = New-Object Windows.Forms.ToolStripMenuItem("Remove  $f")
        $it.Tag = $f
        $it.Add_Click({ param($s) Save-Folders @(Get-Folders | Where-Object { $_ -ne $s.Tag }); Update-FolderMenu })
        [void]$foldersItem.DropDownItems.Add($it)
    }
    if (-not @($Shared.Folders).Count) { $e = $foldersItem.DropDownItems.Add('(none)'); $e.Enabled = $false }
    [void]$foldersItem.DropDownItems.Add((New-Object Windows.Forms.ToolStripSeparator))
    $add = $foldersItem.DropDownItems.Add('Add a folder...')
    $add.Add_Click({
        $dlg = New-Object Windows.Forms.FolderBrowserDialog
        $dlg.Description = 'Choose a folder with videos to show on the TV'
        if ($dlg.ShowDialog() -eq 'OK') { Save-Folders (@(Get-Folders) + $dlg.SelectedPath); Update-FolderMenu }
    })
    $edit = $foldersItem.DropDownItems.Add('Edit the list...')
    $edit.Add_Click({ Start-Process notepad.exe -ArgumentList "`"$FoldersFile`"" })
}
Update-FolderMenu

$rescan = $menu.Items.Add('Rescan now')
$rescan.Add_Click({ $Shared.Folders = Get-Folders; $Shared.ScanNow = $true; Update-FolderMenu })

$auto = New-Object Windows.Forms.ToolStripMenuItem('Start with Windows')
$auto.CheckOnClick = $true
$auto.Checked = Test-Path -LiteralPath $StartupLink
$auto.Add_CheckedChanged({ Set-Autostart $auto.Checked })
[void]$menu.Items.Add($auto)

$drop = $menu.Items.Add('Open LeanTV Drop')
$drop.Add_Click({ Start-Process (Join-Path $Root 'LeanTV Drop.bat') })
[void]$menu.Items.Add((New-Object Windows.Forms.ToolStripSeparator))
$quit = $menu.Items.Add('Quit')
$quit.Add_Click({ $tray.Visible = $false; [Windows.Forms.Application]::Exit() })

$tray.ContextMenuStrip = $menu
$tray.Visible = $true

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 2000
$timer.Add_Tick({
    $shows = if ($Shared.Shows -eq 1) { '1 show' } else { "$($Shared.Shows) shows" }
    $statusItem.Text = if ($Shared.Status) { $Shared.Status } else { "$($Shared.Count) videos  ($shows)" }
    $tvItem.Text = if ($Shared.TvOk) { "$([char]0x25CF)  TV connected" } else { "$([char]0x25CB)  TV not reachable" }
    $t = "LeanTV Library - $($Shared.Count) videos - " + $(if ($Shared.TvOk) { 'TV connected' } else { 'TV not reachable' })
    $tray.Text = $t.Substring(0, [Math]::Min(63, $t.Length))
})
$timer.Start()

# First run: start with Windows by default so the TV always finds the library.
if (-not (Test-Path (Join-Path $Data '.library-autostart-asked'))) {
    Set-Autostart $true; $auto.Checked = $true
    New-Item -ItemType File -Force (Join-Path $Data '.library-autostart-asked') | Out-Null
    $tray.ShowBalloonTip(6000, 'LeanTV Library is running',
        'Your videos are on the TV under Videos. Right-click this icon to choose folders.', 'Info')
}

try { [Windows.Forms.Application]::Run() } finally { $timer.Stop(); $tray.Dispose(); Stop-All }
