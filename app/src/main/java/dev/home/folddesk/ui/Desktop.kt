package dev.home.folddesk.ui

import androidx.compose.foundation.layout.PaddingValues
import dev.home.folddesk.Widgets
import dev.home.folddesk.WidgetActions
import dev.home.folddesk.DeskWidgetView
import dev.home.folddesk.DeskWidget
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Image
import dev.home.folddesk.Launcher
import androidx.compose.foundation.layout.Column
import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.layout.fillMaxHeight
import dev.home.folddesk.TaskbarSide
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.Icons
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.home.folddesk.Cell
import dev.home.folddesk.DesktopDrag
import dev.home.folddesk.ShellController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The home screen: wallpaper (drawn by the system) and desktop shortcuts. When
 * the taskbar overlay isn't running, the taskbar and its popups live here too.
 */
@Composable
fun DesktopScreen(shell: ShellController, showTaskbar: Boolean, widgetActions: WidgetActions? = null) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Space the taskbar takes on each edge.
    val side = shell.desk.taskbarSide
    val size = shell.desk.taskbarSize
    val aboveTaskbar = if (side == TaskbarSide.Bottom) size.bar.dp + navBottom else navBottom
    val barStart = if (side == TaskbarSide.Left) size.sideBar.dp else 0.dp
    val barEnd = if (side == TaskbarSide.Right) size.sideBar.dp else 0.dp
    var rows by remember { mutableIntStateOf(6) }
    // Drag-to-remove: the pill shows while an icon is dragged.
    var dragging by remember { mutableStateOf(false) }
    var overRemove by remember { mutableStateOf(false) }
    var removeZone by remember { mutableStateOf(Rect.Zero) }

    // Keep the taskbar's open-app list current while the desktop is visible.
    LaunchedEffect(Unit) {
        while (true) {
            shell.desk.refreshOpenApps()
            delay(2000)
        }
    }

    // The Today page, left of the first desktop: 0 hidden, 1 open. It follows the finger.
    val page = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var pageWidth by remember { mutableFloatStateOf(1f) }
    val canOpenPage by rememberUpdatedState(
        shell.desk.todayPage && shell.desk.desktops.firstOrNull()?.id == shell.desk.currentDesktopId && !shell.hasPopup,
    )
    LaunchedEffect(shell.todayOpen) { page.animateTo(if (shell.todayOpen) 1f else 0f, tween(PAGE_MS)) }
    val dragPage: (Float) -> Unit = { dx -> scope.launch { page.snapTo((page.value + dx / pageWidth).coerceIn(0f, 1f)) } }
    // Past halfway, or flung, it opens; otherwise it goes back.
    val releasePage: (Float) -> Unit = { velocity ->
        val open = if (abs(velocity) > FLING_PX_PER_S) velocity > 0 else page.value > 0.5f
        shell.todayOpen = open
        scope.launch { page.animateTo(if (open) 1f else 0f, tween(PAGE_MS)) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { pageWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pageDrag(direction = 1, enabled = { canOpenPage }, onDrag = dragPage, onRelease = releasePage)
            .deskGestures(
                onTap = shell::closePopups,
                onMenu = shell::desktopMenu,
                // Left/right: next/previous desktop. Up/down: whatever settings say.
                onSwipe = { d ->
                    when {
                        kotlin.math.abs(d.x) > kotlin.math.abs(d.y) -> shell.swipeSideways(if (d.x < 0) 1 else -1)
                        d.y < 0 -> shell.swipeAction(shell.desk.swipeUp)
                        else -> shell.swipeAction(shell.desk.swipeDown)
                    }
                },
            ),
    ) {
        // Drawn before the icons so the dragged icon passes over it.
        if (dragging) {
            RemoveZone(
                active = overRemove,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + 18.dp)
                    .onGloballyPositioned { removeZone = it.boundsInRoot() },
            )
        }
        // Switching desktops slides the icons: next desktop comes in from the right.
        val order = shell.desk.desktops.map { it.id }
        AnimatedContent(
            targetState = shell.desk.currentDesktopId,
            transitionSpec = {
                val dir = if (order.indexOf(targetState) >= order.indexOf(initialState)) 1 else -1
                (slideInHorizontally(tween(SWITCH_MS)) { it * dir / 6 } + fadeIn(tween(SWITCH_MS))) togetherWith
                    (slideOutHorizontally(tween(SWITCH_MS)) { -it * dir / 6 } + fadeOut(tween(SWITCH_MS)))
            },
            label = "desktopSwitch",
        ) { desktopId ->
        DesktopIcons(
            shell,
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = page.value * this.size.width }
                .padding(top = statusTop + 14.dp, bottom = aboveTaskbar + 8.dp, start = barStart + 10.dp, end = barEnd + 10.dp),
            desktopId = desktopId,
            widgetActions = widgetActions,
            rows = rows,
            onRows = { rows = it },
            onDragging = {
                dragging = it
                if (!it) overRemove = false
            },
            onDragMoved = { iconBounds -> overRemove = iconBounds.overlaps(removeZone) },
            removeOnDrop = { overRemove },
        )
        }
        if (page.value > 0f || shell.todayOpen) {
            TodayPage(
                shell, widgetActions,
                progress = { page.value },
                onDrag = dragPage,
                onRelease = releasePage,
                contentPadding = PaddingValues(top = statusTop + 18.dp, bottom = aboveTaskbar + 18.dp, start = barStart + 16.dp, end = barEnd + 16.dp),
            )
        }
        if (showTaskbar) {
            TaskbarSurface(
                shell,
                if (side.vertical) PaddingValues(top = statusTop, bottom = navBottom) else PaddingValues(bottom = navBottom),
                when (side) {
                    TaskbarSide.Bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    TaskbarSide.Left -> Modifier.align(Alignment.CenterStart).fillMaxHeight()
                    TaskbarSide.Right -> Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                },
            )
        }
        // With the taskbar overlay running, it shows the drag targets above app windows.
        PopupLayer(
            shell,
            Modifier.padding(top = statusTop, bottom = aboveTaskbar, start = barStart, end = barEnd),
            dragTargets = showTaskbar,
        )
        DesktopPill(shell, Modifier.align(Alignment.TopCenter).padding(top = statusTop + 18.dp))
        // Not the default home app (e.g. just installed from Play): Home and
        // unfolding would go elsewhere, so ask first.
        val context = LocalContext.current
        var isHome by remember { mutableStateOf(Launcher.isDefaultHome(context)) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isHome = Launcher.isDefaultHome(context) }
        val requestHome = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isHome = Launcher.isDefaultHome(context)
        }
        val homeNotice = !isHome && !shell.desk.homeNoticeDismissed
        // Notices wait while the drawer or a menu is open; they'd sit on top of it.
        if (homeNotice && !shell.hasPopup) {
            HomeNotice(
                onSetHome = { requestHome.launch(Launcher.requestHomeRole(context)) },
                onDismiss = { shell.desk.homeNoticeDismissed = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = aboveTaskbar + 16.dp, start = barStart, end = barEnd),
            )
        }
        // The fallback taskbar is showing, so the service is off: say so.
        val turnOnTaskbar = rememberTurnOnTaskbar()
        var noticeDismissed by remember { mutableStateOf(false) }
        LaunchedEffect(showTaskbar) { if (!showTaskbar) noticeDismissed = false }
        if (showTaskbar && !noticeDismissed && !homeNotice && !shell.hasPopup) {
            ServiceOffNotice(
                onTurnOn = turnOnTaskbar,
                onDismiss = { noticeDismissed = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = aboveTaskbar + 16.dp, start = barStart, end = barEnd),
            )
        }
        if (widgetActions != null) WidgetPickerLayer(shell, widgetActions, Modifier.padding(top = statusTop, bottom = aboveTaskbar, start = barStart, end = barEnd))
    }
}

