package dev.home.folddesk

import android.app.ActivityOptions
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast

/** Starts apps fullscreen or in freeform windows, plus a few system shortcuts. */
object Launcher {
    private const val TAG = "FoldDesk"
    private const val WINDOWING_MODE_FULLSCREEN = 1
    private const val WINDOWING_MODE_FREEFORM = 5
    const val ONE_UI_HOME = "com.sec.android.app.launcher"

    private var cascade = 0

    /** Pop-up windows are an inner-screen thing; on the cover screen apps always open full screen. */
    fun open(context: Context, app: AppEntry, windowed: Boolean, size: WindowSize = WindowSize.Medium) =
        start(context, app, windowOptions(context, windowed && FoldDeskApp.isInnerScreen(context.resources.configuration), size))

    private fun windowOptions(context: Context, windowed: Boolean, size: WindowSize): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        if (windowed) {
            options.setLaunchBounds(nextWindowBounds(context, size))
            setWindowingMode(options, WINDOWING_MODE_FREEFORM)
        } else {
            setWindowingMode(options, WINDOWING_MODE_FULLSCREEN)
        }
        return options
    }

    /** Raises an app that is already open without changing its window. */
    fun bringToFront(context: Context, app: AppEntry) = start(context, app, null)

    private fun start(context: Context, app: AppEntry, options: ActivityOptions?) {
        try {
            context.getSystemService(LauncherApps::class.java)
                .startMainActivity(app.component, app.user, null, options?.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${app.component}", e)
            Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setWindowingMode(options: ActivityOptions, mode: Int) {
        try {
            HiddenApi.invoke(ActivityOptions::class.java, options, "setLaunchWindowingMode", mode)
        } catch (e: Exception) {
            Log.w(TAG, "setLaunchWindowingMode unavailable", e)
        }
    }

    /** Cascades new windows from the upper middle of the screen, DeX style. */
    private fun nextWindowBounds(context: Context, size: WindowSize): Rect {
        val metrics = context.getSystemService(WindowManager::class.java).maximumWindowMetrics
        val screen = metrics.bounds
        val density = context.resources.displayMetrics.density
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        val top = insets.top + (16 * density).toInt()
        // Keep clear of the taskbar, on whichever edge it is, plus a little air.
        val side = (context.applicationContext as FoldDeskApp).desk.taskbarSide
        val barSize = (context.applicationContext as FoldDeskApp).desk.taskbarSize
        val bar = ((if (side.vertical) barSize.sideBar else barSize.bar) * density).toInt()
        val air = (16 * density).toInt()
        val bottom = screen.height() - insets.bottom - (if (side == TaskbarSide.Bottom) bar else 0) - air
        val minX = if (side == TaskbarSide.Left) bar + air else 0
        val maxX = screen.width() - if (side == TaskbarSide.Right) bar + air else 0
        val usableHeight = bottom - top
        val width = ((maxX - minX) * size.width).toInt()
        val height = (usableHeight * size.height).toInt()
        val step = (36 * density).toInt()
        val index = cascade++ % 6
        val left = (minX + (maxX - minX - width) / 2 - 2 * step + index * step)
            .coerceIn(minX, maxX - width)
        val y = (top + index * step).coerceAtMost(bottom - height)
        return Rect(left, y, left + width, y + height)
    }

    fun showNotifications(context: Context) = statusBar(context, "expandNotificationsPanel")
    fun showQuickSettings(context: Context) = statusBar(context, "expandSettingsPanel")

    private fun statusBar(context: Context, method: String) {
        try {
            HiddenApi.invoke(
                StatusBarManager::class.java, context.getSystemService(StatusBarManager::class.java), method,
            )
        } catch (e: Exception) {
            Log.w(TAG, "StatusBarManager.$method failed", e)
        }
    }

    fun appInfo(context: Context, app: AppEntry) {
        context.getSystemService(LauncherApps::class.java)
            .startAppDetailsActivity(app.component, app.user, null, null)
    }

    fun uninstall(context: Context, app: AppEntry) {
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${app.pkg}"))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openSettings(context: Context) = start(context, Intent(Settings.ACTION_SETTINGS))

    /** FoldDesk's own settings: a window when apps open in windows, like everything else. */
    fun openDeskSettings(context: Context) = startWindowed(
        context, Intent(context, SettingsActivity::class.java),
        (context.applicationContext as FoldDeskApp).desk.openInWindow, WindowSize.Medium,
    )

    // Shortcut targets, checked on the Fold7 (One UI 9).
    fun openConnections(context: Context, windowed: Boolean) = startWindowed(
        context, settingsPage("ConnectionsSettingsActivity"), windowed,
        fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )
    fun openBattery(context: Context, windowed: Boolean) =
        startWindowed(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY), windowed)
    fun openSystemSettings(context: Context, windowed: Boolean) =
        startWindowed(context, Intent(Settings.ACTION_SETTINGS), windowed)

    /** The Clock app itself; the SHOW_ALARMS action lands on an alarm widget list on One UI. */
    fun openClock(context: Context, windowed: Boolean) = startWindowed(
        context,
        context.packageManager.getLaunchIntentForPackage("com.sec.android.app.clockpackage")
            ?: Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
        windowed,
    )
    fun openCalendar(context: Context, windowed: Boolean) = startWindowed(
        context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR), windowed,
    )

    private fun settingsPage(name: String) =
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$$name")

    private fun startWindowed(
        context: Context,
        intent: Intent,
        windowed: Boolean,
        size: WindowSize = WindowSize.Medium,
        fallback: Intent? = null,
    ) {
        val inner = FoldDeskApp.isInnerScreen(context.resources.configuration)
        val options = windowOptions(context, windowed && inner, size).toBundle()
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        } catch (e: ActivityNotFoundException) {
            if (fallback != null) startWindowed(context, fallback, windowed, size)
            else Log.w(TAG, "No activity for $intent", e)
        }
    }
    fun openWallpaperPicker(context: Context) =
        start(context, Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
    fun openHomeSettings(context: Context) = start(context, Intent(Settings.ACTION_HOME_SETTINGS))
    fun openAccessibilitySettings(context: Context) =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Hands the cover screen to the chosen launcher (One UI Home by default). */
    fun openCoverLauncher(context: Context, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            // Uninstalled since it was picked: fall back to Samsung's launcher.
            pkg != ONE_UI_HOME && openCoverLauncher(context, ONE_UI_HOME)
        }
    }

    /** Opens a web page or mail address in whichever app handles it. */
    fun openLink(context: Context, url: String) = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $intent", e)
        }
    }
}
