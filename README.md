# LeanTV

Debloat an Android TV box over Wi-Fi ADB, reversibly, and replace its ad-filled home screen with
**LeanTV Home**: a minimal Apple TV-style launcher with a dock of favourite apps on top of rotating
nature photos.

Target found on this network: **SEI Robotics** Android TV box ("yes"), `192.168.0.52`.

## 0. Enable ADB on the TV (once, needs the remote)

1. Settings → Device Preferences → About → press OK on **Build** 7 times ("You are now a developer").
2. Settings → Device Preferences → **Developer options** → **USB debugging** = on.
   If there is a "Network debugging" / "ADB over network" switch, turn it on too.
3. Android 11+ boxes may only offer **Wireless debugging**. Open it, choose *Pair device with pairing
   code*, and use `connect.ps1 -Pair <ip:port> -Code <code> -Ip <ip:port>` (both ports are on that screen).
4. When the TV asks *Allow USB debugging?*, tick **Always allow from this computer** and press OK.

## 1. Run (PowerShell, from this folder)

```powershell
.\scripts\connect.ps1            # find the TV, connect, wait for the on-screen approval
.\scripts\backup.ps1 -PullApks   # FIRST backup = restore baseline (packages, settings, home, screenshot)
.\scripts\audit.ps1              # classify every package -> debloat\report.md + debloat\plan.txt
#   edit debloat\plan.txt: remove the "#" in front of anything else you want disabled
.\scripts\debloat.ps1 -WhatIf    # dry run
.\scripts\debloat.ps1            # apply; screenshots + health check after every batch
.\build.ps1                      # (only if you changed the launcher) -> dist\LeanTVHome.apk
.\scripts\install-launcher.ps1 -Dock "com.netflix.ninja,com.google.android.youtube.tv,@settings"
```

Screenshots of every step go to `shots\`. Every change is appended to `backups\changes.log`.

## 2. Undo

```powershell
.\scripts\restore.ps1 -WhatIf                        # what would be undone
.\scripts\restore.ps1                                # everything back to the baseline
.\scripts\restore.ps1 -Only com.google.android.katniss   # re-enable one package
```
Or double-click **restore.bat**. Restore compares the TV with the *baseline snapshot*: it
re-enables everything disabled since then, puts back logged settings, makes the original launcher
Home again, and uninstalls LeanTV Home (`-KeepLauncher` keeps it). Nothing is ever uninstalled by the
debloat, so a factory reset is never needed.

## Debloat rules

* `pm disable-user` only. Never `pm uninstall`. The yes+ firmware blocks `disable-user`
  ("This command is illegal!"), so the script falls back to `pm suspend` plus a background-run ban
  (`appops RUN_ANY_IN_BACKGROUND ignore`) and a force-stop. Both are logged and undone by restore.
* `debloat\catalog.csv` marks packages **disable** (ads, telemetry, junk), **ask** (apps/features you
  might use: casting, Assistant, YouTube...), or **keep**. Only *disable* entries are switched on
  in the generated plan. Manufacturer/operator packages not in the catalog are listed for review,
  commented out.
* A hard-coded protected list (`scripts\common.ps1`) can never be disabled: system UI, Settings,
  Play services/Store, input, Wi-Fi/Bluetooth, TV providers, setup wizard...
* The stock launcher is **not** disabled by default. LeanTV Home is set as Home and the stock one stays
  as a fallback. Disable it (plan, "launcher" lines) only once LeanTV Home has proven itself.

## LeanTV Home

Plain Java, no libraries, about 35 KB of code (the 9 MB APK is mostly the 16 bundled photos).

* Dock: rounded 5:3 tiles that zoom and lift on focus, with the name shown under the focused tile.
  Uses each app's TV banner when it has one, otherwise its icon on a matching dark tint.
* **Long-press OK** (or Menu) on a dock tile: move left / move right / remove / app info.
* **All Apps** tile: grid of every app. Long-press any of them to add it to the dock.
* Wallpapers crossfade every 60 s with a slow zoom. The credit is shown top-left. Add your own
  photos: `install-launcher.ps1 -Wallpapers C:\path\to\photos`, or push them to
  `/sdcard/Android/data/com.leantv.home/files/wallpapers/`.
* Inter typeface (SIL OFL, `launcherssetsonts`), frosted-glass dock (a blurred copy of the photo,
  computed once per photo on a tiny bitmap, so it costs nothing per frame).
* **Videos** tile appears when videos have been copied to the TV; the built-in player handles OK = play/pause,
  left/right = 10 s (30 s held), back = close.
* Hebrew UI strings included. The dock keeps your order left-to-right in any language.
* Configure over adb:
  ```
  adb shell am start -n com.leantv.home/.HomeActivity --es dock "pkg1,pkg2,@settings"
  adb shell am start -n com.leantv.home/.HomeActivity --ei interval 90
  adb shell am start -n com.leantv.home/.HomeActivity --ez reset_dock true
  ```

### Home button

The yes+ firmware gives its own launcher top priority and blocks `pm disable-user`, component disable and
`pm hide`, so `set-home-activity` alone doesn't take. Suspending the stock launcher doesn't help either:
Home then lands on Android's "app paused" screen. Instead LeanTV Home has a tiny accessibility service
(`HomeKeyService`) that watches only the stock launcher's window. Whenever that launcher comes to the
front (Home button, boot, an app closing) the service replaces it with LeanTV Home, after a brief flash.
If LeanTV Home crashes repeatedly, the service backs off and leaves the stock launcher usable.

```
.\scripts\home-button.ps1          # on  (logged in backups\changes.log)
.\scripts\home-button.ps1 -Off     # off: Home goes to the stock launcher again
```
`restore.bat` switches it off as well. It also appears in Settings > Accessibility as "LeanTV Home button".

Build needs the Android SDK (build-tools + platform, already in `%LOCALAPPDATA%\Android\Sdk`) and a
JDK 11+. No Gradle. The signing key is generated in `keys\` on first build. Keep it, because updates must
be signed with the same key.

Testing without the TV: `.\scripts\*.ps1` honour `LEANTV_DEVICE` (e.g. `emulator-5580`) and
`LEANTV_DATA` (separate folder for backups/shots/plan, so the real TV's baseline is never touched).
A 1080p test AVD `leantv_test` exists.

## LeanTV Drop (play videos from this computer)

Double-click **LeanTV Drop** on the Desktop (or `LeanTV Drop.bat` here), then drop video files on the
window, or drop them straight onto the shortcut. mp4 / mkv / mov / webm / m4v / ts.

* **Stream (default):** the file stays on this PC and is served to the TV through the adb connection
  (`adb reverse`, the server only listens on 127.0.0.1, so nothing is exposed on the network). Instant start,
  no TV storage used. Keep the window open while watching.
* **Copy to the TV:** copies into LeanTV Home's own folder (`/sdcard/Android/data/com.leantv.home/files/videos`),
  then plays; it stays under *Videos* on the home screen (long-press to delete). The TV has only ~1.3 GB
  free, so this is for clips; the app refuses files that don't fit.

## Photo credits

Wikimedia Commons, licences CC0 / public domain / CC BY / CC BY-SA. Per-photo author, licence and
source link are in `launcher\assets\wallpapers\credits.json` and on screen.