/** Asks to become the default home app, through Android's own dialog. */
@Composable
private fun HomeNotice(onSetHome: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    Panel(22.dp, modifier.widthIn(max = 560.dp)) {
        Row(
            Modifier.deskGestures().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Dex.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(Icons.Default.Home, contentDescription = null, colorFilter = ColorFilter.tint(Dex.accent), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText("FoldDesk isn't your home app yet", style = TextStyle(color = Dex.text, fontSize = 14.sp))
                BasicText(
                    "Set it so Home and unfolding bring you back here. The cover screen stays as it is.",
                    style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Dex.accent)
                    .deskGestures(onTap = { onSetHome() }).padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                BasicText("Set as home", style = TextStyle(color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
            }
            Box(Modifier.clip(CircleShape).deskGestures(onTap = { onDismiss() }).padding(10.dp)) {
                BasicText("✕", style = TextStyle(color = Dex.subtle, fontSize = 16.sp))
            }
        }
    }
}

/** Warns that the taskbar accessibility service is off, with a way to turn it on. */
@Composable
private fun ServiceOffNotice(onTurnOn: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    Panel(22.dp, modifier.widthIn(max = 560.dp)) {
        Row(
            Modifier.deskGestures().padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Dex.warning.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("!", style = TextStyle(color = Dex.warning, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText("The FoldDesk taskbar is turned off", style = TextStyle(color = Dex.text, fontSize = 14.sp))
                BasicText(
                    "Without it the app drawer opens behind apps and unfolding won't bring you back here",
                    style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.clip(RoundedCornerShape(18.dp)).background(Dex.accent)
                    .deskGestures(onTap = { onTurnOn() }).padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                BasicText("Turn on", style = TextStyle(color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold))
            }
            Box(Modifier.clip(CircleShape).deskGestures(onTap = { onDismiss() }).padding(10.dp)) {
                BasicText("✕", style = TextStyle(color = Dex.subtle, fontSize = 16.sp))
            }
        }
    }
}

/** Shows the desktop's name and position for a moment after each switch. */
@Composable
private fun DesktopPill(shell: ShellController, modifier: Modifier) {
    val desk = shell.desk
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(desk.currentDesktopId) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        visible = true
        delay(1300)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Row(
            Modifier
                .shadow(10.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Dex.panel)
                .height(44.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(desk.currentDesktop.name, style = TextStyle(color = Dex.text, fontSize = 14.sp))
            Spacer(Modifier.width(12.dp))
            for (d in desk.desktops) {
                val on = d.id == desk.currentDesktopId
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (on) 18.dp else 7.dp, height = 7.dp)
                        .clip(CircleShape)
                        .background(if (on) Dex.accent else Dex.separator),
                )
            }
        }
    }
}

private const val SWITCH_MS = 320
private const val PAGE_MS = 260
private const val FLING_PX_PER_S = 1200f

/** The "Remove from desktop" pill; turns red while an icon hovers over it. */
@Composable
private fun RemoveZone(active: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .graphicsLayer {
                val scale = if (active) 1.06f else 1f
                scaleX = scale
                scaleY = scale
            }
            .shadow(14.dp, shape)
            .clip(shape)
            .background(if (active) Color(0xFFD93A3A) else Dex.panel)
            .border(1.dp, if (active) Color.Transparent else Dex.outline, shape)
            .height(52.dp)
            .padding(start = 20.dp, end = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (active) Color.White else Dex.text
        Glyph(DexGlyph.Trash, 20.dp, ink)
        Spacer(Modifier.width(10.dp))
        BasicText("Remove from desktop", style = TextStyle(color = ink, fontSize = 14.sp))
    }
}

@Composable
private fun DesktopIcons(
    shell: ShellController,
    modifier: Modifier,
    desktopId: Int,
    widgetActions: WidgetActions?,
    rows: Int,
    onRows: (Int) -> Unit,
    onDragging: (Boolean) -> Unit,
    onDragMoved: (Rect) -> Unit,
    removeOnDrop: () -> Boolean,
) {
    val desk = shell.desk
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // Icons tapped with another finger during a drag ride along, in tap order.
    val group = remember { mutableStateListOf<String>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val view = LocalView.current
    // Finger position (this window's coordinates) during an icon drag.
    var finger by remember { mutableStateOf(Offset.Zero) }
    fun screenOffset(): Offset {
        val loc = IntArray(2).also { view.getLocationOnScreen(it) }
        return Offset(loc[0].toFloat(), loc[1].toFloat())
    }

    BoxWithConstraints(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        val cols = (maxWidth / Dex.desktopCellWidth).toInt().coerceAtLeast(1)
        val fitRows = (maxHeight / Dex.desktopCellHeight).toInt().coerceAtLeast(1)
        LaunchedEffect(fitRows) { onRows(fitRows) }
        val cellW = with(density) { Dex.desktopCellWidth.toPx() }
        val cellH = with(density) { Dex.desktopCellHeight.toPx() }
        val stackStep = with(density) { Offset(8.dp.toPx(), -6.dp.toPx()) }
        // What fits this orientation; icons saved off-grid are shown nearby.
        val current = desktopId == desk.currentDesktopId
        val layout = if (current) desk.desktopLayout(cols, fitRows) else desk.desktopLayout(cols, fitRows, desk.iconsOf(desktopId), emptySet())
        if (current) {
            SideEffect {
                DesktopGrid.cols = cols
                DesktopGrid.rows = fitRows
                DesktopGrid.origin = origin
                DesktopGrid.cellW = cellW
                DesktopGrid.cellH = cellH
                DesktopGrid.layout = layout
            }
            // Widgets sit behind the icons. (A desktop sliding away shows only its icons.)
            if (widgetActions != null) {
                for (w in desk.widgets) {
                    key(w.id) { DeskWidgetItem(shell, w, cellW, cellH, cols, fitRows, layout, widgetActions) }
                }
            }
        }
        val leaderPos = dragKey?.let { layout[it] }
            ?.let { Offset(it.col * cellW, it.row * cellH) + dragOffset }

        fun endDrag() {
            dragKey = null
            dragOffset = Offset.Zero
            group.clear()
            onDragging(false)
        }

        for ((key, cell) in layout) {
            val app = desk.app(key) ?: continue
            // Per-icon state (press feedback, join animation) must follow the icon.
            androidx.compose.runtime.key(key) {
            val leading = key == dragKey
            val groupIndex = group.indexOf(key)
            val riding = groupIndex >= 0 && leaderPos != null
            val base = Offset(cell.col * cellW, cell.row * cellH)

            // Riders fly from their spot into the stack under the finger.
            val join = remember(key) { Animatable(0f) }
            LaunchedEffect(riding) { if (riding) join.animateTo(1f, tween(180)) else join.snapTo(0f) }

            if (leading || riding) {
                // A faded copy marks where the icon came from.
                AppTile(
                    app, onWallpaper = true,
                    modifier = Modifier
                        .offset { IntOffset(base.x.roundToInt(), base.y.roundToInt()) }
                        .width(Dex.desktopCellWidth)
                        .alpha(0.35f),
                )
            }
            val pos = when {
                leading -> base + dragOffset
                riding -> lerp(base, leaderPos!! + stackStep * (groupIndex + 1).toFloat(), join.value)
                else -> base
            }
            val feedback = remember(key) { PressFeedback() }
            AppTile(
                app = app,
                onWallpaper = true,
                showLabel = !riding,
                selected = leading || riding || shell.menu?.header == app,
                feedback = feedback,
                modifier = Modifier
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .zIndex(if (leading) 2f else if (riding) 1.9f - groupIndex * 0.01f else 0f)
                    .lifted(leading || riding, RoundedCornerShape(12.dp))
                    .width(Dex.desktopCellWidth)
                    .deskGestures(
                        onTap = {
                            if (dragKey != null && !leading) {
                                // Second-finger tap during a drag: join (or leave) the group.
                                if (!group.remove(key)) group.add(key)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            } else {
                                shell.open(app)
                            }
                        },
                        onMenu = {
                            finger = it
                            if (dragKey == null) shell.appMenu(app, it, desktopRows = rows)
                        },
                        feedback = feedback,
                        onDragStart = {
                            if (dragKey == null) {
                                shell.closePopups()
                                dragKey = key
                                dragOffset = Offset.Zero
                                onDragging(true)
                                // Desktops appear too; dropping on one moves the app there.
                                shell.dragToDesktops(app, finger + screenOffset(), showIcon = false, iconKey = key)
                            }
                        },
                        onDrag = {
                            if (dragKey == key) {
                                dragOffset += it
                                finger += it
                                onDragMoved(Rect(origin + base + dragOffset, Size(cellW, cellH)))
                                DesktopDrag.moveTo(finger + screenOffset())
                                // Held over a desktop: the app went there and so did we.
                                if (!DesktopDrag.active) endDrag()
                            }
                        },
                        onDragEnd = {
                            if (dragKey == key && DesktopDrag.active && DesktopDrag.hovered != null) {
                                DesktopDrag.release()
                                group.clear()
                                endDrag()
                            } else if (dragKey == key) {
                                DesktopDrag.finish()
                                val keys = listOf(key) + group
                                val drop = base + dragOffset
                                val target = Cell(
                                    (drop.x / cellW).roundToInt().coerceIn(0, cols - 1),
                                    (drop.y / cellH).roundToInt().coerceIn(0, fitRows - 1),
                                )
                                when {
                                    removeOnDrop() -> desk.removeFromDesktop(keys)
                                    group.isEmpty() -> desk.moveOnDesktop(key, target, layout)
                                    else -> desk.moveGroupOnDesktop(keys, target, fitRows, layout)
                                }
                                endDrag()
                            }
                        },
                    ),
            )
            if (leading && group.isNotEmpty()) {
                GroupBadge(
                    count = group.size + 1,
                    modifier = Modifier
                        .offset { IntOffset((pos.x + cellW).roundToInt(), pos.y.roundToInt()) }
                        .offset(x = (-26).dp, y = (-6).dp)
                        .zIndex(3f),
                )
            }
            }
        }
    }
}

@Composable
private fun GroupBadge(count: Int, modifier: Modifier) {
    Box(
        modifier
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(Dex.accent)
            .heightIn(min = 24.dp)
            .widthIn(min = 24.dp)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            count.toString(),
            style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = a + (b - a) * t

// --- Widgets ---

/**
 * A widget on the grid. Its own taps and scrolls work as usual; a long press
 * opens FoldDesk's menu, and dragging on from there moves it.
 */
@Composable
private fun DeskWidgetItem(
    shell: ShellController,
    widget: DeskWidget,
    cellW: Float,
    cellH: Float,
    cols: Int,
    rows: Int,
    layout: Map<String, Cell>,
    actions: WidgetActions,
) {
    val desk = shell.desk
    val density = LocalDensity.current
    val info = remember(widget.id) { Widgets.info(widget.id) } ?: return
    var drag by remember { mutableStateOf<Offset?>(null) }
    val base = Offset(widget.cell.col * cellW, widget.cell.row * cellH)
    val pos = base + (drag ?: Offset.Zero)
    val wPx = widget.w * cellW
    val hPx = widget.h * cellH
    val inset = with(density) { 6.dp.toPx() }
    val latest by rememberUpdatedState(widget)
    val latestLayout by rememberUpdatedState(layout)

    AndroidView(
        factory = { ctx -> Widgets.host.createView(ctx, widget.id, info) },
        update = { view ->
            val v = view as? DeskWidgetView ?: return@AndroidView
            v.onLongPress = { x, y -> shell.widgetMenu(latest.id, Offset(x, y), actions) }
            v.onDragStart = {
                shell.closeMenu()
                drag = Offset.Zero
            }
            v.onDrag = { dx, dy -> drag = (drag ?: Offset.Zero) + Offset(dx, dy) }
            v.onDragEnd = {
                val w = latest
                val at = Offset(w.cell.col * cellW, w.cell.row * cellH) + (drag ?: Offset.Zero)
                val target = Cell(kotlin.math.round(at.x / cellW).toInt(), kotlin.math.round(at.y / cellH).toInt())
                if (desk.areaFree(target, w.w, w.h, cols, rows, latestLayout, except = w.id)) desk.updateWidget(w.copy(cell = target))
                drag = null
            }
            with(density) { Widgets.reportSize(v, (wPx - 2 * inset).toDp().value, (hPx - 2 * inset).toDp().value) }
        },
        modifier = Modifier
            .offset { IntOffset((pos.x + inset).roundToInt(), (pos.y + inset).roundToInt()) }
            .zIndex(if (drag != null) 1f else 0f)
            .lifted(drag != null, RoundedCornerShape(20.dp))
            .size(with(density) { (wPx - 2 * inset).toDp() }, with(density) { (hPx - 2 * inset).toDp() }),
    )

    if (shell.resizingWidget == widget.id) {
        WidgetResizeFrame(shell, widget, info, cellW, cellH, cols, rows, layout)
    }
}

/**
 * Resize mode: an outline with handles on the edges the widget allows; edges
 * snap to the grid and a badge shows the size.
 */
@Composable
private fun WidgetResizeFrame(
    shell: ShellController,
    widget: DeskWidget,
    info: AppWidgetProviderInfo,
    cellW: Float,
    cellH: Float,
    cols: Int,
    rows: Int,
    layout: Map<String, Cell>,
) {
    val desk = shell.desk
    val context = LocalContext.current
    val density = LocalDensity.current
    val (minW, minH) = remember(info) { Widgets.minSpan(info, context, Dex.desktopCellWidth.value, Dex.desktopCellHeight.value) }
    // Edges in px, while dragging.
    var left by remember(widget) { mutableStateOf(widget.cell.col * cellW) }
    var top by remember(widget) { mutableStateOf(widget.cell.row * cellH) }
    var right by remember(widget) { mutableStateOf((widget.cell.col + widget.w) * cellW) }
    var bottom by remember(widget) { mutableStateOf((widget.cell.row + widget.h) * cellH) }

    fun snapped(): DeskWidget {
        val c0 = kotlin.math.round(left / cellW).toInt().coerceIn(0, cols - 1)
        val r0 = kotlin.math.round(top / cellH).toInt().coerceIn(0, rows - 1)
        val c1 = kotlin.math.round(right / cellW).toInt().coerceIn(c0 + 1, cols)
        val r1 = kotlin.math.round(bottom / cellH).toInt().coerceIn(r0 + 1, rows)
        return widget.copy(cell = Cell(c0, r0), w = (c1 - c0).coerceAtLeast(minW), h = (r1 - r0).coerceAtLeast(minH))
    }

    fun commit() {
        val next = snapped()
        if (desk.areaFree(next.cell, next.w, next.h, cols, rows, layout, except = widget.id)) desk.updateWidget(next)
        else {
            left = widget.cell.col * cellW; top = widget.cell.row * cellH
            right = (widget.cell.col + widget.w) * cellW; bottom = (widget.cell.row + widget.h) * cellH
        }
    }

    val accent = Dex.accent
    val preview = snapped()
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
            .zIndex(3f)
            .drawBehind {
                drawRoundRect(accent, cornerRadius = CornerRadius(20.dp.toPx()), style = Stroke(2.dp.toPx()))
            },
    ) {
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        if (horizontal) {
            ResizeHandle(Modifier.align(Alignment.CenterStart).offset(x = (-18).dp), onDrag = { left = (left + it.x).coerceIn(0f, right - cellW) }, onEnd = ::commit)
            ResizeHandle(Modifier.align(Alignment.CenterEnd).offset(x = 18.dp), onDrag = { right = (right + it.x).coerceIn(left + cellW, cols * cellW) }, onEnd = ::commit)
        }
        if (vertical) {
            ResizeHandle(Modifier.align(Alignment.TopCenter).offset(y = (-18).dp), onDrag = { top = (top + it.y).coerceIn(0f, bottom - cellH) }, onEnd = ::commit)
            ResizeHandle(Modifier.align(Alignment.BottomCenter).offset(y = 18.dp), onDrag = { bottom = (bottom + it.y).coerceIn(top + cellH, rows * cellH) }, onEnd = ::commit)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).offset(y = 32.dp)
                .clip(RoundedCornerShape(8.dp)).background(accent).padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            BasicText("${preview.w} × ${preview.h}", style = TextStyle(color = Color.White, fontSize = 12.sp))
        }
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier, onDrag: (Offset) -> Unit, onEnd: () -> Unit) {
    val accent = Dex.accent
    Box(
        modifier
            .size(36.dp)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White).border(3.dp, accent, CircleShape))
    }
}

/** The widget picker over the desktop, and a widget being dragged out of it. */
@Composable
private fun WidgetPickerLayer(shell: ShellController, actions: WidgetActions, modifier: Modifier) {
    var placing by remember { mutableStateOf<WidgetPlacement?>(null) }
    if (!shell.widgetPickerOpen && placing == null) return
    Box(modifier.fillMaxSize().deskGestures(onTap = shell::closeWidgetPicker)) {
        if (shell.widgetPickerOpen) {
            WidgetPicker(
                shell,
                Modifier.align(Alignment.Center).padding(8.dp),
                onAdd = { info ->
                    shell.closeWidgetPicker()
                    actions.addWidget(info, null)
                },
                onDragOut = { placing = it },
                onDrop = { p ->
                    placing = null
                    shell.closeWidgetPicker()
                    actions.addWidget(p.info, DesktopGrid.cellAt(p.at, p.w, p.h))
                },
                placing = placing != null,
            )
        }
        placing?.let { p ->
            // The widget follows the finger at its desktop size.
            val density = LocalDensity.current
            val wPx = p.w * DesktopGrid.cellW
            val hPx = p.h * DesktopGrid.cellH
            Box(
                Modifier
                    .offset { IntOffset((p.at.x - wPx / 2).roundToInt(), (p.at.y - hPx / 2).roundToInt()) }
                    .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                    .lifted(true, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Dex.pressed),
                contentAlignment = Alignment.Center,
            ) {
                p.preview?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().padding(6.dp)) }
            }
        }
    }
}
