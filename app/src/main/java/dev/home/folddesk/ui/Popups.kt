package dev.home.folddesk.ui

import dev.home.folddesk.TaskbarSide
import androidx.compose.ui.platform.LocalView
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.home.folddesk.AppEntry
import dev.home.folddesk.Launcher
import dev.home.folddesk.DesktopDrag
import dev.home.folddesk.MenuItem
import dev.home.folddesk.MenuState
import dev.home.folddesk.Placement
import dev.home.folddesk.ShellController
import kotlin.math.roundToInt

/**
 * Drawer and context menu, drawn over the area above the taskbar. Tapping
 * anywhere outside them closes them.
 */
@Composable
fun PopupLayer(shell: ShellController, modifier: Modifier = Modifier, dragTargets: Boolean = true) {
    val dragging = dragTargets && DesktopDrag.active
    // Kept composed while the drawer's close animation plays.
    val drawer = remember { MutableTransitionState(false) }
    drawer.targetState = shell.drawerOpen
    val drawerShowing = drawer.currentState || drawer.targetState
    if (!shell.hasPopup && !dragging && !drawerShowing) return
    Box(
        modifier.fillMaxSize().deskGestures(
            onTap = shell::closePopups,
            onMenu = { shell.closePopups() },
            // Swiping down outside the drawer dismisses it too.
            onSwipe = { d ->
                if (shell.drawerOpen && shell.desk.swipeDownClosesDrawer && d.y > 0 &&
                    kotlin.math.abs(d.y) > kotlin.math.abs(d.x)
                ) shell.closePopups()
            },
        ),
    ) {
        AnimatedVisibility(
            visibleState = drawer,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(DRAWER_OPEN_MS)) +
                scaleIn(tween(DRAWER_OPEN_MS, easing = LinearOutSlowInEasing), initialScale = 0.94f) +
                slideInVertically(tween(DRAWER_OPEN_MS, easing = LinearOutSlowInEasing)) { it / 24 },
            exit = fadeOut(tween(DRAWER_CLOSE_MS)) +
                scaleOut(tween(DRAWER_CLOSE_MS, easing = FastOutLinearInEasing), targetScale = 0.96f),
        ) {
            val blur by transition.animateFloat(
                transitionSpec = { tween(if (targetState == EnterExitState.Visible) DRAWER_OPEN_MS else DRAWER_CLOSE_MS) },
                label = "drawerBlur",
            ) { if (it == EnterExitState.Visible) 1f else 0f }
            AppDrawer(shell, Modifier.padding(8.dp), blur)
        }
        if (shell.switcherOpen || dragging) {
            // Next to the Desktops button: above a bottom bar, beside a side one.
            val centered = shell.desk.taskbarAlignment == Placement.Center
            val at = when (shell.desk.taskbarSide) {
                TaskbarSide.Bottom -> if (centered) Alignment.BottomCenter else Alignment.BottomStart
                TaskbarSide.Left -> if (centered) Alignment.CenterStart else Alignment.TopStart
                TaskbarSide.Right -> if (centered) Alignment.CenterEnd else Alignment.TopEnd
            }
            DesktopSwitcher(shell, Modifier.align(at).padding(8.dp))
        }
        shell.menu?.let { ContextMenu(it, onDismiss = shell::closeMenu) }
        if (dragging && DesktopDrag.showIcon) DragGhost()
    }
}

// --- App drawer ---

private const val DRAWER_OPEN_MS = 220
/** Also how long the overlay keeps its popup window after closing. */
const val DRAWER_CLOSE_MS = 150

