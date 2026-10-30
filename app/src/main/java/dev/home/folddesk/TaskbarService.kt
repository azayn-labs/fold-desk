package dev.home.folddesk

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.MainScope
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import dev.home.folddesk.ui.Dex
import dev.home.folddesk.ui.DexTheme
import dev.home.folddesk.ui.DRAWER_CLOSE_MS
import dev.home.folddesk.ui.PopupLayer
import dev.home.folddesk.ui.TaskbarSurface
import dev.home.folddesk.ui.EdgeHandle
import androidx.compose.foundation.layout.PaddingValues

/**
 * Draws the taskbar as an accessibility overlay so it floats above app windows
 * without taking focus (which is what lets Back reach the app in front), and
 * provides the Back/Home/Recents/notification keys.
 */
class TaskbarService : AccessibilityService(), SystemKeys {
    private lateinit var wm: WindowManager
    private lateinit var shell: ShellController
    private val owner = OverlayOwner()
    private val handler = Handler(Looper.getMainLooper())
    private var bar: View? = null
    private var popup: View? = null
    private var navInset by mutableStateOf(0)

    private val refreshTick = object : Runnable {
        override fun run() {
            shell.desk.refreshOpenApps()
            handler.postDelayed(this, 2000)
        }
    }
    private val updateSoon = Runnable { updateVisibility() }

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        shell = ShellController(this, (application as FoldDeskApp).desk, keys = this)
        shell.onPopupsChanged = ::syncPopupWindow
        ShellController.register(shell)
        owner.start()
        addBar()
        running = true
        current = this
        wasInner = FoldDeskApp.isInnerScreen(resources.configuration)
        DesktopDrag.addListener(dragListener)
        registerReceiver(lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        lockReceiverRegistered = true
        getSystemService(DisplayManager::class.java).let { dm ->
            panel = dm.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.let { it.physicalWidth to it.physicalHeight }
            dm.registerDisplayListener(foldListener, handler)
        }
        updateVisibility()
        handler.post(refreshTick)
        scope.launch {
            // Side or size changes the bar window's shape: rebuild it.
            snapshotFlow { shell.desk.taskbarSide to shell.desk.taskbarSize }.drop(1).collect { rebuildBar() }
        }
        scope.launch {
            snapshotFlow { shell.desk.autoHideTaskbar to shell.desk.keepOverFullScreen }.drop(1).collect {
                revealed = false
                updateVisibility()
            }
        }
    }

    private val scope = MainScope()

