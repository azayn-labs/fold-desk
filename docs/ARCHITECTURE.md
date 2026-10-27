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
| `ui/Desktop.kt` | Home screen: icon grid, group drag, drag-to-remove, desktop switch animation, widgets on the grid, resize frame, widget picker layer. |
| `ui/WidgetPicker.kt` | Widget picker panel. |
| `ui/DesktopGrid.kt` | Last laid-out grid geometry, for code outside Compose. |
| `ui/Gestures.kt` | `deskGestures`: tap, long-press (haptic + instant menu), right-click, drag-after-long-press, swipes. Used for nearly every touch target. |
| `ui/Blur.kt` | `Panel`: One UI panel material via Samsung's `SemBlurInfo`. |

## How the tricky parts work

### Floating windows
`ActivityOptions.setLaunchWindowingMode(5 /* FREEFORM */)` is a hidden API,
called through `org.lsposed.hiddenapibypass`. The Fold7 declares
`android.software.freeform_window_management`, so apps open as One UI pop-up
windows.

### Why an accessibility service
- Its `TYPE_ACCESSIBILITY_OVERLAY` windows sit above app windows without taking
  focus, so Back still reaches the app.
- It can read the window list, to hide the taskbar for full-screen apps, bottom
  sheets, the keyboard, the notification shade and the lock screen.
- It can press buttons in other windows. Minimising = click the window's
  `com.android.systemui:id/caption_handle`, then `…:id/minimize_window`.
  Closing via `…:id/close_window` is detected the same way.

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
| `desktop_<id>` (`desktop` for legacy desktop 1) | Icons: `key\|col\|row`. |
| `widgets_<id>` | Widgets: `appWidgetId\|col\|row\|w\|h`. |
| `appDesktop` | Open app package → desktop id. |
| `windows_<id>` | Windows visible when that desktop was left, bottom→top. |
| `dismissed` | Apps removed from the taskbar's open section, with time. |
| settings | `openInWindow`, `coverUsesOneUi`, `taskbarSide`, `taskbarAlignment`, `statusAction`, `clockAction`, `windowSize`, `swipeUpForDrawer`, `swipeDownClosesDrawer`, `swipeSwitchesDesktops`, `swipeDownForNotifications`. |

`allowBackup` is off, so a fresh install starts clean; `adb install -r` keeps
everything.
