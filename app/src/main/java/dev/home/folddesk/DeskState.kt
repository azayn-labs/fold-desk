package dev.home.folddesk

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [DeskState.coverLauncher] value for FoldDesk's own cover screen layout. */
const val COVER_SCREEN = "folddesk:cover"

data class AppEntry(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val icon: ImageBitmap,
) {
    val pkg: String get() = component.packageName
}

/** Desktop cell, counted from the top-left corner. */
data class Cell(val col: Int, val row: Int)

/** A widget on the cover screen's 3-column grid: [w]×[h] cells, top-left at [col], [row]. */
/** Apps that fit in the cover screen's dock: one per 72 dp, like the recent row, after its margins. */
fun coverDockMax(context: Context) = ((context.resources.configuration.screenWidthDp - 48) / 72).coerceAtLeast(4)

data class CoverWidget(val id: Int, val w: Int, val h: Int, val col: Int = 0, val row: Int = -1) {
    val cells get() = (col until col + w).flatMap { c -> (row until row + h).map { r -> c to r } }

    fun overlaps(other: CoverWidget) = col < other.col + other.w && other.col < col + w &&
        row < other.row + other.h && other.row < row + h
}

/**
 * Puts [moved] where it was dropped and moves whatever it lands on out of
 * the way: into the spot [moved] came from if it fits there (a swap), else
 * the nearest free spot.
 */
fun dropCoverWidget(widgets: List<CoverWidget>, moved: CoverWidget, cols: Int): List<CoverWidget> {
    val old = widgets.firstOrNull { it.id == moved.id } ?: return widgets
    val (inTheWay, clear) = widgets.filter { it.id != moved.id }.partition { it.overlaps(moved) }
    val placed = (clear + moved).toMutableList()
    for (w in inTheWay) {
        // Anywhere in the space the moved widget left, then the nearest free spot.
        val vacated = (old.row until old.row + old.h).flatMap { row ->
            (old.col until old.col + old.w).map { col -> w.copy(col = col.coerceIn(0, cols - w.w), row = row) }
        }
        placed += vacated.firstOrNull { spot -> placed.none { it.overlaps(spot) } } ?: nearestCoverSpot(placed, w, cols)
    }
    // Keep the original order, so nothing else shuffles.
    return widgets.map { w -> placed.first { it.id == w.id } }
}

/** The free spot closest to where [w] is now. */
private fun nearestCoverSpot(others: List<CoverWidget>, w: CoverWidget, cols: Int): CoverWidget {
    val lastRow = (others.maxOfOrNull { it.row + it.h } ?: 0) + w.h
    var best: CoverWidget? = null
    var bestDistance = Int.MAX_VALUE
    for (row in 0..lastRow) for (col in 0..cols - w.w) {
        val probe = w.copy(col = col, row = row)
        if (others.any { it.overlaps(probe) }) continue
        val distance = (row - w.row) * (row - w.row) + (col - w.col) * (col - w.col)
        if (distance < bestDistance) { best = probe; bestDistance = distance }
    }
    return best ?: w.copy(row = lastRow)
}

/**
 * First spot from the top where a [w]×[h] widget fits among [others], from
 * [fromRow] down.
 */
fun freeCoverSpot(others: List<CoverWidget>, w: Int, h: Int, cols: Int, fromRow: Int = 0): Pair<Int, Int> {
    var row = fromRow
    while (true) {
        for (col in 0..cols - w) {
            val probe = CoverWidget(-1, w, h, col, row)
            if (others.none { it.overlaps(probe) }) return col to row
        }
        row++
    }
}

/** A widget on a desktop: [cell] is its top-left, [w]×[h] its span in cells. */
data class DeskWidget(val id: Int, val cell: Cell, val w: Int, val h: Int) {
    fun covers(c: Cell) = c.col in cell.col until cell.col + w && c.row in cell.row until cell.row + h
    val cells get() = (0 until w).flatMap { dx -> (0 until h).map { dy -> Cell(cell.col + dx, cell.row + dy) } }
}

/** A virtual desktop: its own icons and its own app windows. */
data class Desktop(val id: Int, val name: String)

/** An app in the taskbar's "open" section, derived from usage events. */
data class TaskbarApp(val app: AppEntry, val active: Boolean)

enum class Network { None, Wifi, Cellular, Other }

