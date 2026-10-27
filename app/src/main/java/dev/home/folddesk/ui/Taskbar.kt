package dev.home.folddesk.ui

import dev.home.folddesk.TaskbarSide
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.home.folddesk.AppEntry
import dev.home.folddesk.DesktopDrag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import dev.home.folddesk.Launcher
import dev.home.folddesk.Network
import dev.home.folddesk.Placement
import dev.home.folddesk.OpenMode
import dev.home.folddesk.ShellController
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The DeX taskbar: Apps, Recents/Home/Back, pinned then open apps on the left;
 * notification icons, status and clock on the right.
 */
@Composable
fun Taskbar(shell: ShellController, modifier: Modifier = Modifier) {
    val desk = shell.desk
    val context = LocalContext.current
    val keys = shell.keys
    val side = desk.taskbarSide
    val vertical = side.vertical
    val open = desk.openApps.associateBy { it.app.pkg }
    val pinned = desk.pinned.mapNotNull { desk.app(it) }
    val pinnedPkgs = pinned.map { it.pkg }.toSet()
    val unpinnedOpen = desk.openApps.filter { it.app.pkg !in pinnedPkgs }

    val centered = desk.taskbarAlignment == Placement.Center
    val outline = Dex.outline
    Layout(
        modifier = modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(Dex.taskbarWidth) else Modifier.fillMaxWidth().height(Dex.taskbarHeight))
            .background(Dex.bar)
            // Hairline on the screen-facing edge; it separates the bar from light wallpapers.
            .drawBehind {
                val w = 1.dp.toPx()
                when (side) {
                    TaskbarSide.Bottom -> drawLine(outline, Offset.Zero, Offset(size.width, 0f), w)
                    TaskbarSide.Left -> drawLine(outline, Offset(size.width, 0f), Offset(size.width, size.height), w)
                    TaskbarSide.Right -> drawLine(outline, Offset.Zero, Offset(0f, size.height), w)
                }
            }
            .padding(if (vertical) PaddingValues(vertical = 6.dp) else PaddingValues(horizontal = 6.dp)),
        content = {
            AxisLine(vertical) {
                if (!centered) {
                    AppsKey(shell)
                    DesktopsKey(shell)
                }
                // With gesture navigation these duplicate the system gestures.
                if (keys != null && !desk.gestureNav) {
                    if (!centered) Separator(vertical)
                    Key(onClick = { shell.closePopups(); keys.recents() }) { Glyph(DexGlyph.Recents) }
                    Key(onClick = { shell.closePopups(); keys.home() }) { Glyph(DexGlyph.Home) }
                    Key(onClick = { if (shell.hasPopup) shell.closePopups() else keys.back() }) { Glyph(DexGlyph.Back) }
                }
                if (!centered) Separator(vertical)
            }
            AxisLine(vertical) {
                // Centred, the 9-dot button travels with the icons, like Windows 11's Start.
                if (centered) {
                    AppsKey(shell)
                    DesktopsKey(shell)
                    Separator(vertical)
                }
                val reorder = remember { TaskbarReorder() }
                val items = pinned.map { it to open[it.pkg] } + unpinnedOpen.map { it.app to it }
                val icons: @Composable () -> Unit = {
                    items.forEachIndexed { index, (app, state) ->
                        key(app.key) {
                            TaskbarApp(
                                shell, app, running = state != null, focused = state?.active == true,
                                index = index, pinnedCount = pinned.size, reorder = reorder, side = side,
                            )
                        }
                    }
                }
                if (vertical) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { icons() }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { icons() }
                }
            }
            val onNotifications = {
                shell.closePopups()
                keys?.notifications() ?: Launcher.showNotifications(context)
            }
            if (vertical) {
                TrayVertical(shell, onNotifications, onStatus = shell::statusTapped, onClock = shell::clockTapped)
            } else {
                Tray(shell, onNotifications, onStatus = shell::statusTapped, onClock = shell::clockTapped)
            }
        },
    ) { measurables, constraints ->
        // Keys at the start, tray at the end; app icons get what's left and
        // scroll when they don't fit, so the tray is never squeezed.
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = measurables[0].measure(loose)
        val end = measurables[2].measure(loose)
        fun main(p: androidx.compose.ui.layout.Placeable) = if (vertical) p.height else p.width
        val length = if (vertical) constraints.maxHeight else constraints.maxWidth
        val room = (length - main(start) - main(end)).coerceAtLeast(0)
        val apps = measurables[1].measure(if (vertical) loose.copy(maxHeight = room) else loose.copy(maxWidth = room))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Centred on the screen when there's room, otherwise as close as it fits.
            val appsAt = if (centered) {
                ((length - main(apps)) / 2).coerceIn(main(start), length - main(end) - main(apps))
            } else {
                main(start)
            }
            if (vertical) {
                fun x(w: Int) = (constraints.maxWidth - w) / 2
                start.place(x(start.width), 0)
                end.place(x(end.width), length - end.height)
                apps.place(x(apps.width), appsAt)
            } else {
                fun y(h: Int) = (constraints.maxHeight - h) / 2
                start.place(0, y(start.height))
                end.place(length - end.width, y(end.height))
                apps.place(appsAt, y(apps.height))
            }
        }
    }
}

