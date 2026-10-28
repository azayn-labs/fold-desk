package dev.home.folddesk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/** A menu row; with [submenu] it opens a side list instead of running [action]. */
data class MenuItem(
    val label: String,
    /** Current choice, shown dimmed on the right. */
    val value: String? = null,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val submenu: List<MenuItem>? = null,
    val action: () -> Unit = {},
)

/**
 * A context menu. [groups] are separated by dividers; [header] shows the app's
 * icon and name on top. With [anchorBottom] the menu sits on the bottom edge of
 * the popup area (menus raised from the taskbar).
 */
data class MenuState(
    val at: Offset,
    val groups: List<List<MenuItem>>,
    val header: AppEntry? = null,
    val anchorBottom: Boolean = false,
    /** Raised from the taskbar on this edge: [at] is on screen, and the menu opens beside the bar. */
    val taskbarEdge: TaskbarSide? = null,
)

enum class OpenMode { Default, Window, Fullscreen, Front }

/** System keys; only available while [TaskbarService] is running. */
interface SystemKeys {
    fun back()
    fun home()
    fun recents()
    fun notifications()
    fun quickSettings()
    fun powerMenu()
    fun showDesktop()

    /**
     * Minimises everything on screen except [keep], then brings back [restore]
     * in order, then raises [keep]; calls [done] after.
     */
    fun switchWindows(restore: List<AppEntry>, keep: AppEntry? = null, done: () -> Unit)

    /** Minimises [app]'s window, if it's showing. */
    fun minimizeApp(app: AppEntry)

    /** Packages of the app windows on screen now, bottom to top. */
    fun visibleApps(): List<String>

    /** Opens the taskbar overlay's app drawer. */
    fun openDrawer()
}

/**
 * Popup state (app drawer, context menu) plus the actions behind every taskbar
 * and desktop control. One per surface: the taskbar overlay or the home screen.
 */