@Composable
private fun AppDrawer(shell: ShellController, modifier: Modifier, blurAmount: Float = 1f) {
    val desk = shell.desk
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val results = remember(query, desk.apps) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) desk.apps
        else desk.apps.filter { q in it.label.lowercase() }
            .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
    }

    BoxWithConstraints(modifier) {
        val width = minOf(560.dp, maxWidth * 0.7f)
        val height = minOf(640.dp, maxHeight)
        val grid = rememberLazyGridState()
        Panel(26.dp, Modifier.size(width, height), blurAmount) {
        Column(
            Modifier
                .fillMaxSize()
                .swipeDownToClose(grid, { shell.desk.swipeDownClosesDrawer }, shell::closePopups)
                // Taps inside the drawer close an open menu, never the drawer itself.
                .deskGestures(onTap = shell::closeMenu, onMenu = { shell.closeMenu() })
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
        ) {
            // Search across the top, with the drawer's buttons at its right end.
            Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(Dex.field)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchGlyph()
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) BasicText("Search", style = TextStyle(color = Dex.subtle, fontSize = 14.sp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Dex.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(Dex.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let { shell.open(it) } }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            RoundButton(onTap = shell::openDeskSettings) { Glyph(DexGlyph.Sliders, 20.dp) }
            RoundButton(onTap = { shell.closePopups(); Launcher.openSettings(context) }) { Glyph(DexGlyph.Settings, 20.dp) }
            shell.keys?.let { keys ->
                RoundButton(onTap = { shell.closePopups(); keys.powerMenu() }) { Glyph(DexGlyph.Power, 20.dp) }
            }
            }
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                state = grid,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(results, key = { it.key }) { app ->
                    val feedback = remember { PressFeedback() }
                    AppTile(
                        app, onWallpaper = false, feedback = feedback,
                        modifier = Modifier.deskGestures(
                            onTap = { shell.open(app) },
                            onMenu = { shell.appMenu(app, it) },
                            feedback = feedback,
                        ),
                    )
                }
            }
        }
        }
    }
}

/**
 * Closes the drawer on a downward swipe, but only one that starts with the app
 * list at the top: after scrolling down, the first swipe scrolls back up and a
 * second one closes. Only watches; scrolling and taps work as usual.
 */
private fun Modifier.swipeDownToClose(grid: LazyGridState, enabled: () -> Boolean, close: () -> Unit) = pointerInput(grid) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val atTop = !grid.canScrollBackward
        var total = Offset.Zero
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            total += change.positionChange()
            if (!change.pressed) break
        }
        val min = 80.dp.toPx()
        if (enabled() && atTop && total.y > min && total.y > 2 * kotlin.math.abs(total.x)) close()
    }
}

@Composable
private fun RoundButton(onTap: () -> Unit, content: @Composable () -> Unit) {
    HoverBox(corner = 19, modifier = Modifier.size(38.dp).deskGestures(onTap = onTap)) { content() }
}