/** A Row on a bottom taskbar, a Column on a side one. */
@Composable
private fun AxisLine(vertical: Boolean, content: @Composable () -> Unit) {
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
private fun AppsKey(shell: ShellController) {
    Key(selected = shell.drawerOpen, onClick = shell::toggleDrawer) { Glyph(DexGlyph.Apps) }
}

/** Opens the desktop switcher; the badge shows which desktop you're on. */
@Composable
private fun DesktopsKey(shell: ShellController) {
    val desk = shell.desk
    val number = desk.desktops.indexOfFirst { it.id == desk.currentDesktopId } + 1
    Key(selected = shell.switcherOpen, onClick = shell::toggleSwitcher) {
        Box(Modifier.size(width = 30.dp, height = 26.dp)) {
            Box(Modifier.align(Alignment.Center)) { Glyph(DexGlyph.Desktops) }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Dex.accent)
                    .padding(horizontal = 4.dp),
            ) {
                BasicText(
                    number.toString(),
                    style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp),
                )
            }
        }
    }
}

@Composable
private fun TaskbarApp(
    shell: ShellController,
    app: AppEntry,
    running: Boolean,
    focused: Boolean,
    index: Int,
    pinnedCount: Int,
    reorder: TaskbarReorder,
    side: TaskbarSide,
) {
    val vertical = side.vertical
    val feedback = remember { PressFeedback() }
    val view = LocalView.current
    val density = LocalDensity.current
    val slot = with(density) { TASKBAR_SLOT.toPx() }
    val leaveUp = with(density) { 48.dp.toPx() }
    // Where the long press landed, on screen; a drag carries on from there.
    var finger by remember { mutableStateOf(Offset.Zero) }
    val dragging = reorder.key == app.key
    // Others slide aside to open a gap at the drop spot.
    // Along the bar: x on the bottom, y on a side.
    val shift by animateFloatAsState(
        if (dragging) 0f else reorder.shiftFor(index) * slot,
        animationSpec = tween(150),
        label = "taskbarShift",
    )
    val outline = Dex.accent
    HoverBox(
        selected = focused || feedback.pressed || dragging,
        selectedColor = if (feedback.lifted || dragging) Dex.pressed else Dex.hover,
        modifier = Modifier
            .zIndex(if (dragging) 1f else 0f)
            .then(
                if (dragging) Modifier.drawBehind {
                    // Dashed outline where the icon will land.
                    val along = (reorder.target - index) * slot
                    drawRoundRect(
                        outline.copy(alpha = 0.7f), if (vertical) Offset(0f, along) else Offset(along, 0f), size, CornerRadius(10.dp.toPx()),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                } else Modifier,
            )
            .offset {
                val along = (if (dragging) reorder.along else shift).toInt()
                if (vertical) IntOffset(0, along) else IntOffset(along, 0)
            }
            .lifted(feedback.lifted || dragging, RoundedCornerShape(8.dp))
            .size(width = 44.dp, height = 40.dp)
            .deskGestures(
                onTap = { shell.open(app, if (running) OpenMode.Front else OpenMode.Default) },
                onMenu = {
                    val loc = IntArray(2).also { l -> view.getLocationOnScreen(l) }
                    finger = it + Offset(loc[0].toFloat(), loc[1].toFloat())
                    shell.appMenu(app, finger, fromTaskbar = true)
                },
                feedback = feedback,
                // Sideways: reorder on the taskbar. Upwards: off to the desktops strip.
                onDragStart = {
                    shell.closeMenu()
                    reorder.start(app.key, index, pinnedCount, isPinned = index < pinnedCount)
                },
                onDrag = { delta ->
                    finger += delta
                    if (reorder.key == app.key) {
                        reorder.along += if (vertical) delta.y else delta.x
                        reorder.away += when (side) {
                            TaskbarSide.Bottom -> -delta.y
                            TaskbarSide.Left -> delta.x
                            TaskbarSide.Right -> -delta.x
                        }
                        // Dragged away from the bar: off to the desktops strip.
                        if (reorder.away > leaveUp && reorder.away > kotlin.math.abs(reorder.along)) {
                            reorder.cancel()
                            shell.dragToDesktops(app, finger, showIcon = true)
                        } else if (reorder.retarget(slot)) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    } else {
                        DesktopDrag.moveTo(finger)
                    }
                },
                onDragEnd = {
                    if (reorder.key == app.key) {
                        val target = reorder.target
                        reorder.cancel()
                        when {
                            index < pinnedCount -> if (target != index) shell.desk.movePin(app.key, target)
                            target < pinnedCount -> shell.desk.pinAt(app, target)
                        }
                    } else {
                        DesktopDrag.release()
                    }
                },
            ),
    ) {
        Image(app.icon, contentDescription = app.label, modifier = Modifier.size(32.dp))
        if (running) {
            // On the edge facing the screen.
            val long = if (focused) 14.dp else 4.dp
            Box(
                Modifier
                    .align(
                        when (side) {
                            TaskbarSide.Bottom -> Alignment.BottomCenter
                            TaskbarSide.Left -> Alignment.CenterEnd
                            TaskbarSide.Right -> Alignment.CenterStart
                        },
                    )
                    .padding(2.dp)
                    .size(width = if (vertical) 4.dp else long, height = if (vertical) long else 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (focused) Dex.accent else Dex.subtle),
            )
        }
    }
}

private val TASKBAR_SLOT = 46.dp // 44dp icon + 2dp spacing

/** An icon being dragged along the taskbar to a new position. */
private class TaskbarReorder {
    var key by mutableStateOf<String?>(null)
        private set
    /** Drag distance along the bar, and away from it towards the screen. */
    var along by mutableStateOf(0f)
    var away = 0f
    var target by mutableStateOf(0)
        private set
    private var from = 0
    private var maxTarget = 0

    fun start(key: String, index: Int, pinnedCount: Int, isPinned: Boolean) {
        this.key = key
        from = index
        target = index
        along = 0f
        away = 0f
        // Pinned apps move within the pinned ones; an open app can only move
        // left into them (which pins it); the open section keeps opening order.
        maxTarget = if (isPinned) pinnedCount - 1 else index
    }

    /** Updates the drop slot from the drag distance; true if it changed. */
    fun retarget(slot: Float): Boolean {
        val next = (from + kotlin.math.round(along / slot).toInt()).coerceIn(0, maxTarget)
        if (next == target) return false
        target = next
        return true
    }

    fun cancel() {
        key = null
        along = 0f
    }

    /** How many slots the icon at [index] steps aside for the dragged one. */
    fun shiftFor(index: Int): Int = when {
        key == null -> 0
        from < target && index in (from + 1)..target -> -1
        target < from && index in target until from -> 1
        else -> 0
    }
}

@Composable
private fun Tray(shell: ShellController, onNotifications: () -> Unit, onStatus: () -> Unit, onClock: () -> Unit) {
    val desk = shell.desk
    val notifying = desk.notificationPackages.mapNotNull { desk.appForPackage(it) }.take(4)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (notifying.isNotEmpty()) {
            HoverBox(modifier = Modifier.height(36.dp).deskGestures(onTap = onNotifications)) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (app in notifying) Image(app.icon, contentDescription = app.label, modifier = Modifier.size(20.dp))
                }
            }
        }
        HoverBox(modifier = Modifier.height(36.dp).deskGestures(onTap = onStatus)) {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (desk.network) {
                    Network.Wifi -> Glyph(DexGlyph.Wifi, 17.dp)
                    Network.Cellular -> Glyph(DexGlyph.Cellular, 15.dp)
                    else -> Unit
                }
                desk.batteryLevel?.let { level ->
                    BasicText("$level%", style = TextStyle(color = Dex.text, fontSize = 12.5.sp))
                    BatteryGlyph(level, desk.charging)
                }
            }
        }
        HoverBox(modifier = Modifier.height(36.dp).deskGestures(onTap = onClock)) {
            Clock(Modifier.padding(start = 6.dp, end = 10.dp))
        }
    }
}

