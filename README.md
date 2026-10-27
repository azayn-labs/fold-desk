# FoldDesk

A Samsung DeX–style desktop launcher for the **inner screen of the Galaxy Z Fold7**
(One UI 9 / Android 17). On the cover screen it hands over to One UI Home, so the
folded phone behaves exactly as before.

It's a personal, sideloaded app: not on the Play Store, signed with the debug key.

## What it does

- **DeX taskbar** at the bottom, left or right: app drawer button, desktops
  button, pinned + open apps (drag to reorder), notification icons, Wi‑Fi,
  battery and clock. Back/Home/Recents keys appear only with 3‑button navigation.
- **Apps in floating windows** (One UI pop‑up view), cascaded and sized by setting.
- **App drawer** centred on screen with search, styled with One UI's panel
  material (blurred, like the notification panel).
- **Desktop** with draggable icons, multi‑select group drag (second finger),
  drag‑to‑remove, and **widgets** (add, move, resize, configure, remove).
- **Multiple desktops** (up to 4): each with its own icons, widgets and windows.
  Switch from the taskbar, by swiping, or by dragging an app onto a desktop.
- **Gestures** (each can be turned off): swipe up for the drawer, swipe down to
  close it, swipe left/right to switch desktops, swipe down for notifications.
- **Light and dark** themes following the phone.
- **Settings** window: taskbar position/alignment, tap shortcuts for the tray
  and clock, window size, gestures, cover‑screen behaviour, service status.

Mockups of every screen are in [`design/`](design/) (open the `.html` files in a
browser; the `.png` files are renders of them).

## Repository layout

```
app/src/main/java/dev/home/folddesk/   Kotlin sources (see docs/ARCHITECTURE.md)
app/src/main/res/                      themes, icon, accessibility-service config
design/                                HTML mockups + rendered PNGs
docs/ARCHITECTURE.md                   how it works, file by file
docs/DEVELOPMENT.md                    building, installing, testing on the phone
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

## License

Apache License 2.0, see [`LICENSE`](LICENSE).