@Composable
private fun SearchGlyph() {
    val ink = Dex.subtle
    androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
        val u = size.width / 24f
        drawCircle(ink, 6.5f * u, Offset(10 * u, 10 * u), style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * u))
        drawLine(ink, Offset(15 * u, 15 * u), Offset(21 * u, 21 * u), 2.4f * u, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// --- Shared app tile (drawer and desktop) ---

@Composable
fun AppTile(
    app: AppEntry,
    onWallpaper: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    feedback: PressFeedback? = null,
    showLabel: Boolean = true,
) {
    val lifted = feedback?.lifted == true
    HoverBox(
        modifier = modifier.lifted(lifted, RoundedCornerShape(12.dp)),
        selected = selected || feedback?.pressed == true,
        selectedColor = if (lifted) Dex.pressed else Dex.hover,
        corner = 12,
    ) {
        Column(
            Modifier.padding(top = 8.dp, bottom = 6.dp, start = 2.dp, end = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(app.icon, contentDescription = app.label, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(5.dp))
            BasicText(
                app.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (onWallpaper) Dex.onWallpaper else Dex.text,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    shadow = if (onWallpaper && showLabel) Shadow(Color(0xCC000000), Offset(0f, 1f), 3f) else null,
                ),
                // Hidden but still laid out, so stacked tiles keep their size.
                modifier = Modifier.alpha(if (showLabel) 1f else 0f),
            )
        }
    }
}

/** The long-press "pop": slightly larger, with a shadow. */
fun Modifier.lifted(on: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (!on) this else graphicsLayer {
        scaleX = 1.08f
        scaleY = 1.08f
    }.shadow(12.dp, shape)

// --- Context menu ---

@Composable
private fun ContextMenu(state: MenuState, onDismiss: () -> Unit) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    // An item with a submenu opens a side list next to it.
    var openSub by remember(state) { mutableStateOf<MenuItem?>(null) }
    var subTop by remember(state) { mutableStateOf(0f) }
    var layerTop by remember { mutableStateOf(0f) }
    val view = LocalView.current

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f)
            .onGloballyPositioned { layerTop = it.positionInRoot().y },
        content = {
            MenuPanel(width = 230.dp) {
                state.header?.let { app ->
                    Row(
                        Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(app.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicText(
                            app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = Dex.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                state.groups.filter { it.isNotEmpty() }.forEachIndexed { i, group ->
                    if (i > 0 || state.header != null) MenuDivider()
                    for (item in group) {
                        var top by remember { mutableStateOf(0f) }
                        MenuRow(
                            item,
                            selected = openSub == item,
                            modifier = Modifier.onGloballyPositioned { top = it.positionInRoot().y },
                            onTap = {
                                if (item.submenu != null) {
                                    openSub = if (openSub == item) null else item
                                    subTop = top
                                } else {
                                    onDismiss()
                                    item.action()
                                }
                            },
                        )
                    }
                }
            }
            openSub?.submenu?.let { items ->
                MenuPanel(width = 190.dp) {
                    for (item in items) {
                        MenuRow(item, onTap = {
                            onDismiss()
                            item.action()
                        })
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val menu = measurables[0].measure(Constraints())
        val sub = measurables.getOrNull(1)?.measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Taskbar menus give a screen position; bring it into this window.
            val at = if (state.taskbarEdge != null) {
                val loc = IntArray(2).also { view.getLocationOnScreen(it) }
                state.at - androidx.compose.ui.geometry.Offset(loc[0].toFloat(), loc[1].toFloat() + layerTop)
            } else {
                state.at
            }
            val maxX = (constraints.maxWidth - menu.width - margin).coerceAtLeast(0)
            val maxY = (constraints.maxHeight - menu.height - margin).coerceAtLeast(margin)
            val x = when (state.taskbarEdge) {
                TaskbarSide.Left -> margin
                TaskbarSide.Right -> maxX
                else -> at.x.roundToInt().coerceIn(margin.coerceAtMost(maxX), maxX)
            }
            val y = if (state.taskbarEdge == TaskbarSide.Left || state.taskbarEdge == TaskbarSide.Right) {
                // Level with the icon, beside the bar.
                (at.y.roundToInt() - menu.height / 3).coerceIn(margin, maxY)
            } else if (state.anchorBottom || state.taskbarEdge == TaskbarSide.Bottom) {
                constraints.maxHeight - menu.height - margin
            } else {
                // Open downward from the pointer, flipping up near the bottom edge.
                val below = state.at.y.roundToInt()
                if (below + menu.height > constraints.maxHeight - margin) below - menu.height else below
            }.coerceAtLeast(margin)
            menu.place(x, y)
            sub?.let {
                // Beside the menu (left of it if there's no room on the right), level with its item.
                val right = x + menu.width + margin / 2
                val sx = if (right + it.width <= constraints.maxWidth - margin) right else x - it.width - margin / 2
                val sy = (subTop - layerTop).roundToInt()
                    .coerceIn(margin, (constraints.maxHeight - it.height - margin).coerceAtLeast(margin))
                it.place(sx.coerceAtLeast(margin), sy)
            }
        }
    }
}

@Composable
private fun MenuPanel(width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Panel(22.dp, Modifier.width(width)) {
        Column(Modifier.width(width).deskGestures().padding(vertical = 8.dp)) { content() }
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Dex.divider))
}

@Composable
private fun MenuRow(item: MenuItem, onTap: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    HoverBox(
        corner = 0,
        selected = selected,
        modifier = modifier.fillMaxWidth().then(if (item.enabled) Modifier.deskGestures(onTap = onTap) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                item.label,
                style = TextStyle(color = if (item.enabled) Dex.text else Dex.subtle, fontSize = 14.sp),
                modifier = Modifier.weight(1f),
            )
            item.value?.let {
                BasicText(it, style = TextStyle(color = Dex.subtle, fontSize = 13.sp), modifier = Modifier.padding(start = 10.dp, end = 6.dp))
            }
            when {
                item.submenu != null -> BasicText("›", style = TextStyle(color = Dex.text, fontSize = 16.sp))
                item.checked == true -> BasicText("✓", style = TextStyle(color = Dex.accent, fontSize = 14.sp))
            }
        }
    }
}