/** The tray stacked for a side taskbar: notifications, status, then time and date. */
@Composable
private fun TrayVertical(shell: ShellController, onNotifications: () -> Unit, onStatus: () -> Unit, onClock: () -> Unit) {
    val desk = shell.desk
    val notifying = desk.notificationPackages.mapNotNull { desk.appForPackage(it) }.take(3)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (notifying.isNotEmpty()) {
            HoverBox(modifier = Modifier.width(44.dp).deskGestures(onTap = onNotifications)) {
                Column(
                    Modifier.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for (app in notifying) Image(app.icon, contentDescription = app.label, modifier = Modifier.size(20.dp))
                }
            }
        }
        HoverBox(modifier = Modifier.width(44.dp).deskGestures(onTap = onStatus)) {
            Column(
                Modifier.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (desk.network) {
                    Network.Wifi -> Glyph(DexGlyph.Wifi, 17.dp)
                    Network.Cellular -> Glyph(DexGlyph.Cellular, 15.dp)
                    else -> Unit
                }
                desk.batteryLevel?.let { level ->
                    BatteryGlyph(level, desk.charging)
                    BasicText("$level%", style = TextStyle(color = Dex.text, fontSize = 11.sp))
                }
            }
        }
        HoverBox(modifier = Modifier.width(48.dp).deskGestures(onTap = onClock)) {
            ClockStacked(Modifier.padding(vertical = 6.dp))
        }
    }
}