/** Process-wide launcher state; Compose reads it directly. */
class DeskState(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sp = context.getSharedPreferences("desk", Context.MODE_PRIVATE)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var pinned by mutableStateOf(readList("pinned"))
        private set
    var desktops by mutableStateOf(readDesktops())
        private set
    var currentDesktopId by mutableStateOf(
        sp.getInt("currentDesktop", 1).takeIf { id -> desktops.any { it.id == id } } ?: desktops.first().id,
    )
        private set
    /** Icons on the current desktop. */
    var desktop by mutableStateOf(readDesktop(currentDesktopId))
        private set
    /** Widgets on the current desktop. */
    var widgets by mutableStateOf(readWidgets(currentDesktopId))
        private set
    /** Which desktop each open app (package) lives on. */
    var appDesktop by mutableStateOf(readAppDesktop())
        private set
    var openApps by mutableStateOf<List<TaskbarApp>>(emptyList())
        private set
    var openInWindow by mutableStateOf(sp.getBoolean("openInWindow", true))
        private set
    var notificationsInWindows by mutableStateOf(sp.getBoolean("notificationsInWindows", false))
        private set
    var keepOverFullScreen by mutableStateOf(sp.getBoolean("keepOverFullScreen", false))
        private set
    var hideStatusBar by mutableStateOf(sp.getBoolean("hideStatusBar", false))
        private set
    /** Apps pinned to the top of the cover screen's app list, by key, in the order added. */
    var coverFavourites by mutableStateOf(readList("coverFavourites"))
        private set

    fun isCoverFavourite(app: AppEntry) = app.key in coverFavourites

    fun toggleCoverFavourite(app: AppEntry) {
        coverFavourites = if (isCoverFavourite(app)) coverFavourites - app.key else coverFavourites + app.key
        sp.edit().putString("coverFavourites", coverFavourites.joinToString("\n")).apply()
    }

    /** The cover screen's dock, by key; starts as a copy of the first taskbar apps. */
    var coverDock by mutableStateOf(if (sp.contains("coverDock")) readList("coverDock") else pinned.take(5))
        private set

    fun isInCoverDock(app: AppEntry) = app.key in coverDock

    /** The menu checks there's room before adding. */
    fun toggleCoverDock(app: AppEntry) =
        saveCoverDock(if (isInCoverDock(app)) coverDock - app.key else coverDock + app.key)

    fun moveCoverDock(key: String, index: Int) {
        val list = (coverDock - key).toMutableList()
        list.add(index.coerceIn(0, list.size), key)
        saveCoverDock(list)
    }

    private fun saveCoverDock(list: List<String>) {
        coverDock = list
        sp.edit().putString("coverDock", list.joinToString("\n")).apply()
    }

    /** Apps left out of the app drawer, by key. */
    var hiddenApps by mutableStateOf(readList("hiddenApps").toSet())
        private set
    /**
     * Launcher package the cover screen hands over to; FoldDesk's own package
     * means its desktop runs on both screens, [COVER_SCREEN] its cover screen
     * layout. Migrates the old on/off switch.
     */
    var coverLauncher by mutableStateOf(
        sp.getString("coverLauncher", null)
            ?: if (sp.getBoolean("coverUsesOneUi", true)) Launcher.ONE_UI_HOME else context.packageName,
    )
        private set
    val coverUsesOneUi get() = coverLauncher != context.packageName && coverLauncher != COVER_SCREEN
    val ownCoverScreen get() = coverLauncher == COVER_SCREEN
    /** Share of the cover screen's free height given to notifications; the rest is the calendar. */
    var coverSplit by mutableStateOf(sp.getFloat("coverSplit", 0.5f))
        private set
    /** Widgets on the cover screen's widget page, top to bottom; separate from the desktops'. */
    var coverWidgets by mutableStateOf(
        readList("coverWidgets").mapNotNull { line ->
            val p = line.split('|').mapNotNull { it.toIntOrNull() }
            when (p.size) {
                5 -> CoverWidget(p[0], p[1], p[2], p[3], p[4])
                4 -> CoverWidget(p[0], p[1], p[2], p[3])
                3 -> CoverWidget(p[0], p[1], p[2])
                // Before the grid: full width and a height in dp.
                2 -> CoverWidget(p[0], Widgets.COVER_COLUMNS, Widgets.coverRows(p[1]))
                else -> null
            }
        }.let { list ->
            // Saved before widgets had a row: stack them as they used to show.
            list.fold(emptyList<CoverWidget>()) { placed, w ->
                if (w.row >= 0) placed + w
                else {
                    val col = w.col.coerceIn(0, Widgets.COVER_COLUMNS - w.w)
                    var row = 0
                    while (placed.any { it.overlaps(w.copy(col = col, row = row)) }) row++
                    placed + w.copy(col = col, row = row)
                }
            }
        },
    )
        private set

    var autoHideTaskbar by mutableStateOf(sp.getBoolean("autoHideTaskbar", false))
        private set
    var taskbarBackground by mutableStateOf(readEnum("taskbarBackground", TaskbarBackground.Blurred))
        private set
    var taskbarSize by mutableStateOf(readEnum("taskbarSize", TaskbarSize.Default))
        private set
    /** Custom taskbar colour and accent (ARGB); null follows the light/dark theme. */
    var taskbarColour by mutableStateOf(sp.getLong("taskbarColour", 0L).takeIf { it != 0L })
        private set
    var accentColour by mutableStateOf(sp.getLong("accentColour", 0L).takeIf { it != 0L })
        private set
    var taskbarAlignment by mutableStateOf(readEnum("taskbarAlignment", Placement.Left))
        private set
    var taskbarSide by mutableStateOf(readEnum("taskbarSide", TaskbarSide.Bottom))
        private set
    var statusAction by mutableStateOf(readEnum("statusAction", StatusAction.QuickSettings))
        private set
    var clockAction by mutableStateOf(readEnum("clockAction", ClockAction.Notifications))
        private set
    var windowSize by mutableStateOf(readEnum("windowSize", WindowSize.Medium))
        private set
    var swipeUp by mutableStateOf(readSwipe("swipeUp", "swipeUpForDrawer", SwipeAction.Drawer))
        private set
    var swipeDownClosesDrawer by mutableStateOf(sp.getBoolean("swipeDownClosesDrawer", true))
        private set
    var swipeSwitchesDesktops by mutableStateOf(sp.getBoolean("swipeSwitchesDesktops", true))
        private set
    var swipeDown by mutableStateOf(readSwipe("swipeDown", "swipeDownForNotifications", SwipeAction.Notifications))
        private set

    // System status for the taskbar tray.
    var batteryLevel by mutableStateOf<Int?>(null)
        private set
    var charging by mutableStateOf(false)
        private set
    var network by mutableStateOf(Network.None)
        private set
    /** Apps with notifications, newest first; set by [NotificationWatcher]. */
    var notificationPackages by mutableStateOf<List<String>>(emptyList())
    /** Notifications for the cover screen, one entry per app; set by [NotificationWatcher]. */
    var coverNotifications by mutableStateOf<List<CoverNotification>>(emptyList())
    /** True with gesture navigation, where the taskbar's Back/Home/Recents keys are redundant. */
    var gestureNav by mutableStateOf(readGestureNav())
        private set
    /** Package of the focused window; set by [TaskbarService] when it runs. */
    var focusedPackage by mutableStateOf<String?>(null)
    /** Apps last seen filling the screen (or in split screen); set by [TaskbarService]. */
    private val fullScreenApps = mutableSetOf<String>()

    fun noteAppWindow(pkg: String, fullScreen: Boolean) {
        if (fullScreen) fullScreenApps += pkg else fullScreenApps -= pkg
    }

    fun wasFullScreen(pkg: String) = pkg in fullScreenApps

    private val byKey get() = apps.associateBy { it.key }
    private var homePackages = emptySet<String>()
    // Packages keep their taskbar slot while they stay recent, like a real taskbar.
    private val firstSeen = LinkedHashMap<String, Long>()
    /** Every app the open section remembers, on every desktop. */
    private var recentEntries = emptyList<AppEntry>()
    private var lastUsed = emptyMap<String, Long>()
    /** While FoldDesk itself is shuffling windows between desktops. */
    private var switchingUntil = 0L
    // Persisted so a restart doesn't bring back apps that were closed.
    private val dismissedAt = HashMap(readList("dismissed").mapNotNull { line ->
        line.split('|').takeIf { it.size == 2 }?.let { it[0] to it[1].toLong() }
    }.toMap())

    init {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
            override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
            override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
            override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
            override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = reload()
        })
        watchBattery()
        watchNetwork()
        watchNavigationMode()
        reload()
    }

    private fun watchBattery() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val raw = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                if (raw >= 0) batteryLevel = raw * 100 / scale
                charging = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun watchNavigationMode() {
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Secure.getUriFor(NAVIGATION_MODE), false,
            object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    gestureNav = readGestureNav()
                }
            },
        )
    }

    private fun readGestureNav() =
        android.provider.Settings.Secure.getInt(context.contentResolver, NAVIGATION_MODE, 0) == NAV_MODE_GESTURAL

    private fun watchNetwork() {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
                val next = when {
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> Network.Wifi
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> Network.Cellular
                    else -> Network.Other
                }
                scope.launch { network = next }
            }

            override fun onLost(n: android.net.Network) {
                scope.launch { network = Network.None }
            }
        })
    }

    fun reload() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadApps() }
            homePackages = withContext(Dispatchers.IO) { loadHomePackages() }
            apps = loaded
            if (!sp.contains("pinned")) seedDefaults()
            refreshOpenApps()
        }
    }

    private fun loadApps(): List<AppEntry> {
        val iconPx = (48 * context.resources.displayMetrics.density).toInt()
        return userManager.userProfiles.flatMap { user ->
            val serial = userManager.getSerialNumberForUser(user)
            launcherApps.getActivityList(null, user)
                .filter { it.componentName.packageName != context.packageName }
                .map { info ->
                    AppEntry(
                        key = "${info.componentName.flattenToShortString()}#$serial",
                        component = info.componentName,
                        user = user,
                        label = info.label.toString(),
                        icon = info.getBadgedIcon(0).toBitmap(iconPx, iconPx).asImageBitmap(),
                    )
                }
        }.sortedBy { it.label.lowercase() }
    }

    private fun loadHomePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    private fun seedDefaults() {
        val wanted = listOf(
            "com.sec.android.app.myfiles", "com.sec.android.app.sbrowser", "com.android.chrome",
            "com.samsung.android.messaging", "com.android.settings",
        )
        val own = Process.myUserHandle()
        savePinned(wanted.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg && it.user == own }?.key })
    }

    fun app(key: String): AppEntry? = byKey[key]

    /** The main-profile entry for [pkg], used where only a package is known. */
    fun appForPackage(pkg: String): AppEntry? =
        apps.firstOrNull { it.pkg == pkg && it.user == Process.myUserHandle() }

    // --- Taskbar pins ---

    fun isPinned(app: AppEntry) = app.key in pinned
    fun togglePin(app: AppEntry) = savePinned(if (isPinned(app)) pinned - app.key else pinned + app.key)

    fun movePin(key: String, toIndex: Int) {
        val list = pinned.toMutableList()
        if (list.remove(key)) {
            list.add(toIndex.coerceIn(0, list.size), key)
            savePinned(list)
        }
    }

    /** Pins [app] at [index] (dragged in among the pinned apps). */
    fun pinAt(app: AppEntry, index: Int) {
        val list = (pinned - app.key).toMutableList()
        list.add(index.coerceIn(0, list.size), app.key)
        savePinned(list)
    }

    private fun savePinned(list: List<String>) {
        pinned = list
        sp.edit().putString("pinned", list.joinToString("\n")).apply()
    }

    // --- Desktop icons ---

    fun isOnDesktop(app: AppEntry) = app.key in desktop

    fun addToDesktop(app: AppEntry, rows: Int) {
        if (isOnDesktop(app)) return
        val taken = desktop.values.toSet() + widgetCells()
        // Fill column by column, like a desktop OS.
        val cell = generateSequence(0) { it + 1 }
            .map { Cell(it / rows, it % rows) }
            .first { it !in taken }
        saveDesktop(desktop + (app.key to cell))
    }

    fun removeFromDesktop(app: AppEntry) = saveDesktop(desktop - app.key)

    /**
     * Where each icon shows on a [cols]×[rows] grid. Icons whose saved cell is
     * off this grid (placed in the other orientation) show in the nearest free
     * cell instead; their saved cell is kept, so rotating back restores them.
     */
    /** Icons saved on desktop [id] (the current one, or one sliding away). */
    fun iconsOf(id: Int): Map<String, Cell> = if (id == currentDesktopId) desktop else readDesktop(id)

    fun desktopLayout(
        cols: Int,
        rows: Int,
        icons: Map<String, Cell> = desktop,
        blocked: Set<Cell> = widgetCells(),
    ): Map<String, Cell> {
        val desktop = icons
        val fits = desktop.filterValues { it.col < cols && it.row < rows && it !in blocked }
        val placed = LinkedHashMap(fits)
        val taken = (fits.values + blocked).toMutableSet()
        for ((key, saved) in desktop) {
            if (key in placed) continue
            val want = Cell(saved.col.coerceAtMost(cols - 1), saved.row.coerceAtMost(rows - 1))
            val cell = (0 until cols).flatMap { c -> (0 until rows).map { r -> Cell(c, r) } }
                .filter { it !in taken }
                .minByOrNull { (it.col - want.col) * (it.col - want.col) + (it.row - want.row) * (it.row - want.row) }
                ?: break // Grid is full; leave the rest where they are.
            placed[key] = cell
            taken += cell
        }
        return placed
    }

    /**
     * Drops [key] on [cell], swapping with whatever was there. [layout] is what
     * the user sees, and becomes the saved arrangement.
     */
    fun moveOnDesktop(key: String, cell: Cell, layout: Map<String, Cell>) {
        val from = layout[key] ?: return
        if (cell in widgetCells()) return // A widget is there.
        val occupant = layout.entries.firstOrNull { it.value == cell }?.key
        val next = desktop.toMutableMap().apply { putAll(layout) }
        next[key] = cell
        if (occupant != null && occupant != key) next[occupant] = from
        saveDesktop(next)
    }

    /**
     * Drops a group of icons: the first lands on [start], the rest on the next
     * free cells after it (down the column, then on to the next column).
     */
    fun moveGroupOnDesktop(keys: List<String>, start: Cell, rows: Int, layout: Map<String, Cell>) {
        val next = desktop.toMutableMap().apply { putAll(layout) }
        val taken = (next.filterKeys { it !in keys }.values + widgetCells()).toMutableSet()
        var index = start.col * rows + start.row
        for (key in keys) {
            var cell: Cell
            do {
                cell = Cell(index / rows, index % rows)
                index++
            } while (cell in taken)
            next[key] = cell
            taken += cell
        }
        saveDesktop(next)
    }

    fun removeFromDesktop(keys: Collection<String>) = saveDesktop(desktop - keys.toSet())

    /** Moves the shortcut [key] from this desktop to desktop [id]'s first free cell. */
    fun moveIconToDesktop(key: String, id: Int) {
        if (id == currentDesktopId || key !in desktop) return
        val target = readDesktop(id).toMutableMap()
        if (key !in target) {
            val taken = target.values.toSet()
            target[key] = generateSequence(0) { it + 1 }
                .map { Cell(it / DESKTOP_ROWS_GUESS, it % DESKTOP_ROWS_GUESS) }
                .first { it !in taken }
        }
        saveDesktop(target, id)
        saveDesktop(desktop - key)
    }

    private fun saveDesktop(map: Map<String, Cell>, id: Int = currentDesktopId) {
        if (id == currentDesktopId) desktop = map
        sp.edit().putString(
            desktopIconsKey(id), map.entries.joinToString("\n") { "${it.key}|${it.value.col}|${it.value.row}" },
        ).apply()
    }

    // --- Virtual desktops ---

    val currentDesktop: Desktop get() = desktops.first { it.id == currentDesktopId }
    val canAddDesktop get() = desktops.size < MAX_DESKTOPS

    /** Apps open on desktop [id], least recently used first. */
    fun appsOn(id: Int): List<AppEntry> =
        recentEntries.filter { appDesktop[it.pkg] == id }.sortedBy { lastUsed[it.pkg] ?: 0L }

    /**
     * Windows to bring back when returning to desktop [id]: the ones that were
     * on screen when it was left (bottom to top), if still open and still here.
     * Apps whose windows were closed weren't on screen, so they stay closed.
     */
    fun windowsToRestore(id: Int): List<AppEntry> {
        val open = recentEntries.associateBy { it.pkg }
        return readList(snapshotKey(id)).filter { appDesktop[it] == id }.mapNotNull { open[it] }
    }

    /** Records which of desktop [id]'s windows are showing, bottom to top. */
    fun saveWindowSnapshot(id: Int, packages: List<String>) {
        sp.edit().putString(snapshotKey(id), packages.distinct().joinToString("\n")).apply()
    }

    /** Adds [pkg] to the top of desktop [id]'s snapshot (an app moved there). */
    fun addToWindowSnapshot(id: Int, pkg: String) =
        saveWindowSnapshot(id, readList(snapshotKey(id)).filter { it != pkg } + pkg)

    private fun snapshotKey(id: Int) = "windows_$id"

    fun desktopOf(app: AppEntry): Int? = appDesktop[app.pkg]
    fun isOpen(app: AppEntry) = recentEntries.any { it.pkg == app.pkg }

    fun addDesktop(): Desktop? {
        if (!canAddDesktop) return null
        val id = (desktops.maxOf { it.id }) + 1
        val created = Desktop(id, "Desktop ${desktops.size + 1}")
        saveDesktops(desktops + created)
        return created
    }

    fun renameDesktop(id: Int, name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        saveDesktops(desktops.map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Deletes [id]; its apps and icons move to the neighbouring desktop. */
    fun deleteDesktop(id: Int): Int? {
        if (desktops.size <= 1) return null
        val index = desktops.indexOfFirst { it.id == id }
        val heir = desktops.getOrNull(index - 1) ?: desktops[index + 1]
        // Widgets move over too, into free space where possible (fixed up on display).
        saveWidgets(readWidgets(heir.id) + readWidgets(id), heir.id)
        sp.edit().remove(widgetsKey(id)).apply()
        // Icons join the heir's desktop in its free cells.
        val heirIcons = readDesktop(heir.id).toMutableMap()
        val taken = heirIcons.values.toMutableSet()
        var next = 0
        for ((key, _) in readDesktop(id)) {
            if (key in heirIcons) continue
            var cell: Cell
            do {
                cell = Cell(next / DESKTOP_ROWS_GUESS, next % DESKTOP_ROWS_GUESS)
                next++
            } while (cell in taken)
            heirIcons[key] = cell
            taken += cell
        }
        saveDesktop(heirIcons, heir.id)
        sp.edit().remove(desktopIconsKey(id)).apply()
        saveAppDesktop(appDesktop.mapValues { (_, d) -> if (d == id) heir.id else d })
        saveDesktops(desktops.filter { it.id != id })
        if (currentDesktopId == id) setCurrentDesktop(heir.id)
        return heir.id
    }

    fun setCurrentDesktop(id: Int) {
        if (desktops.none { it.id == id }) return
        currentDesktopId = id
        desktop = readDesktop(id)
        widgets = readWidgets(id)
        sp.edit().putInt("currentDesktop", id).apply()
        refreshOpenApps()
    }

    fun assignApp(pkg: String, id: Int) {
        if (appDesktop[pkg] != id) saveAppDesktop(appDesktop + (pkg to id))
        refreshOpenApps()
    }

    /** FoldDesk is minimising/restoring windows; don't treat that as the user moving apps. */
    fun beginSwitch() {
        switchingUntil = System.currentTimeMillis() + SWITCH_GRACE_MS
    }

    fun endSwitch() {
        switchingUntil = 0L
        refreshOpenApps()
    }

    private val switching get() = System.currentTimeMillis() < switchingUntil

    /**
     * An app came to the front without FoldDesk opening it (Samsung's
     * minimised-apps bubble, a notification). New apps join this desktop;
     * for an app that lives on another desktop, returns that desktop so the
     * caller can follow it there, like DeX.
     */
    fun onAppFocused(pkg: String): Int? {
        if (switching || pkg == context.packageName || pkg in homePackages) return null
        if (appForPackage(pkg) == null) return null
        val home = appDesktop[pkg]
        if (home == null) {
            assignApp(pkg, currentDesktopId)
            return null
        }
        return home.takeIf { it != currentDesktopId && desktops.any { d -> d.id == it } }
    }

    private fun saveDesktops(list: List<Desktop>) {
        desktops = list
        sp.edit().putString("desktops", list.joinToString("\n") { "${it.id}|${it.name}" }).apply()
    }

    private fun saveAppDesktop(map: Map<String, Int>) {
        appDesktop = map
        sp.edit().putString("appDesktop", map.entries.joinToString("\n") { "${it.key}|${it.value}" }).apply()
    }

    // --- Settings ---

    fun updateOpenInWindow(value: Boolean) {
        openInWindow = value
        sp.edit().putBoolean("openInWindow", value).apply()
    }

    fun updateKeepOverFullScreen(value: Boolean) {
        keepOverFullScreen = value
        sp.edit().putBoolean("keepOverFullScreen", value).apply()
    }

    fun updateHideStatusBar(value: Boolean) {
        hideStatusBar = value
        sp.edit().putBoolean("hideStatusBar", value).apply()
    }

    fun isHidden(app: AppEntry) = app.key in hiddenApps

    fun updateHiddenApps(keys: Set<String>) {
        hiddenApps = keys
        sp.edit().putString("hiddenApps", keys.joinToString("\n")).apply()
    }

    fun updateNotificationsInWindows(value: Boolean) {
        notificationsInWindows = value
        sp.edit().putBoolean("notificationsInWindows", value).apply()
    }

    fun updateCoverWidgets(list: List<CoverWidget>) {
        coverWidgets = list
        sp.edit().putString("coverWidgets", list.joinToString("\n") { "${it.id}|${it.w}|${it.h}|${it.col}|${it.row}" }).apply()
    }

    fun updateCoverSplit(value: Float) {
        coverSplit = value
        sp.edit().putFloat("coverSplit", value).apply()
    }

    fun updateCoverLauncher(pkg: String) {
        coverLauncher = pkg
        sp.edit().putString("coverLauncher", pkg).apply()
    }

    fun updateAutoHideTaskbar(value: Boolean) {
        autoHideTaskbar = value
        sp.edit().putBoolean("autoHideTaskbar", value).apply()
    }

    fun updateTaskbarBackground(value: TaskbarBackground) {
        taskbarBackground = value
        saveEnum("taskbarBackground", value)
    }

    fun updateTaskbarSize(value: TaskbarSize) {
        taskbarSize = value
        saveEnum("taskbarSize", value)
    }

    /** Colours apply at once (live preview); null resets to the theme default. */
    fun updateColours(bar: Long?, accent: Long?) {
        taskbarColour = bar
        accentColour = accent
        sp.edit().putLong("taskbarColour", bar ?: 0L).putLong("accentColour", accent ?: 0L).apply()
    }

    /** Name of the current colour combination, for the settings row. */
    val coloursName: String
        get() = when {
            taskbarColour == null && accentColour == null -> "Default"
            else -> ColourPresets.firstOrNull { it.bar == taskbarColour && it.accent == accentColour }?.name ?: "Custom"
        }

    /** Installed launchers for the cover screen, as (package, label). */
    fun installedLaunchers(): List<Pair<String, String>> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName && it.first != "com.android.settings" }
            .distinctBy { it.first }
    }

    fun updateTaskbarSide(value: TaskbarSide) {
        taskbarSide = value
        saveEnum("taskbarSide", value)
    }

    fun updateTaskbarAlignment(value: Placement) {
        taskbarAlignment = value
        saveEnum("taskbarAlignment", value)
    }

    fun updateStatusAction(value: StatusAction) {
        statusAction = value
        saveEnum("statusAction", value)
    }

    fun updateClockAction(value: ClockAction) {
        clockAction = value
        saveEnum("clockAction", value)
    }

    fun updateWindowSize(value: WindowSize) {
        windowSize = value
        saveEnum("windowSize", value)
    }

    /** Window size picked for [app] from its menu; null follows [windowSize]. */
    fun appWindowSize(app: AppEntry): WindowSize? =
        sp.getString("windowSize_${app.key}", null)?.let { name -> WindowSize.entries.firstOrNull { it.name == name } }

    fun windowSizeFor(app: AppEntry): WindowSize = appWindowSize(app) ?: windowSize

    fun updateAppWindowSize(app: AppEntry, value: WindowSize?) {
        val key = "windowSize_${app.key}"
        if (value == null) sp.edit().remove(key).apply() else saveEnum(key, value)
    }

    fun updateSwipeUp(value: SwipeAction) {
        swipeUp = value
        saveEnum("swipeUp", value)
    }

    fun updateSwipeDownClosesDrawer(value: Boolean) {
        swipeDownClosesDrawer = value
        sp.edit().putBoolean("swipeDownClosesDrawer", value).apply()
    }

    fun updateSwipeDown(value: SwipeAction) {
        swipeDown = value
        saveEnum("swipeDown", value)
    }

    fun updateSwipeSwitchesDesktops(value: Boolean) {
        swipeSwitchesDesktops = value
        sp.edit().putBoolean("swipeSwitchesDesktops", value).apply()
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        sp.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun saveEnum(key: String, value: Enum<*>) = sp.edit().putString(key, value.name).apply()

    /** Swipe actions replaced on/off switches; a switch that was off carries over as None. */
    private fun readSwipe(key: String, oldSwitch: String, default: SwipeAction) =
        if (sp.contains(key) || sp.getBoolean(oldSwitch, true)) readEnum(key, default) else SwipeAction.None

    // --- Open apps (needs usage access) ---

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** Hides [app] from the open section until it is used again. */
    fun dismissOpen(app: AppEntry) = dismissPackages(listOf(app.pkg))

    /** A window was closed with its own close button. */
    fun dismissOpen(label: String) =
        dismissPackages(recentEntries.filter { it.label == label }.map { it.pkg })

    /** "Close all" in Recents: empty the taskbar's open section. */
    fun dismissAllOpen() = dismissPackages(recentEntries.map { it.pkg })

    /**
     * Recents is showing every task it has ([labels]); drop open apps that
     * aren't among them, i.e. were swiped away.
     */
    fun syncWithRecents(labels: Set<String>) =
        dismissPackages(recentEntries.filter { it.label !in labels }.map { it.pkg })

    private fun dismissPackages(packages: List<String>) {
        if (packages.isEmpty()) return
        val now = System.currentTimeMillis()
        packages.forEach {
            dismissedAt[it] = now
            firstSeen.remove(it)
        }
        // A closed app no longer lives on any desktop.
        saveAppDesktop(appDesktop - packages.toSet())
        // Entries older than the recent window can never matter again.
        dismissedAt.values.removeAll { it < now - RECENT_WINDOW_MS }
        sp.edit().putString("dismissed", dismissedAt.entries.joinToString("\n") { "${it.key}|${it.value}" }).apply()
        refreshOpenApps()
    }

    fun refreshOpenApps() {
        if (!hasUsageAccess()) return
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - RECENT_WINDOW_MS, end)
        val lastResumed = HashMap<String, Long>()
        val resumed = HashMap<String, MutableSet<String>>()
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            val pkg = event.packageName
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    lastResumed[pkg] = event.timeStamp
                    resumed.getOrPut(pkg) { mutableSetOf() }.add(event.className ?: "")
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    resumed[pkg]?.remove(event.className ?: "")
            }
        }
        val own = Process.myUserHandle()
        val entryFor = apps.filter { it.user == own }.groupBy { it.pkg }.mapValues { it.value.first() }
        val candidates = lastResumed.filter { (pkg, time) ->
            pkg != context.packageName && pkg !in homePackages && pkg in entryFor &&
                time > (dismissedAt[pkg] ?: 0L)
        }
        firstSeen.keys.retainAll(candidates.keys)
        candidates.entries.sortedBy { it.value }.forEach { firstSeen.putIfAbsent(it.key, it.value) }
        recentEntries = firstSeen.keys.map { entryFor.getValue(it) }
        lastUsed = lastResumed
        // Apps seen for the first time belong to the desktop they appeared on.
        val unassigned = firstSeen.keys.filter { it !in appDesktop }
        if (unassigned.isNotEmpty() && !switching) {
            saveAppDesktop(appDesktop + unassigned.associateWith { currentDesktopId })
        }
        // The accessibility service knows the focused window exactly; usage events are a fallback.
        val focused = focusedPackage
        val here = firstSeen.keys.filter { (appDesktop[it] ?: currentDesktopId) == currentDesktopId }
        openApps = here.takeLast(MAX_OPEN).map { pkg ->
            val active = if (focused != null) pkg == focused else resumed[pkg].orEmpty().isNotEmpty()
            TaskbarApp(entryFor.getValue(pkg), active)
        }
    }

    /** Package of the most recently resumed activity, if usage access is granted. */
    /** Apps used most recently, newest first, from the last [days] days; empty without usage access. */
    fun recentlyUsed(limit: Int, days: Int = 7): List<AppEntry> {
        if (!hasUsageAccess()) return emptyList()
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - days * 24 * 60 * 60 * 1000L, end)
        val event = UsageEvents.Event()
        val lastUsed = HashMap<String, Long>()
        while (events.getNextEvent(event)) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastUsed[event.packageName] = event.timeStamp
        }
        return lastUsed.entries.sortedByDescending { it.value }
            .asSequence()
            .map { it.key }
            .filter { it != context.packageName && it !in homePackages }
            .mapNotNull { appForPackage(it) }
            .filter { !isHidden(it) }
            .take(limit)
            .toList()
    }

    fun foregroundPackage(): String? {
        if (!hasUsageAccess()) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - 60 * 60 * 1000L, end)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.getNextEvent(event)) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }

    private fun readList(name: String) =
        sp.getString(name, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    // --- Widgets ---

    fun widgetCells(list: List<DeskWidget> = widgets): Set<Cell> = list.flatMap { it.cells }.toSet()

    /** Icons on this desktop, as cells, for widget placement. */
    private fun iconCells(layout: Map<String, Cell>) = layout.values.toSet()

    /** Is [w]×[h] at [cell] free of icons and other widgets (ignoring widget [except])? */
    fun areaFree(cell: Cell, w: Int, h: Int, cols: Int, rows: Int, layout: Map<String, Cell>, except: Int? = null): Boolean {
        if (cell.col < 0 || cell.row < 0 || cell.col + w > cols || cell.row + h > rows) return false
        val blocked = iconCells(layout) + widgetCells(widgets.filter { it.id != except })
        return DeskWidget(-1, cell, w, h).cells.none { it in blocked }
    }

    /** First free [w]×[h] area, scanning rows top-down then columns, preferring the left. */
    fun findFreeArea(w: Int, h: Int, cols: Int, rows: Int, layout: Map<String, Cell>): Cell? {
        for (col in 0..cols - w) for (row in 0..rows - h) {
            val c = Cell(col, row)
            if (areaFree(c, w, h, cols, rows, layout)) return c
        }
        return null
    }

    fun addWidget(widget: DeskWidget) = saveWidgets(widgets.filter { it.id != widget.id } + widget)
    fun updateWidget(widget: DeskWidget) = saveWidgets(widgets.map { if (it.id == widget.id) widget else it })
    fun removeWidget(id: Int) = saveWidgets(widgets.filter { it.id != id })

    private fun widgetsKey(id: Int) = "widgets_$id"

    private fun readWidgets(id: Int): List<DeskWidget> = readList(widgetsKey(id)).mapNotNull { line ->
        val p = line.split('|').mapNotNull { it.toIntOrNull() }
        if (p.size == 5) DeskWidget(p[0], Cell(p[1], p[2]), p[3], p[4]) else null
    }

    private fun saveWidgets(list: List<DeskWidget>, id: Int = currentDesktopId) {
        if (id == currentDesktopId) widgets = list
        sp.edit().putString(
            widgetsKey(id), list.joinToString("\n") { "${it.id}|${it.cell.col}|${it.cell.row}|${it.w}|${it.h}" },
        ).apply()
    }

    private fun desktopIconsKey(id: Int) = "desktop_$id"

    private fun readDesktop(id: Int): Map<String, Cell> {
        // Before multiple desktops, desktop 1's icons were stored under "desktop".
        val key = if (id == 1 && !sp.contains(desktopIconsKey(1))) "desktop" else desktopIconsKey(id)
        return readList(key).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size == 3) parts[0] to Cell(parts[1].toInt(), parts[2].toInt()) else null
        }.toMap()
    }

    private fun readDesktops(): List<Desktop> = readList("desktops").mapNotNull { line ->
        val parts = line.split('|', limit = 2)
        parts[0].toIntOrNull()?.let { Desktop(it, parts.getOrElse(1) { "Desktop $it" }) }
    }.ifEmpty { listOf(Desktop(1, "Desktop 1")) }

    private fun readAppDesktop(): Map<String, Int> = readList("appDesktop").mapNotNull { line ->
        val parts = line.split('|')
        parts.getOrNull(1)?.toIntOrNull()?.let { parts[0] to it }
    }.toMap()

    companion object {
        private const val RECENT_WINDOW_MS = 4 * 60 * 60 * 1000L
        private const val MAX_OPEN = 10
        const val MAX_DESKTOPS = 4
        private const val SWITCH_GRACE_MS = 6000L
        // Merging icons from a deleted desktop happens off-screen; rows get fixed up on display.
        private const val DESKTOP_ROWS_GUESS = 8
        private const val NAVIGATION_MODE = "navigation_mode"
        private const val NAV_MODE_GESTURAL = 2
    }
}
