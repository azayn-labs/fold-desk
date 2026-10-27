# FoldDesk

A Samsung DeX–style desktop launcher for the **inner screen of the Galaxy Z Fold7**
(One UI 9 / Android 17). On the cover screen it hands over to One UI Home, so the
folded phone behaves exactly as before.

It's a sideloaded, open-source app (not on the Play Store). Signed APKs are
published on the [Releases page](../../releases).

## Compatibility

- **Built and tested on:** Galaxy Z Fold7, One UI 9 (Android 17).
- **Needs:** Android 14+ (minSdk 34) and a device with freeform window support.
- **Samsung-specific:** the panel blur, Recents sync and window minimising rely
  on One UI internals. Other Samsung foldables/tablets on a recent One UI will
  likely work; other brands will run with reduced features. Reports welcome
  via Issues.

## What it does

- **DeX taskbar** at the bottom, left or right: app drawer button, desktops
  button, pinned + open apps (drag to reorder), notification icons, Wi‑Fi,
  battery and clock. Back/Home/Recents keys appear only with 3‑button navigation.
- **Apps in floating windows** (One UI pop‑up view), cascaded and sized by
  setting, with an optional size per app (long press → Window size).
- **App drawer** centred on screen with search, styled with One UI's panel
  material (blurred, like the notification panel).
- **Desktop** with draggable icons, multi‑select group drag (second finger),
  drag‑to‑remove, and **widgets** (add, move, resize, configure, remove).
- **Multiple desktops** (up to 4): each with its own icons, widgets and windows.
  Switch from the taskbar, by swiping, or by dragging an app onto a desktop.
- **Gestures**: choose what swiping up or down on the desktop does (app drawer,
  notifications, quick settings or nothing); swipe left/right to switch
  desktops and swipe down to close the drawer can each be turned off.
- **Light and dark** themes following the phone.
- **Settings** window: taskbar position/alignment, tap shortcuts for the tray
  and clock, window size, gestures, cover‑screen behaviour, service status.

Mockups of every screen are in [`design/`](design/) (open the `.html` files in a
browser; the `.png` files are renders of them).

## Install from a release (no computer needed)

1. Download `FoldDesk-vX.Y.Z.apk` from the [latest release](../../releases/latest)
   on the phone and open it. Allow your browser/files app to install unknown apps
   when asked.
2. **Make it the home app:** Settings → Apps → Default apps → Home app → FoldDesk.
3. **Turn on the taskbar:** Settings → Accessibility → Installed apps →
   FoldDesk taskbar → On. If it's greyed out ("Restricted setting"): Settings →
   Apps → FoldDesk → ⋮ → Allow restricted settings, then try again.
4. **Open apps on the taskbar:** Settings → Apps → ⋮ → Special access → Usage
   data access → FoldDesk → On.
5. **Notification icons in the tray:** Settings → Notifications → Advanced
   settings → Notification access → FoldDesk → On.
6. If apps don't open in floating windows: Developer options → turn on
   **Force activities to be resizable** (and **Enable freeform windows** if your
   phone shows it).

Updates install over the previous version (same signing key); your desktops,
icons and settings are kept.

## Repository layout

```
app/src/main/java/dev/home/folddesk/   Kotlin sources (see docs/ARCHITECTURE.md)
app/src/main/res/                      themes, icon, accessibility-service config
design/                                HTML mockups + rendered PNGs
docs/ARCHITECTURE.md                   how it works, file by file
docs/DEVELOPMENT.md                    building, installing, testing on the phone
docs/RELEASING.md                      publishing a new release
.github/workflows/                     CI build + tag-triggered signed releases
CLAUDE.md                              notes for AI-assisted sessions
```

## Building

Requirements:

- **Android SDK** with platform **android-37** and build-tools 36
  (Android Studio installs these, or `sdkmanager "platforms;android-37"`).
- **Java:** nothing to install by hand. The build uses a JDK 21 toolchain;
  Gradle uses one already on the machine or downloads it (foojay resolver).
  Gradle itself just needs any Java 17+ runtime to start.

```bash
# Tell Gradle where the SDK is (this file is not committed):
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Installing on the phone

1. On the phone: **Settings → About phone → Software information**, tap
   *Build number* 7× to enable Developer options, then turn on **USB debugging**
   (or **Wireless debugging**).
2. Connect and install:

   ```bash
   adb devices                       # note the Fold's serial
   adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Grant the permissions FoldDesk needs (adb can do these):

   ```bash
   P=dev.home.folddesk
   adb -s <serial> shell appops set $P GET_USAGE_STATS allow        # open-apps on the taskbar
   adb -s <serial> shell appops set $P SYSTEM_ALERT_WINDOW allow    # return to desktop on unfold
   adb -s <serial> shell cmd notification allow_listener $P/$P.NotificationWatcher   # tray icons
   adb -s <serial> shell cmd role add-role-holder android.app.role.HOME $P 0        # default home
   ```

4. **On the phone**, turn on the taskbar service (adb isn't allowed to):
   **Settings → Accessibility → Installed apps → FoldDesk taskbar → On**.
   If the switch is greyed out ("Restricted setting"): **Settings → Apps →
   FoldDesk → ⋮ → Allow restricted settings**, then try again.

Widgets: the first time you add a widget from an app, Android asks "Allow access
to widget content?" once per app.

To go back to Samsung's launcher: **Settings → Apps → Default apps → Home app →
One UI Home**.

## Known limitations

These come from Android/One UI, not bugs to fix:

- Apps can't truly minimise or move other apps' windows, so desktop switching and
  "Show desktop" press each window's own Minimize button through the
  accessibility service. Windows fold away one by one; they can't be animated.
- Samsung's minimised-apps bubble lists minimised windows from **all** desktops.
  Tapping one that belongs to another desktop switches to that desktop.
- An app has one window, so it lives on one desktop at a time.
- After installing a new build, One UI sometimes marks the taskbar service as
  crashed. Symptom: drawer opens *behind* windows, no Power button in the drawer.
  Fix: toggle **FoldDesk taskbar** off and on in Accessibility settings.

See [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) for working on the code.

## Support

FoldDesk is free and always will be. If it makes your Fold nicer to use and
you'd like to say thanks, you can [buy me a coffee on Ko-fi](https://ko-fi.com/adarsmohan76).
A star on GitHub helps too.

## License

Apache License 2.0, see [`LICENSE`](LICENSE). © 2026 [azayn](https://azayn.com).
