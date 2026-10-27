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
    var coverUsesOneUi by mutableStateOf(sp.getBoolean("coverUsesOneUi", true))
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
    var swipeUpForDrawer by mutableStateOf(sp.getBoolean("swipeUpForDrawer", true))
        private set
    var swipeDownClosesDrawer by mutableStateOf(sp.getBoolean("swipeDownClosesDrawer", true))
        private set
    var swipeSwitchesDesktops by mutableStateOf(sp.getBoolean("swipeSwitchesDesktops", true))
        private set
    var swipeDownForNotifications by mutableStateOf(sp.getBoolean("swipeDownForNotifications", true))
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
    /** True with gesture navigation, where the taskbar's Back/Home/Recents keys are redundant. */
    var gestureNav by mutableStateOf(readGestureNav())
        private set
    /** Package of the focused window; set by [TaskbarService] when it runs. */
    var focusedPackage by mutableStateOf<String?>(null)

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

    fun updateCoverUsesOneUi(value: Boolean) {
        coverUsesOneUi = value
        sp.edit().putBoolean("coverUsesOneUi", value).apply()
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

    fun updateSwipeUpForDrawer(value: Boolean) {
        swipeUpForDrawer = value
        sp.edit().putBoolean("swipeUpForDrawer", value).apply()
    }

    fun updateSwipeDownClosesDrawer(value: Boolean) {
        swipeDownClosesDrawer = value
        sp.edit().putBoolean("swipeDownClosesDrawer", value).apply()
    }

    fun updateSwipeDownForNotifications(value: Boolean) {
        swipeDownForNotifications = value
        sp.edit().putBoolean("swipeDownForNotifications", value).apply()
    }

    fun updateSwipeSwitchesDesktops(value: Boolean) {
        swipeSwitchesDesktops = value
        sp.edit().putBoolean("swipeSwitchesDesktops", value).apply()
    }

    private inline fun <reified E : Enum<E>> readEnum(key: String, default: E): E =
        sp.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun saveEnum(key: String, value: Enum<*>) = sp.edit().putString(key, value.name).apply()

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
