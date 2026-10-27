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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
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
import dev.home.folddesk.ui.Taskbar

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
        DesktopDrag.addListener(dragListener)
        registerReceiver(lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        lockReceiverRegistered = true
        updateVisibility()
        handler.post(refreshTick)
        scope.launch {
            snapshotFlow { shell.desk.taskbarSide }.drop(1).collect { rebuildBar() }
        }
    }

    private val scope = MainScope()

    /** Lock and unlock don't always produce window events; re-check on each. */
    private var lockReceiverRegistered = false
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateVisibility()
    }

    /** Label of the window whose handle menu was opened last. */
    private var lastHandleLabel: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == SYSTEM_UI && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // A window's own close button: that app is closed, not just hidden.
            val source = event.source
            when (source?.viewIdResourceName) {
                CAPTION_HANDLE -> lastHandleLabel = source.contentDescription?.toString()?.removeSuffix(" Window handle")
                CLOSE_WINDOW -> lastHandleLabel?.let { shell.desk.dismissOpen(it) }
            }
        }
        if (event.packageName == Launcher.ONE_UI_HOME) {
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
        val view = composeView {
            val density = LocalDensity.current
            val navPad = with(density) { navInset.toDp() }
            if (side.vertical) {
                // Full height, clear of the status bar and gesture handle.
                val topPad = with(density) { statusInset.toDp() }
                Box(Modifier.fillMaxHeight().background(Dex.bar).padding(top = topPad, bottom = navPad)) { Taskbar(shell) }
            } else {
                Box(Modifier.fillMaxWidth().background(Dex.bar).padding(bottom = navPad)) { Taskbar(shell) }
            }
        }
        val params = if (side.vertical) {
            overlayParams(barThickness(), WindowManager.LayoutParams.MATCH_PARENT, focusable = false).apply {
                gravity = if (side == TaskbarSide.Left) Gravity.LEFT else Gravity.RIGHT
            }
        } else {
            overlayParams(WindowManager.LayoutParams.MATCH_PARENT, barThickness(), focusable = false).apply {
                gravity = Gravity.BOTTOM
            }
        }
        wm.addView(view, params)
        bar = view
        barShown = true
    }

    /** Height of a bottom bar (with the gesture area), or width of a side bar. */
    private fun barThickness(): Int {
        val density = resources.displayMetrics.density
        return if (barSide.vertical) (Dex.taskbarWidth.value * density).toInt()
        else (Dex.taskbarHeight.value * density).toInt() + navInset
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
        val view = bar ?: return
        val windows = try {
            windows
        } catch (e: Exception) {
            emptyList()
        }
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val top = apps.maxByOrNull { it.layer }
        val topPackage = top?.root?.packageName?.toString()
        val focused = apps.firstOrNull { it.isFocused }?.root?.packageName?.toString()
        shell.desk.focusedPackage = focused?.takeIf { it != packageName }
        focused?.let { pkg -> shell.desk.onAppFocused(pkg)?.let { shell.followAppToDesktop(it, pkg) } }

        val screen = wm.maximumWindowMetrics.bounds
        val bounds = Rect().also { top?.getBoundsInScreen(it) }
        // Step aside for anything in front that reaches into the taskbar's strip:
        // full-screen apps, bottom-sheet dialogs, windows dragged over it.
        val thickness = barThickness()
        val strip = when (barSide) {
            TaskbarSide.Bottom -> Rect(0, screen.height() - thickness, screen.width(), screen.height())
            TaskbarSide.Left -> Rect(0, 0, thickness, screen.height())
            TaskbarSide.Right -> Rect(screen.width() - thickness, 0, screen.width(), screen.height())
        }
        val coversTaskbar = topPackage != null && topPackage != packageName && Rect.intersects(bounds, strip)
        // The notification panel / Quick settings: a large System UI window. Our
        // overlays always stack above it, so get out of its way.
        val shade = windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .map { Rect().also { r -> it.getBoundsInScreen(r) } }
            .filter { it.height() > screen.height() / 3 }
        if (shade.isNotEmpty()) shell.closePopups()
        val shadeCoversTaskbar = shade.any { Rect.intersects(it, strip) }
        val keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val show = FoldDeskApp.isInnerScreen(resources.configuration) && !locked &&
            !coversTaskbar && !shadeCoversTaskbar && !keyboard

        setBarShown(show)
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
    override fun recents() { performGlobalAction(GLOBAL_ACTION_RECENTS) }
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
        private const val CAPTION_HANDLE = "com.android.systemui:id/caption_handle"
        private const val MINIMIZE_WINDOW = "com.android.systemui:id/minimize_window"
        private const val CLOSE_WINDOW = "com.android.systemui:id/close_window"
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