/** Time over a two-line short date, for the narrow side taskbar. */
@Composable
private fun ClockStacked(modifier: Modifier) {
    val context = LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val dateFormat = remember {
        val locale = Locale.getDefault()
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(timeFormat.format(now), softWrap = false, style = TextStyle(color = Dex.text, fontSize = 12.sp))
        BasicText(dayFormat.format(now), softWrap = false, style = TextStyle(color = Dex.date, fontSize = 10.sp))
        BasicText(dateFormat.format(now), softWrap = false, style = TextStyle(color = Dex.date, fontSize = 10.sp))
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val dateFormat = remember {
        val locale = Locale.getDefault()
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        BasicText(timeFormat.format(now), softWrap = false, style = TextStyle(color = Dex.text, fontSize = 12.5.sp))
        BasicText(dateFormat.format(now), softWrap = false, style = TextStyle(color = Dex.date, fontSize = 11.sp))
    }
}

@Composable
private fun Key(selected: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    HoverBox(
        selected = selected,
        selectedColor = Dex.pressed,
        modifier = Modifier.size(width = 44.dp, height = 40.dp).deskGestures(onTap = onClick),
    ) { content() }
}

@Composable
private fun Separator(vertical: Boolean = false) {
    if (vertical) {
        Box(Modifier.padding(vertical = 6.dp).size(width = 22.dp, height = 1.dp).background(Dex.separator))
    } else {
        Box(Modifier.padding(horizontal = 6.dp).size(width = 1.dp, height = 22.dp).background(Dex.separator))
    }
}

/** Rounded hit target that highlights on hover (mouse) or when selected. */
@Composable
fun HoverBox(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color = Dex.hover,
    corner: Int = 8,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(
                when {
                    selected -> selectedColor
                    hovered -> Dex.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction),
        contentAlignment = Alignment.Center,
        content = content,
    )
}
