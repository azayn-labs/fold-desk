# Development

## Workflow

1. **Design first.** Any visible change gets a mockup before code: an HTML file
   in `design/` at the inner screen's size (875 × 971 dp), rendered to PNG:

   ```bash
   google-chrome --headless=new --hide-scrollbars --force-device-scale-factor=1.5 \
     --window-size=875,971 --screenshot="$PWD/design/out.png" "file://$PWD/design/mockup.html?s=desktop"
   ```

   Mockups use placeholder names and data only. Never commit captures from the
   real phone (wallpaper, notifications, app lists): `design/device/` and the
   wallpaper-based mockups are git-ignored for that reason.

2. Build and install:

   ```bash
   ./gradlew assembleGithubDebug
   adb -s <serial> install -r app/build/outputs/apk/github/debug/app-github-debug.apk
   ```

3. Check the taskbar service survived the install (see below), then test on the
   phone and take screenshots.

## Testing on the phone with adb

```bash
D="adb -s <serial>"
$D shell screencap -p /sdcard/s.png && $D pull /sdcard/s.png   # screenshot
$D shell input tap X Y                                          # tap (physical px)
$D shell input swipe X1 Y1 X2 Y2 300                            # swipe
$D shell input swipe X Y X Y 900                                # long press
$D shell 'input motionevent DOWN X Y; sleep 0.8; input motionevent MOVE X2 Y2; input motionevent UP X2 Y2'  # long-press drag
$D shell screenrecord --time-limit 3 /sdcard/r.mp4             # check animations
$D shell 'run-as dev.home.folddesk cat shared_prefs/desk.xml'   # saved state (debug builds)
```

The inner screen is 1968 × 2184 px at density 2.625 (1 dp ≈ 2.6 px).

Gotchas learned the hard way:

- **`uiautomator dump` suspends accessibility services** while it runs, so
  FoldDesk's taskbar service stops receiving events. Use `dumpsys` to wait or
  inspect instead.
- **After `adb install -r`** the service needs a few seconds to rebind, and
  sometimes One UI marks it as crashed:

  ```bash
  $D shell dumpsys accessibility | grep -E "label=FoldDesk taskbar|Crashed services"
  ```

  If it's listed under *Crashed services*, toggle it off/on on the phone
  (Settings → Accessibility → Installed apps). adb isn't allowed to change
  accessibility settings.
- Two-finger gestures (group drag, holding a window while switching desktops)
  can't be simulated with `input`; test those by hand.
- One UI's view IDs used by the service (Recents cards, window caption
  handle/minimise/close buttons) can change with One UI updates. If desktop
  switching or the Recents sync stops working after an update, check them with
  an accessibility dump of the relevant screen.

## Toolchain

- AGP 9.4.1, Gradle 9.8.2 (wrapper), Kotlin Compose plugin 2.4.21.
- `compileSdk 37`, `targetSdk 36`, `minSdk 34`.
- JDK 21 via Gradle toolchain (auto-provisioned by the foojay resolver).
- `local.properties` (SDK path) is per machine and not committed.
- Release builds are signed with the debug key (personal sideloaded app).
