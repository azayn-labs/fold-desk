# Architecture

FoldDesk is one Android app (`dev.home.folddesk`), written in Kotlin with
Jetpack Compose. It runs as two cooperating parts:

| Part | Class | Role |
|---|---|---|
| Home screen | `MainActivity` | The default HOME activity: wallpaper, desktop icons, widgets. Hands the cover screen to One UI Home. |
| Taskbar overlay | `TaskbarService` | An **accessibility service** that draws the taskbar, drawer, menus and desktop switcher as overlay windows *above* app windows, and drives other apps' windows (minimise/restore). |

Both share one process-wide state object, `DeskState`, owned by `FoldDeskApp`.
If the service isn't running, the home screen draws its own fallback taskbar,
which can only sit behind floating windows.

## Source map

`app/src/main/java/dev/home/folddesk/`

| File | What's in it |
|---|---|
| `FoldDeskApp.kt` | Application: creates `DeskState`, inits the widget host, brings the desktop back when the phone is unfolded onto One UI Home. |
| `DeskState.kt` | All persistent and live state (Compose `mutableStateOf`): installed apps, pins, desktop icons, desktops, app→desktop map, window snapshots, widgets, settings, battery/network/notifications, the open-apps list. |
| `Options.kt` | Setting enums (`Placement`, `TaskbarSide`, `StatusAction`, `ClockAction`, `WindowSize`). |
| `Shell.kt` | `ShellController`: popup state (drawer, switcher, menus, widget picker) and every user action (open app, menus, switch/move desktops, shortcuts). One per surface (overlay, home). `SystemKeys` is the interface the service implements. |
| `Launcher.kt` | Starting apps fullscreen or in freeform windows, window cascade/size, system screens, status-bar panels. |
| `TaskbarService.kt` | Overlay windows (bar, popup layer), show/hide rules and animation, Recents sync, window switching/minimising, lock and notification-shade handling, moving the bar to another edge. |
| `DesktopDrag.kt` | Shared state for dragging an app onto a desktop card (screen coordinates, hover, hold-to-go). |
| `Widgets.kt` | `AppWidgetHost` wrapper, widget size maths, `DeskWidgetView` (long-press → menu/drag without breaking the widget's own touches). |
| `NotificationWatcher.kt` | NotificationListenerService feeding the tray's notification icons. |
| `OverlayOwner.kt` | Lifecycle/SavedState owner so Compose works in service-owned windows. |
| `SettingsActivity.kt` | FoldDesk settings screen (One UI style). |
| `ui/Theme.kt` | `DexColors` (dark and light palettes), `DexTheme`, sizes, line-drawn glyphs. |
| `ui/Taskbar.kt` | The taskbar (horizontal or vertical), tray, clock, icon reordering. |
| `ui/Popups.kt` | Popup layer: app drawer, context menus (with submenus), app tiles. |
| `ui/Desktops.kt` | Desktop switcher strip and cards, drag ghost. |
| `ui/Desktop.kt` | Home screen: icon grid, group drag, drop-to-make-a-folder, drag-to-remove, desktop switch animation, widgets on the grid, resize frame, widget picker layer. |
| `ui/Folders.kt` | Folders: the glass icon, small and large desktop tiles, the open-folder panel (rename, drag an app out), the merge-target highlight. |
| `ui/WidgetPicker.kt` | Widget picker panel. |
| `ui/DesktopGrid.kt` | Last laid-out grid geometry, for code outside Compose. |
| `ui/Gestures.kt` | `deskGestures`: tap, long-press (haptic + instant menu), right-click, drag-after-long-press, swipes. Used for nearly every touch target. |
| `ui/Blur.kt` | `Panel`: One UI panel material via Samsung's `SemBlurInfo`. |

## How the tricky parts work

### Floating windows
Apps open with `ActivityOptions.setLaunchBounds`. The Fold7 declares
`android.software.freeform_window_management`, and One UI turns a launch with
bounds into a pop-up window on its own. `setLaunchWindowingMode` is a hidden
API; `HiddenApi` tries it by plain reflection and carries on when Android
blocks it. (A hidden-API bypass library used to lift the block, but Google Play
rejects apps that ship one.)

### Why an accessibility service
- Its `TYPE_ACCESSIBILITY_OVERLAY` windows sit above app windows without taking
  focus, so Back still reaches the app.
- It can read the window list, to hide the taskbar for full-screen apps, bottom
  sheets, the keyboard, the notification shade and the lock screen.
- It can press buttons in other windows. Minimising = click the window's
  `com.android.systemui:id/caption_handle`, then `…:id/minimize_window`.
  Closing via `…:id/close_window` is detected the same way.
- One UI keeps pop-up windows above a full-screen app. When an app comes to the
  front full screen on the inner screen, the service minimises the pop-ups once
  (`putPopupsAway`), the way maximising a window does on DeX. Pop-ups opened
  afterwards stay where they are.
- Stage mode (off by default) builds on the same pieces. `Shell.open` first
  minimises the focused window, while its Minimise button is still reachable
  (an unfocused window's often isn't), then launches the next app at a fixed
  centre-stage size (`Launcher.stageBounds`). `TaskbarService.tidyStage` is the
  backup for windows opened some other way. The strip is a small accessibility
  overlay at the left edge (`ui/Stage.kt`); its cards come from `DeskState.stageApps`,
  not from usage events, so it works without usage access.

### Open apps on the taskbar
Usage stats report resumed/paused but **never** closed. The list is built from
usage events (last 4 h) and trimmed by reading One UI's **Recents** screen
(`com.sec.android.app.launcher:id/taskView` cards, `clear_all`,
`empty_text_view`) whenever it's shown.

### Multiple desktops
Android has no virtual desktops, so they're emulated:
- `appDesktop` maps package → desktop; `desktop_<id>` and `widgets_<id>` hold
  each desktop's icons and widgets.
- Switching: record which windows are visible (`windows_<id>`, bottom→top),
  press Home, minimise every remaining window, then relaunch the target
  desktop's recorded apps oldest-first (relaunching a minimised app restores its
  window). Windows that couldn't be minimised (e.g. held by a finger) come along
  to the new desktop.