    /** Lock and unlock don't always produce window events; re-check on each. */
    private var lockReceiverRegistered = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateVisibility()
            if (intent.action == Intent.ACTION_USER_PRESENT && returnAfterUnlock) {
                returnAfterUnlock = false
                // The screen is settled by now, so check soon; once more in case
                // the launcher wasn't in front yet. It does nothing once home.
                if (panelIsInner()) {
                    handler.postDelayed(::returnToDesktop, UNLOCK_SETTLE_MS)
                    handler.postDelayed(::returnToDesktop, UNFOLD_SETTLE_MS)
                }
            }
        }
    }

    /**
     * When a notification was tapped, and the app in front then. The app it
     * opens comes up full screen; [updateVisibility] turns it into a window.
     */
    private var notificationTap: Pair<Long, String?>? = null

    /** Package of the last focused app window; the shade takes focus while it's open. */
    private var lastFocused: String? = null

    /** Label of the window whose handle menu was opened last. */
    private var lastHandleLabel: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == SYSTEM_UI && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // A window's own close button: that app is closed, not just hidden.
            val source = event.source
            when (source?.viewIdResourceName) {
                CAPTION_HANDLE -> lastHandleLabel = source.contentDescription?.toString()?.removeSuffix(" Window handle")
                CLOSE_WINDOW -> lastHandleLabel?.let { shell.desk.dismissOpen(it) }
                NOTIFICATION_ROW -> if (shell.desk.notificationsInWindows) {
                    notificationTap = SystemClock.uptimeMillis() to lastFocused
                }
            }
        }
        if (event.packageName == Launcher.ONE_UI_HOME) {
            // On the inner screen One UI Home only shows up as Recents; the
            // drawer and menus are our overlays and would stay on top of it.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) shell.closePopups()
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                event.source?.viewIdResourceName == RECENTS_CLOSE_ALL
            ) {
                shell.desk.dismissAllOpen()
            } else if (!readRecentsQueued) {
                // Throttle, don't debounce: live thumbnails keep Recents changing.
                readRecentsQueued = true
                readRecentsRetries = 0
                handler.postDelayed(readRecentsTask, 150)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        // Check now, so the taskbar starts sliding away while a sheet or app is
        // still animating in; check again once the burst of events settles.
        updateVisibility()
        handler.removeCallbacks(updateSoon)
        handler.postDelayed(updateSoon, 120)
    }

    /**
     * Keeps the taskbar's open apps in line with One UI's Recents screen. Usage
     * stats never report tasks being removed, so this is the only signal.
     */
    private var readRecentsQueued = false
    private var readRecentsRetries = 0
    private val readRecentsTask = Runnable { readRecents() }

    private fun readRecents() {
        readRecentsQueued = false
        // Recents may still be animating in; find its window wherever it is.
        val root = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull {
                it.packageName == Launcher.ONE_UI_HOME &&
                    it.findAccessibilityNodeInfosByViewId(RECENTS_LIST).isNotEmpty()
            }
        if (root == null) {
            if (readRecentsRetries++ < 5) {
                readRecentsQueued = true
                handler.postDelayed(readRecentsTask, 200)
            }
            return
        }
        readRecentsRetries = 0
        if (root.findAccessibilityNodeInfosByViewId(RECENTS_EMPTY).isNotEmpty()) {
            shell.desk.dismissAllOpen()
            return
        }
        val cards = root.findAccessibilityNodeInfosByViewId(RECENTS_TASK)
        // Only trust the list when it isn't scrollable, i.e. every card is on screen.
        if (cards.isEmpty() || anyScrollable(root)) return
        shell.desk.syncWithRecents(cards.mapNotNull { it.contentDescription?.toString() }.toSet())
    }

    private fun anyScrollable(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (anyScrollable(child)) return true
        }
        return false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bar == null) return
        // Fold/unfold changes the screen; rebuild the bar for the new size.
        shell.closePopups()
        removeBar()
        addBar()
        updateVisibility()
        val inner = FoldDeskApp.isInnerScreen(newConfig)
        if (inner && !wasInner) handler.postDelayed(::returnToDesktop, UNFOLD_SETTLE_MS)
        if (!inner && wasInner) {
            foldedAt = SystemClock.uptimeMillis()
            returnAfterUnlock = false
        }
        wasInner = inner
    }

    private var wasInner = true

    /**
     * Folding or unfolding: drop the bar at once. The configuration change that
     * hides it for the cover screen comes a moment after the screens switch, and
     * until then the bar showed on top of the cover launcher. The built-in
     * display's panel size is what changes when the phone folds or unfolds.
     */
    private var panel: Pair<Int, Int>? = null
    private val foldListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val mode = getSystemService(DisplayManager::class.java).getDisplay(displayId)?.mode ?: return
            val size = mode.physicalWidth to mode.physicalHeight
            val before = panel
            panel = size
            if (before == null || before == size) return
            shell.closePopups()
            handler.removeCallbacks(revealBar)
            bar?.let { view ->
                view.animate().cancel()
                view.visibility = View.GONE
                barShown = false
            }
            handler.removeCallbacks(updateSoon)
            handler.postDelayed(updateSoon, FOLD_SETTLE_MS)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    /**
     * Whether the built-in display is the inner panel right now. Right after
     * folding, events still arrive with the unfolded configuration; the
     * panel's shape (near square inside, tall outside) is already right.
     */
    private fun panelIsInner(): Boolean {
        val mode = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return true
        val (w, h) = mode.physicalWidth to mode.physicalHeight
        return maxOf(w, h) < minOf(w, h) * 3 / 2
    }

    /**
     * Unfolded onto the cover launcher (One UI Home by default): press Home so
     * Android opens the default home app, FoldDesk. The service is always
     * running, so this works even when the app process was killed while
     * folded, and needs no background-start or usage permission.
     */
    /** Unfolded while locked; [returnToDesktop] runs again on unlock. */
    private var returnAfterUnlock = false

    private fun returnToDesktop() {
        if (!shell.desk.coverUsesOneUi) return
        // Unfolded while locked: the lock screen is in front, and the cover
        // launcher only shows once unlocked. Check again then.
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            returnAfterUnlock = true
            return
        }
        val front = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }?.root?.packageName?.toString()
        if (front == shell.desk.coverLauncher) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Folded with an app open in a pop-up window: the window comes along to the
     * cover screen, where it's just in the way. Launching the app again with
     * full-screen options turns its task back into a normal full-screen one.
     */
    /** Last app made full screen on the cover screen, and when; keeps it from looping. */
    private var coverFix = "" to 0L
    /** When the phone was last folded; a pop-up that came along gets made full screen. */
    private var foldedAt = 0L

    /**
     * Pop-up windows are for the inner screen. One that came along when the
     * phone was folded, or that an app reopened in when FoldDesk opened it on
     * the cover screen, is just in the way there, so press its Maximize
     * button, the same way as Minimize for desktop switching. Launching the
     * app again with full-screen options isn't enough on the cover screen;
     * Android keeps the window. Pop-ups made on purpose (e.g. dragged from the
     * Edge panel) stay as they are.
     */
    private fun fullScreenOnCover() {
        val handle = windows.mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId(CAPTION_HANDLE) }
            .firstOrNull() ?: return
        val label = handle.contentDescription?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        val justFolded = now - foldedAt < COVER_FIX_WINDOW_MS
        val justOpened = Launcher.lastCoverOpen?.let { (app, at) -> now - at < COVER_FIX_WINDOW_MS && label.startsWith(app) } == true
        if (!justFolded && !justOpened) return
        if (coverFix.first == label && now - coverFix.second < COVER_FIX_GAP_MS) return
        coverFix = label to now
        handle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ findNode(MAXIMIZE_WINDOW)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) }, 300)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running = false
        scope.cancel()
        if (current === this) current = null
        DesktopDrag.removeListener(dragListener)
        DesktopDrag.finish()
        handler.removeCallbacksAndMessages(null)
        if (lockReceiverRegistered) unregisterReceiver(lockReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(foldListener)
        if (::shell.isInitialized) {
            shell.closePopups()
            ShellController.unregister(shell)
        }
        removeBar()
        owner.stop()
        super.onDestroy()
    }

    // --- Windows ---

    /** Edge the current bar window is on. */
    private var barSide = TaskbarSide.Bottom
    private var statusInset = 0

    private fun addBar() {
        val insets = wm.currentWindowMetrics.windowInsets
        navInset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        statusInset = insets.getInsets(WindowInsets.Type.statusBars()).top
        val side = shell.desk.taskbarSide
        barSide = side
        // Auto hide: a touch anywhere outside the revealed bar hides it again.
        val view = OutsideTouchFrame(this) { onTouchOutsideBar() }
        view.addView(composeView {
            val density = LocalDensity.current
            val navPad = with(density) { navInset.toDp() }
            if (side.vertical) {
                // Full height, clear of the status bar and gesture handle.
                val topPad = with(density) { statusInset.toDp() }
                TaskbarSurface(shell, PaddingValues(top = topPad, bottom = navPad), Modifier.fillMaxHeight())
            } else {
                TaskbarSurface(shell, PaddingValues(bottom = navPad), Modifier.fillMaxWidth())
            }
        })
        owner.attach(view)
        val params = if (side.vertical) {
            overlayParams(barThickness(), WindowManager.LayoutParams.MATCH_PARENT, focusable = false).apply {
                gravity = if (side == TaskbarSide.Left) Gravity.LEFT else Gravity.RIGHT
            }
        } else {
            overlayParams(WindowManager.LayoutParams.MATCH_PARENT, barThickness(), focusable = false).apply {
                gravity = Gravity.BOTTOM
            }
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        wm.addView(view, params)
        bar = view
        barShown = true
    }

    // --- Auto hide ---

    /** The user brought the auto-hidden bar back with the handle. */
    private var revealed = false
    private var handleView: View? = null

    private fun onTouchOutsideBar() {
        if (revealed && shell.desk.autoHideTaskbar && popup == null) {
            revealed = false
            updateVisibility()
        }
    }

    private fun reveal() {
        revealed = true
        setHandleShown(false)
        handler.removeCallbacks(revealBar)
        animateBar(true)
    }

    /** A slim pill at the bar's edge, just clear of the gesture area. */
    private fun setHandleShown(show: Boolean) {
        if (show == (handleView != null)) return
        if (!show) {
            handleView?.let { wm.removeView(it) }
            handleView = null
            return
        }
        val density = resources.displayMetrics.density
        val side = barSide
        val view = composeView { EdgeHandle(side.vertical, ::reveal) }
        val long = (200 * density).toInt()
        val thick = (24 * density).toInt()
        val params = if (side.vertical) {
            overlayParams(thick, long, focusable = false).apply {
                gravity = (if (side == TaskbarSide.Left) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
            }
        } else {
            overlayParams(long, thick, focusable = false).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = navInset
            }
        }
        wm.addView(view, params)
        handleView = view
    }

    /** Height of a bottom bar (with the gesture area), or width of a side bar. */
    private fun barThickness(): Int {
        val density = resources.displayMetrics.density
        val size = shell.desk.taskbarSize
        return if (barSide.vertical) (size.sideBar * density).toInt()
        else (size.bar * density).toInt() + navInset
    }

    /** Taskbar moved to another edge: rebuild it there. */
    private fun rebuildBar() {
        if (bar == null) return
        shell.closePopups()
        removeBar()
        addBar()
        updateVisibility()
    }

    private fun removeBar() {
        handler.removeCallbacks(revealBar)
        setHandleShown(false)
        bar?.let {
            it.animate().cancel()
            wm.removeView(it)
        }
        bar = null
    }

    private val dragListener: () -> Unit = { syncPopupWindow() }

    /** The drawer/menu window covers everything above the taskbar while open. */
    private fun syncPopupWindow() {
        val wanted = shell.hasPopup || DesktopDrag.active
        handler.removeCallbacks(removePopup)
        if (wanted && popup == null) {
            val metrics = wm.currentWindowMetrics
            val top = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            val taskbar = barThickness()
            // Compose looks for its owners on the window's root view, i.e. this frame.
            val view = BackKeyFrame(this) { shell.closePopups() }.apply {
                owner.attach(this)
                addView(composeView { PopupLayer(shell) })
            }
            wm.addView(
                view,
                // Everything below the status bar except the taskbar's strip.
                if (barSide.vertical) {
                    overlayParams(metrics.bounds.width() - taskbar, metrics.bounds.height() - top, focusable = true).apply {
                        gravity = Gravity.TOP or Gravity.LEFT
                        x = if (barSide == TaskbarSide.Left) taskbar else 0
                        y = top
                        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    }
                } else {
                    overlayParams(WindowManager.LayoutParams.MATCH_PARENT, metrics.bounds.height() - top - taskbar, focusable = true)
                        .apply {
                            gravity = Gravity.TOP
                            y = top
                            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        }
                },
            )
            popup = view
        } else if (!wanted && popup != null) {
            // Let the drawer's close animation finish first.
            handler.postDelayed(removePopup, DRAWER_CLOSE_MS + 50L)
        }
    }

    private val removePopup = Runnable {
        if (!shell.hasPopup && !DesktopDrag.active) {
            popup?.let { wm.removeView(it) }
            popup = null
        }
    }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // A focusable window otherwise swallows every touch on screen, so the
            // taskbar under an open drawer would stop responding.
            (if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "FoldDesk"
    }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) =
        ComposeView(this).apply {
            owner.attach(this)
            setContent { DexTheme(content) }
        }

    /**
     * Shows the taskbar on the inner screen unless a full-screen app or the
     * keyboard is up; also tells the taskbar which app has focus.
     */
    private fun updateVisibility() {
        if (!FoldDeskApp.isInnerScreen(resources.configuration)) fullScreenOnCover()
        val view = bar ?: return
        val windows = try {
            windows
        } catch (e: Exception) {
            emptyList()
        }
        // A picture-in-picture video floats over everything; the app under it
        // decides whether the taskbar steps aside.
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
        val top = apps.maxByOrNull { it.layer }
        val topPackage = top?.root?.packageName?.toString()
        val focusedWindow = apps.firstOrNull { it.isFocused }
        val focused = focusedWindow?.root?.packageName?.toString()
        if (focused != null) lastFocused = focused
        shell.desk.focusedPackage = focused?.takeIf { it != packageName }
        focused?.let { pkg -> shell.desk.onAppFocused(pkg)?.let { shell.followAppToDesktop(it, pkg) } }

        val screen = wm.maximumWindowMetrics.bounds
        // Apps put small pop-ups (menus, tooltips) in windows of their own. When
        // the top one sits inside a bigger window of the same app, go by that.
        val bounds = Rect().also { top?.getBoundsInScreen(it) }
        apps.filter { it !== top && it.root?.packageName?.toString() == topPackage }.forEach { window ->
            val around = Rect().also { window.getBoundsInScreen(it) }
            if (around.contains(bounds)) bounds.set(around)
        }
        // Pop-up windows never span the screen's width; see Shell.open. Go by
        // focus: pop-up windows stay layered above a full-screen app.
        val split = windows.any { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
        val focusedBounds = Rect().also { focusedWindow?.getBoundsInScreen(it) }
        val focusedFullScreen = !split && focusedBounds.width() >= screen.width()
        if (focused != null && focused != packageName) {
            shell.desk.noteAppWindow(focused, fullScreen = split || focusedFullScreen)
        }
        // Step aside for anything in front that reaches into the taskbar's strip:
        // full-screen apps, bottom-sheet dialogs, windows dragged over it.
        val thickness = barThickness()
        val strip = when (barSide) {
            TaskbarSide.Bottom -> Rect(0, screen.height() - thickness, screen.width(), screen.height())
            TaskbarSide.Left -> Rect(0, 0, thickness, screen.height())
            TaskbarSide.Right -> Rect(screen.width() - thickness, 0, screen.width(), screen.height())
        }
        // "Keep over full-screen apps" only stays put for apps filling the screen;
        // dialogs and windows dragged over the strip still get it out of the way.
        val fillsScreen = bounds.width() >= screen.width() && bounds.height() >= screen.height() * 9 / 10
        val coversTaskbar = topPackage != null && topPackage != packageName && Rect.intersects(bounds, strip) &&
            !(shell.desk.keepOverFullScreen && fillsScreen)
        // The notification panel / Quick settings: a large System UI window. Our
        // overlays always stack above it, so get out of its way.
        val shade = windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .map { Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { it.height() > screen.height() / 3 }
        if (shade.isNotEmpty()) shell.closePopups()
        val shadeCoversTaskbar = shade.any { Rect.intersects(it, strip) }
        notificationTap?.let { (at, before) ->
            if (SystemClock.uptimeMillis() - at > NOTIFICATION_OPEN_MS) {
                notificationTap = null
            } else if (focused != null && focused != before && focused != packageName &&
                focused != Launcher.ONE_UI_HOME && focusedFullScreen &&
                FoldDeskApp.isInnerScreen(resources.configuration)
            ) {
                // Launching it again with window options turns its task into a window.
                notificationTap = null
                shell.desk.appForPackage(focused)?.let { Launcher.open(this, it, true, shell.desk.windowSizeFor(it)) }
            }
        }
        val keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val show = FoldDeskApp.isInnerScreen(resources.configuration) && panelIsInner() && !locked &&
            !coversTaskbar && !shadeCoversTaskbar && !keyboard
        // Auto hide: step aside for apps, leaving a handle. The desktop and
        // open popups always get the bar.
        val appInFront = topPackage != null && topPackage != packageName
        if (!appInFront) revealed = false
        val tucked = show && shell.desk.autoHideTaskbar && appInFront && !revealed && popup == null

        setBarShown(show && !tucked)
        setHandleShown(tucked)
        if (!show && popup == null) shell.closePopups()
    }

    // --- Show/hide animation ---

    private var barShown = true
    private val revealBar = Runnable { animateBar(true) }

    /**
     * Hides at once (so nothing gets covered) but only reveals once things
     * have settled, so window-event bursts during app animations don't make
     * the taskbar blink.
     */
    private fun setBarShown(show: Boolean) {
        handler.removeCallbacks(revealBar)
        if (show == barShown) return
        if (show) handler.postDelayed(revealBar, REVEAL_DELAY_MS) else animateBar(false)
    }

    private fun animateBar(show: Boolean) {
        val view = bar ?: return
        barShown = show
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.animate()
                .translationX(0f).translationY(0f).alpha(1f)
                .setDuration(180).setInterpolator(DecelerateInterpolator(2f))
                .start()
        } else {
            // Slides off its own edge.
            val out = when (barSide) {
                TaskbarSide.Bottom -> view.animate().translationY(view.height.toFloat())
                TaskbarSide.Left -> view.animate().translationX(-view.width.toFloat())
                TaskbarSide.Right -> view.animate().translationX(view.width.toFloat())
            }
            out.alpha(0f)
                .setDuration(120).setInterpolator(AccelerateInterpolator(1.5f))
                // GONE once out of sight, so touches reach whatever is underneath.
                .withEndAction { if (!barShown) view.visibility = View.GONE }
                .start()
        }
    }

    // --- SystemKeys ---

    override fun back() { performGlobalAction(GLOBAL_ACTION_BACK) }
    override fun home() { performGlobalAction(GLOBAL_ACTION_HOME) }
    override fun recents() {
        shell.closePopups()
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    override fun notifications() { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
    override fun quickSettings() { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
    override fun powerMenu() { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) }

    /**
     * One UI has no "minimise everything", so press each window's own Minimize:
     * tap its caption handle, then the Minimize button in the menu that opens.
     */
    override fun showDesktop() = minimizeNextWindow(MAX_WINDOWS_TO_MINIMIZE, skip = emptySet()) {}

    /**
     * Desktop switch: Home sends full-screen apps behind the desktop (pop-up
     * windows stay put on One UI), then each pop-up window is minimised, then
     * the target desktop's apps are reopened oldest first, so the most recent
     * ends up on top. Relaunching a minimised app restores its window as it was.
     */
    override fun switchWindows(restore: List<AppEntry>, keep: AppEntry?, done: () -> Unit) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            minimizeNextWindow(MAX_WINDOWS_TO_MINIMIZE, skip = setOfNotNull(keep?.label)) {
                restoreNext(restore + listOfNotNull(keep), 0, done)
            }
        }, 250)
    }

    private fun restoreNext(apps: List<AppEntry>, index: Int, done: () -> Unit) {
        if (index >= apps.size) {
            done()
            return
        }
        Launcher.bringToFront(this, apps[index])
        handler.postDelayed({ restoreNext(apps, index + 1, done) }, 250)
    }

    override fun openDrawer() {
        if (!shell.drawerOpen) shell.toggleDrawer()
    }

    override fun visibleApps(): List<String> = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedBy { it.layer }
        .mapNotNull { it.root?.packageName?.toString() }
        .filter { it != packageName && shell.desk.appForPackage(it) != null }
        .distinct()

    override fun minimizeApp(app: AppEntry) {
        // Each pop-up window's handle is labelled "<app> Window handle".
        val handle = windows.mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId(CAPTION_HANDLE) }
            .firstOrNull { it.contentDescription?.startsWith(app.label) == true }
            ?: return
        minimizeVia(handle) {}
    }

    /**
     * Minimises windows one at a time, leaving those labelled in [skip] alone.
     * A window that won't minimise (e.g. one being dragged) is skipped too, so
     * the windows after it still get minimised.
     */
    private fun minimizeNextWindow(remaining: Int, skip: Set<String>, done: () -> Unit) {
        val handle = windows.mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByViewId(CAPTION_HANDLE) }
            .firstOrNull { node -> skip.none { node.contentDescription?.startsWith(it) == true } }
        if (remaining <= 0 || handle == null) {
            done()
            return
        }
        val label = handle.contentDescription?.toString()?.removeSuffix(" Window handle")
        minimizeVia(handle) { minimized ->
            val next = if (minimized || label == null) skip else skip + label
            handler.postDelayed({ minimizeNextWindow(remaining - 1, next, done) }, if (minimized) 350 else 50)
        }
    }

    /** Opens a window's handle menu and presses Minimize; reports whether it could. */
    private fun minimizeVia(handle: AccessibilityNodeInfo, then: (Boolean) -> Unit) {
        handle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            val minimize = findNode(MINIMIZE_WINDOW)
            minimize?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            then(minimize != null)
        }, 300)
    }

    private fun findNode(viewId: String): AccessibilityNodeInfo? = windows
        .mapNotNull { it.root }
        .firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId(viewId).firstOrNull() }

    /** Reports touches outside the bar window (needs FLAG_WATCH_OUTSIDE_TOUCH). */
    @SuppressLint("ViewConstructor")
    private class OutsideTouchFrame(context: Context, private val onOutside: () -> Unit) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside()
                return false
            }
            return super.dispatchTouchEvent(event)
        }
    }

    /** Overlay windows get no OnBackPressedDispatcher; catch Back here instead. */
    @SuppressLint("ViewConstructor")
    private class BackKeyFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    companion object {
        private const val REVEAL_DELAY_MS = 80L
        private const val UNFOLD_SETTLE_MS = 400L
        private const val UNLOCK_SETTLE_MS = 100L
        private const val FOLD_SETTLE_MS = 300L
        private const val COVER_FIX_GAP_MS = 3_000L
        private const val COVER_FIX_WINDOW_MS = 4_000L
        private const val CAPTION_HANDLE = "com.android.systemui:id/caption_handle"
        private const val MINIMIZE_WINDOW = "com.android.systemui:id/minimize_window"
        private const val CLOSE_WINDOW = "com.android.systemui:id/close_window"
        private const val MAXIMIZE_WINDOW = "com.android.systemui:id/maximize_window"
        private const val NOTIFICATION_ROW = "com.android.systemui:id/expandableNotificationRow"
        /** How long after a notification tap its app may take to come up. */
        private const val NOTIFICATION_OPEN_MS = 4000L
        private const val SYSTEM_UI = "com.android.systemui"
        private const val MAX_WINDOWS_TO_MINIMIZE = 12
        private const val RECENTS_TASK = "${Launcher.ONE_UI_HOME}:id/taskView"
        private const val RECENTS_LIST = "${Launcher.ONE_UI_HOME}:id/taskListContainer"
        private const val RECENTS_EMPTY = "${Launcher.ONE_UI_HOME}:id/empty_text_view"
        private const val RECENTS_CLOSE_ALL = "${Launcher.ONE_UI_HOME}:id/clear_all"

        /** The running service, for the home screen's desktop switching. */
        var current: TaskbarService? = null
            private set

        /** Read by the home screen to decide whether to draw its own taskbar. */
        var running by mutableStateOf(false)
            private set
    }
}
