package dev.home.folddesk

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import dev.home.folddesk.ui.CoverScreen
import dev.home.folddesk.ui.DesktopScreen
import dev.home.folddesk.ui.Dex
import dev.home.folddesk.ui.DesktopGrid
import dev.home.folddesk.ui.DexTheme

/** The home screen. On the cover screen it hands over to One UI Home. */
class MainActivity : ComponentActivity(), WidgetActions {
    private lateinit var shell: ShellController
    private var inner by mutableStateOf(true)
    /** Home pressed while already home; the cover screen goes back to its first page. */
    private var homePresses by mutableIntStateOf(0)
    private var lastHandOff = 0L

    /** A widget on its way to the desktop: bound, maybe set up, then placed. */
    private class Pending(val id: Int, val info: AppWidgetProviderInfo, val at: Cell?, val cover: Boolean = false)
    private var pending: Pending? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val desk = (application as FoldDeskApp).desk
        shell = ShellController(this, desk, keys = null, windowKeys = { TaskbarService.current })
        ShellController.register(shell)
        inner = FoldDeskApp.isInnerScreen(resources.configuration)

        // Home never closes; Back only dismisses popups.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = shell.closePopups()
        })

        setContent {
            LaunchedEffect(desk.hideStatusBar, inner) { showStatusBar(!(desk.hideStatusBar && inner)) }
            DexTheme {
                when {
                    !inner && desk.ownCoverScreen -> CoverScreen(shell, homePresses, this@MainActivity)
                    inner || !desk.coverUsesOneUi ->
                        DesktopScreen(shell, showTaskbar = !TaskbarService.running, widgetActions = this@MainActivity)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!handOffCoverScreen()) shell.desk.refreshOpenApps()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ShellController.closeAll()
        homePresses++
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        inner = FoldDeskApp.isInnerScreen(newConfig)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) handOffCoverScreen()
    }

    /** Hidden on the desktop only; a swipe from the top edge still brings it back for a moment. */
    private fun showStatusBar(show: Boolean) {
        val controller = window.insetsController ?: return
        if (show) {
            controller.show(WindowInsets.Type.statusBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.statusBars())
        }
    }

    private fun handOffCoverScreen(): Boolean {
        if (inner || !shell.desk.coverUsesOneUi) return false
        shell.closePopups()
        // Folding brings both a configuration change and a resume. A second home
        // intent right after the first counts as Home pressed again in One UI
        // Home, which then opens Finder.
        val now = SystemClock.uptimeMillis()
        if (now - lastHandOff < HAND_OFF_GAP_MS) return true
        lastHandOff = now
        return Launcher.openCoverLauncher(this, shell.desk.coverLauncher)
    }

    override fun onStart() {
        super.onStart()
        Widgets.host.startListening()
    }

    override fun onStop() {
        super.onStop()
        Widgets.host.stopListening()
    }

    // --- Widgets ---

    override fun addWidget(info: AppWidgetProviderInfo, at: Cell?) = bind(info, at, cover = false)

    override fun addCoverWidget(info: AppWidgetProviderInfo) = bind(info, null, cover = true)

    private fun bind(info: AppWidgetProviderInfo, at: Cell?, cover: Boolean) {
        val id = Widgets.host.allocateAppWidgetId()
        pending = Pending(id, info, at, cover)
        if (Widgets.manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            afterBind()
        } else {
            // Not a system app: Android asks once per widget app.
            val ask = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            @Suppress("DEPRECATION")
            startActivityForResult(ask, REQUEST_BIND)
        }
    }

    private fun afterBind() {
        val p = pending ?: return
        val needsSetup = p.info.configure != null &&
            (p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0
        if (needsSetup) {
            Widgets.host.startAppWidgetConfigureActivityForResult(this, p.id, 0, REQUEST_CONFIGURE, null)
        } else {
            place()
        }
    }

    private fun place() {
        val p = pending ?: return
        pending = null
        val desk = shell.desk
        if (p.cover) {
            val (w, h) = Widgets.coverSpan(p.info, this)
            val (col, row) = freeCoverSpot(desk.coverWidgets, w, h, Widgets.COVER_COLUMNS)
            desk.updateCoverWidgets(desk.coverWidgets + CoverWidget(p.id, w, h, col, row))
            return
        }
        val (cw, ch) = Widgets.defaultSpan(p.info, this, Dex.desktopCellWidth.value, Dex.desktopCellHeight.value)
        val w = cw.coerceAtMost(DesktopGrid.cols)
        val h = ch.coerceAtMost(DesktopGrid.rows)
        val cell = p.at?.takeIf { desk.areaFree(it, w, h, DesktopGrid.cols, DesktopGrid.rows, DesktopGrid.layout) }
            ?: desk.findFreeArea(w, h, DesktopGrid.cols, DesktopGrid.rows, DesktopGrid.layout)
        if (cell == null) {
            Widgets.host.deleteAppWidgetId(p.id)
            Toast.makeText(this, "No room for this widget on this desktop", Toast.LENGTH_SHORT).show()
            return
        }
        desk.addWidget(DeskWidget(p.id, cell, w, h))
    }

    private fun cancelPending() {
        pending?.let { Widgets.host.deleteAppWidgetId(it.id) }
        pending = null
    }

    override fun configureWidget(id: Int) {
        Widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_RECONFIGURE, null)
    }

    override fun removeWidget(id: Int) {
        Widgets.host.deleteAppWidgetId(id)
        shell.desk.removeWidget(id)
    }

    override fun removeCoverWidget(id: Int) {
        Widgets.host.deleteAppWidgetId(id)
        shell.desk.updateCoverWidgets(shell.desk.coverWidgets.filter { it.id != id })
    }

    @Deprecated("Needed for AppWidgetHost.startAppWidgetConfigureActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQUEST_BIND -> if (ok) afterBind() else cancelPending()
            REQUEST_CONFIGURE -> if (ok) place() else cancelPending()
        }
    }

    override fun onDestroy() {
        ShellController.unregister(shell)
        super.onDestroy()
    }

    private companion object {
        const val HAND_OFF_GAP_MS = 1500L
        const val REQUEST_BIND = 41
        const val REQUEST_CONFIGURE = 42
        const val REQUEST_RECONFIGURE = 43
    }
}

/** Widget operations that need the home activity (permission and setup screens). */
interface WidgetActions {
    fun addWidget(info: AppWidgetProviderInfo, at: Cell?)
    fun addCoverWidget(info: AppWidgetProviderInfo)
    fun configureWidget(id: Int)
    fun removeWidget(id: Int)
    fun removeCoverWidget(id: Int)
}