- An app raised from outside (a notification, Samsung's minimised-apps bubble)
  that belongs to another desktop makes FoldDesk follow it there.

### Panel blur
Android's cross-window blur is disabled for third-party apps on Samsung. One UI's
own `android.view.SemBlurInfo` (`BLUR_MODE_WINDOW`) works and is what its panels
use; it's applied via reflection to a backing `View` behind each panel. Blur
radius and tint are animated together with the drawer, because the system draws
the blur separately from Compose.

### Folders
A folder is an icon-map entry under `folder:<id>` whose apps live in the `folders`
pref, so it moves, swaps and is removed like any icon (and travels with a deleted
desktop's icons). While an app is dragged, the icon whose middle it is over
(`overMiddle`, within about a third of a cell; anywhere on a large folder) is the
merge target; dropping makes a folder or adds to one, while dropping near the
edge swaps as usual. A large folder is a 2×2 block: `desktopLayout` places those
first, like widgets, and the other icons flow around them (it returns a
`DesktopLayout`, a `Map` that also knows which folders got their block). Without
a free 2×2 it shows small. A folder with one app left becomes that app again.
The open panel is a popup (`ShellController.openFolderId`); dragging an app off it
drops the app at the grid cell under the finger (`DesktopGrid`).

### Widgets
Standard `AppWidgetHost`. Binding needs `ACTION_APPWIDGET_BIND` (the "Allow
access to widget content?" dialog) because FoldDesk isn't a system app.
Widgets occupy grid cells; icons are laid out around them.

## Persistent state

All in the SharedPreferences file `desk`, managed by `DeskState`:

| Key | Meaning |
|---|---|
| `pinned` | Taskbar pins in order (`component#userSerial`, newline-separated). |
| `desktops`, `currentDesktop` | Desktop list (`id\|name`) and the current one. |
| `desktop_<id>` (`desktop` for legacy desktop 1) | Icons: `key\|col\|row`. A folder's key is `folder:<id>`. |
| `folders` | Folders on any desktop: `id\|large (0 or 1)\|app keys, comma separated\|name`. |
| `widgets_<id>` | Widgets: `appWidgetId\|col\|row\|w\|h`. |
| `appDesktop` | Open app package → desktop id. |
| `windows_<id>` | Windows visible when that desktop was left, bottom→top. |
| `dismissed` | Apps removed from the taskbar's open section, with time. |
| settings | `openInWindow`, `coverUsesOneUi`, `taskbarSide`, `taskbarAlignment`, `statusAction`, `clockAction`, `windowSize` (plus per app `windowSize_<app key>`), `swipeUp`, `swipeDown` (a `SwipeAction`; migrated from the old `swipeUpForDrawer` / `swipeDownForNotifications` switches), `swipeDownClosesDrawer`, `swipeSwitchesDesktops`. |

`allowBackup` is off, so a fresh install starts clean; `adb install -r` keeps
everything.