class ShellController(
    private val context: Context,
    val desk: DeskState,
    val keys: SystemKeys?,
    /** Window control for desktop switching; the home screen borrows the overlay's. */
    private val windowKeys: () -> SystemKeys? = { keys },
) {
    var drawerOpen by mutableStateOf(false)
        private set
    var menu by mutableStateOf<MenuState?>(null)
        private set
    var switcherOpen by mutableStateOf(false)
        private set
    /** Desktop whose name is being edited in the switcher. */
    var renamingDesktop by mutableStateOf<Int?>(null)
        private set

    /** Called whenever a popup opens or closes, so an overlay can resize its window. */
    var onPopupsChanged: () -> Unit = {}

    /** Widget picker (home screen only). */
    var widgetPickerOpen by mutableStateOf(false)
        private set
    /** Widget showing resize handles. */
    var resizingWidget by mutableStateOf<Int?>(null)
        private set

    val hasPopup get() = drawerOpen || switcherOpen || menu != null

    fun toggleDrawer() {
        menu = null
        switcherOpen = false
        drawerOpen = !drawerOpen
        onPopupsChanged()
    }

    /**
     * Opens the drawer. From the home screen it goes to the taskbar overlay
     * when that runs, so it shows above app windows like the 9-dot button's.
     */
    fun openDrawer() {
        val overlay = windowKeys()
        if (keys == null && overlay != null) overlay.openDrawer()
        else if (!drawerOpen) toggleDrawer()
    }

    fun toggleSwitcher() {
        menu = null
        drawerOpen = false
        renamingDesktop = null
        switcherOpen = !switcherOpen
        onPopupsChanged()
    }

    fun showMenu(state: MenuState) {
        menu = state
        onPopupsChanged()
    }

    /** Closes just the context menu, leaving the drawer open. */
    fun closeMenu() {
        if (menu == null) return
        menu = null
        onPopupsChanged()
    }

    fun closePopups() {
        if (!hasPopup && !widgetPickerOpen && resizingWidget == null) return
        drawerOpen = false
        switcherOpen = false
        renamingDesktop = null
        menu = null
        widgetPickerOpen = false
        resizingWidget = null
        onPopupsChanged()
    }

    fun openWidgetPicker() {
        closePopups()
        widgetPickerOpen = true
    }

    fun closeWidgetPicker() {
        widgetPickerOpen = false
    }

    /** Long-press on a widget: Resize / Widget settings / Remove. */
    fun widgetMenu(id: Int, at: Offset, actions: WidgetActions) {
        val info = Widgets.info(id)
        showMenu(
            MenuState(
                at = at,
                groups = listOf(
                    listOfNotNull(
                        info?.takeIf { Widgets.canResize(it) }?.let { MenuItem("Resize") { startResize(id) } },
                        info?.takeIf { Widgets.isReconfigurable(it) }?.let { MenuItem("Widget settings") { actions.configureWidget(id) } },
                    ),
                    listOf(MenuItem("Remove") { actions.removeWidget(id) }),
                ),
            ),
        )
    }

    /** Long press on an app in the cover screen's app list. */
    fun coverAppMenu(app: AppEntry, at: Offset) {
        showMenu(
            MenuState(
                at = at,
                header = app,
                groups = listOf(
                    listOf(
                        if (desk.isInCoverDock(app)) {
                            MenuItem("Remove from dock") { desk.toggleCoverDock(app) }
                        } else {
                            val full = desk.coverDock.size >= COVER_DOCK_MAX
                            MenuItem("Add to dock", value = if (full) "Dock is full" else null, enabled = !full) { desk.toggleCoverDock(app) }
                        },
                        MenuItem(if (desk.isCoverFavourite(app)) "Remove from favourites" else "Add to favourites") {
                            desk.toggleCoverFavourite(app)
                        },
                        MenuItem("Hide from app list") { desk.updateHiddenApps(desk.hiddenApps + app.key) },
                    ),
                    listOf(
                        MenuItem("App info") { Launcher.appInfo(context, app) },
                        MenuItem("Uninstall") { Launcher.uninstall(context, app) },
                    ),
                ),
            ),
        )
    }

    /** Long press on a cover screen widget; dragging it moves it. */
    fun coverWidgetMenu(id: Int, at: Offset, actions: WidgetActions, onResize: () -> Unit) {
        val info = Widgets.info(id)
        showMenu(
            MenuState(
                at = at,
                groups = listOf(
                    listOfNotNull(
                        info?.takeIf { Widgets.canResize(it) }?.let { MenuItem("Resize") { onResize() } },
                        info?.takeIf { Widgets.isReconfigurable(it) }?.let { MenuItem("Widget settings") { actions.configureWidget(id) } },
                    ),
                    listOf(MenuItem("Remove") { actions.removeCoverWidget(id) }),
                ),
            ),
        )
    }

    private fun startResize(id: Int) {
        menu = null
        resizingWidget = id
    }

    fun endResize() {
        resizingWidget = null
    }

    fun open(app: AppEntry, mode: OpenMode = OpenMode.Default) {
        closePopups()
        // An app that's open on another desktop: go there, like DeX.
        val home = desk.desktopOf(app)
        if (windowKeys() != null && home != null && home != desk.currentDesktopId && desk.isOpen(app)) {
            switchDesktop(home)
            return
        }
        desk.assignApp(app.pkg, desk.currentDesktopId)
        when (mode) {
            OpenMode.Default -> Launcher.open(context, app, desk.openInWindow, desk.windowSizeFor(app))
            OpenMode.Window -> Launcher.open(context, app, true, desk.windowSizeFor(app))
            OpenMode.Fullscreen -> Launcher.open(context, app, false)
            // The taskbar can think an app is open after it was closed with Back
            // (nothing reports that), so a windowed app gets what a fresh window
            // needs; an open window keeps its place. Window options would turn a
            // full-screen app into a window, so those are only raised.
            OpenMode.Front ->
                if (desk.openInWindow && !desk.wasFullScreen(app.pkg)) {
                    Launcher.open(context, app, true, desk.windowSizeFor(app))
                } else {
                    Launcher.bringToFront(context, app)
                }
        }
    }

    fun appMenu(app: AppEntry, at: Offset, fromTaskbar: Boolean = false, fromDrawer: Boolean = false, desktopRows: Int = 6) {
        val placement = buildList {
            add(MenuItem(if (desk.isPinned(app)) "Unpin from taskbar" else "Pin to taskbar") { desk.togglePin(app) })
            if (desk.isOnDesktop(app)) add(MenuItem("Remove from desktop") { desk.removeFromDesktop(app) })
            else add(MenuItem("Add to desktop") { desk.addToDesktop(app, desktopRows) })
            if (fromTaskbar && !desk.isPinned(app)) add(MenuItem("Remove from taskbar") { desk.dismissOpen(app) })
            if (fromDrawer) add(MenuItem("Hide from drawer") { desk.updateHiddenApps(desk.hiddenApps + app.key) })
        }
        showMenu(
            MenuState(
                at = at,
                header = app,
                taskbarEdge = if (fromTaskbar) desk.taskbarSide else null,
                groups = listOf(
                    listOf(
                        MenuItem("Open") { open(app, OpenMode.Window) },
                        MenuItem("Open in full screen") { open(app, OpenMode.Fullscreen) },
                        MenuItem(
                            "Window size",
                            value = desk.appWindowSize(app)?.label ?: "Default",
                            submenu = windowSizeItems(app),
                        ),
                    ),
                    listOf(MenuItem("Move to desktop", submenu = moveToDesktopItems(app))),
                    placement,
                    listOf(
                        MenuItem("App info") { Launcher.appInfo(context, app) },
                        MenuItem("Uninstall") { Launcher.uninstall(context, app) },
                    ),
                ),
            ),
        )
    }

    private fun windowSizeItems(app: AppEntry): List<MenuItem> {
        val chosen = desk.appWindowSize(app)
        return listOf(MenuItem("Default", value = desk.windowSize.label, checked = chosen == null) {
            desk.updateAppWindowSize(app, null)
        }) + WindowSize.entries.map { size ->
            MenuItem(size.label, checked = chosen == size) { desk.updateAppWindowSize(app, size) }
        }
    }

    private fun moveToDesktopItems(app: AppEntry): List<MenuItem> {
        val current = desk.desktopOf(app) ?: desk.currentDesktopId
        return desk.desktops.map { d ->
            if (d.id == current) MenuItem("${d.name} (this)", enabled = false)
            else MenuItem(d.name) { moveAppToDesktop(app, d.id) }
        } + if (desk.canAddDesktop) {
            listOf(MenuItem("New desktop") { desk.addDesktop()?.let { moveAppToDesktop(app, it.id) } })
        } else {
            emptyList()
        }
    }

    // --- Virtual desktops ---

    /** Sends [app] to desktop [id]; if it's showing here, its window steps aside. */
    fun moveAppToDesktop(app: AppEntry, id: Int) {
        closePopups()
        val wasHere = (desk.desktopOf(app) ?: desk.currentDesktopId) == desk.currentDesktopId
        desk.assignApp(app.pkg, id)
        if (wasHere && desk.isOpen(app)) {
            windowKeys()?.minimizeApp(app)
            // It should be showing when you get to its new desktop.
            desk.addToWindowSnapshot(id, app.pkg)
        }
    }

    /** Hides this desktop's windows and brings back desktop [id]'s. */
    fun switchDesktop(id: Int) {
        closePopups()
        if (id == desk.currentDesktopId) return
        // Remember exactly which windows were up here, so coming back restores
        // those and not apps whose windows have since been closed.
        windowKeys()?.let { k -> desk.saveWindowSnapshot(desk.currentDesktopId, k.visibleApps()) }
        desk.beginSwitch()
        desk.setCurrentDesktop(id)
        val restore = desk.windowsToRestore(id)
        val k = windowKeys()
        if (k == null) desk.endSwitch() else k.switchWindows(restore) { finishSwitch(k, id) }
    }

    /**
     * Windows still on screen after a switch to [id] came along on purpose
     * (e.g. held in move mode while switching), so they now belong to [id].
     */
    private fun finishSwitch(k: SystemKeys, id: Int) {
        for (pkg in k.visibleApps()) desk.assignApp(pkg, id)
        desk.endSwitch()
    }

    /**
     * [pkg] (on desktop [id]) came to the front on its own, e.g. from Samsung's
     * minimised-apps bubble: go to its desktop and keep it in front.
     */
    fun followAppToDesktop(id: Int, pkg: String) {
        val k = windowKeys() ?: return
        val app = desk.appForPackage(pkg) ?: return
        closePopups()
        desk.saveWindowSnapshot(desk.currentDesktopId, k.visibleApps().filter { it != pkg })
        desk.beginSwitch()
        desk.setCurrentDesktop(id)
        desk.addToWindowSnapshot(id, pkg)
        k.switchWindows(desk.windowsToRestore(id).filter { it.pkg != pkg }, keep = app) { finishSwitch(k, id) }
    }

    /** Swipe on the desktop: [step] +1 for the next desktop, -1 for the previous. */
    fun swipeDesktop(step: Int) {
        val list = desk.desktops
        val index = list.indexOfFirst { it.id == desk.currentDesktopId } + step
        list.getOrNull(index)?.let { switchDesktop(it.id) }
    }

    /**
     * Long-press drag of [app] (from the taskbar, or the desktop shortcut
     * [iconKey]) shows the desktops; dropping on one moves the app there,
     * holding over one goes there with it.
     */
    fun dragToDesktops(app: AppEntry, at: Offset, showIcon: Boolean, iconKey: String? = null) {
        fun moveTo(target: Int): Int? {
            val id = if (target == DesktopDrag.NEW_DESKTOP) desk.addDesktop()?.id else target
            if (id == null || id == desk.currentDesktopId) return null
            iconKey?.let { desk.moveIconToDesktop(it, id) }
            if (desk.isOpen(app) || iconKey == null) moveAppToDesktop(app, id)
            return id
        }
        DesktopDrag.start(
            app, at, showIcon,
            onDrop = { moveTo(it) },
            onHold = { target -> moveTo(target)?.let { switchDesktop(it) } },
        )
    }

    fun newDesktop() {
        desk.addDesktop()?.let { switchDesktop(it.id) }
    }

    fun startRename(id: Int) {
        menu = null
        renamingDesktop = id
        onPopupsChanged()
    }

    fun finishRename(id: Int, name: String) {
        desk.renameDesktop(id, name)
        renamingDesktop = null
    }

    fun deleteDesktop(id: Int) {
        val wasCurrent = id == desk.currentDesktopId
        val k = windowKeys()
        val leaving = k?.visibleApps().orEmpty().mapNotNull { desk.appForPackage(it) }
        val heir = desk.deleteDesktop(id) ?: return
        menu = null
        // Deleting the desktop you're on: its windows stay, and the heir's come back too.
        if (wasCurrent && k != null) {
            desk.beginSwitch()
            k.switchWindows(desk.windowsToRestore(heir).filter { it !in leaving } + leaving) { desk.endSwitch() }
        }
        onPopupsChanged()
    }

    fun desktopCardMenu(desktopId: Int, at: Offset) {
        showMenu(
            MenuState(
                at = at,
                anchorBottom = desk.taskbarSide == TaskbarSide.Bottom,
                groups = listOf(
                    listOf(
                        MenuItem("Rename") { startRename(desktopId) },
                        MenuItem("Delete", enabled = desk.desktops.size > 1) { deleteDesktop(desktopId) },
                    ),
                ),
            ),
        )
    }

    fun openDeskSettings() {
        closePopups()
        Launcher.openDeskSettings(context)
    }

    // --- Configurable shortcuts (FoldDesk settings → Taskbar shortcuts) ---

    /** Swipe up or down on the desktop. */
    fun swipeAction(action: SwipeAction) {
        when (action) {
            SwipeAction.Drawer -> openDrawer()
            SwipeAction.Notifications -> showNotifications()
            SwipeAction.QuickSettings -> {
                closePopups()
                (keys ?: windowKeys())?.quickSettings() ?: Launcher.showQuickSettings(context)
            }
            SwipeAction.None -> Unit
        }
    }

    fun showNotifications() {
        closePopups()
        (keys ?: windowKeys())?.notifications() ?: Launcher.showNotifications(context)
    }

    fun statusTapped() {
        closePopups()
        when (desk.statusAction) {
            StatusAction.QuickSettings -> keys?.quickSettings() ?: Launcher.showQuickSettings(context)
            StatusAction.Battery -> Launcher.openBattery(context, desk.openInWindow)
            StatusAction.Connections -> Launcher.openConnections(context, desk.openInWindow)
        }
    }

    fun clockTapped() {
        closePopups()
        when (desk.clockAction) {
            ClockAction.Notifications -> keys?.notifications() ?: Launcher.showNotifications(context)
            ClockAction.Clock -> Launcher.openClock(context, desk.openInWindow)
            ClockAction.ShowDesktop -> keys?.showDesktop()
            ClockAction.Calendar -> Launcher.openCalendar(context, desk.openInWindow)
        }
    }

    fun desktopMenu(at: Offset) {
        showMenu(
            MenuState(
                at = at,
                groups = listOf(
                    listOf(
                        MenuItem("Widgets") { openWidgetPicker() },
                        MenuItem("Change wallpaper") { Launcher.openWallpaperPicker(context) },
                        MenuItem("Open apps in windows", checked = desk.openInWindow) {
                            desk.updateOpenInWindow(!desk.openInWindow)
                        },
                    ),
                    listOf(
                        MenuItem("FoldDesk settings") { Launcher.openDeskSettings(context) },
                        MenuItem("Settings") { Launcher.openSettings(context) },
                    ),
                ),
            ),
        )
    }

    /** Long press on the cover screen's empty space. */
    fun coverMenu(at: Offset) {
        showMenu(
            MenuState(
                at = at,
                groups = listOf(
                    listOf(MenuItem("Change wallpaper") { Launcher.openWallpaperPicker(context) }),
                    listOf(
                        MenuItem("FoldDesk settings") { Launcher.openDeskSettings(context) },
                        MenuItem("Settings") { Launcher.openSettings(context) },
                    ),
                ),
            ),
        )
    }

    companion object {
        private val all = mutableSetOf<ShellController>()

        fun register(shell: ShellController) = all.add(shell)
        fun unregister(shell: ShellController) = all.remove(shell)

        /** Home was pressed: close every drawer and menu, like DeX. */
        fun closeAll() = all.toList().forEach { it.closePopups() }
    }
}
