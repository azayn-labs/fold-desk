package dev.home.folddesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.home.folddesk.Desktop
import dev.home.folddesk.DesktopDrag
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import dev.home.folddesk.ShellController

/**
 * The strip above the taskbar listing desktops (see design/desktops.html):
 * tap to switch, long-press to rename or delete, "+" to add one.
 */
@Composable
fun DesktopSwitcher(shell: ShellController, modifier: Modifier) {
    val desk = shell.desk
    Panel(24.dp, modifier) {
    Row(
        Modifier
            .deskGestures(onTap = shell::closeMenu, onMenu = { shell.closeMenu() })
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (d in desk.desktops) DesktopCard(shell, d)
        if (desk.canAddDesktop) {
            val hovered = DesktopDrag.hovered == DesktopDrag.NEW_DESKTOP
            HoverBox(
                corner = 14,
                selected = hovered,
                modifier = Modifier
                    .dropTarget(DesktopDrag.NEW_DESKTOP)
                    .size(width = 120.dp, height = 128.dp)
                    .border(2.dp, if (hovered) Dex.accent else Dex.separator, RoundedCornerShape(14.dp))
                    .deskGestures(onTap = shell::newDesktop),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("+", style = TextStyle(color = Dex.text, fontSize = 26.sp))
                    BasicText("New desktop", style = TextStyle(color = Dex.subtle, fontSize = 13.sp))
                }
            }
        }
    }
    }
}

@Composable
private fun DesktopCard(shell: ShellController, d: Desktop) {
    val desk = shell.desk
    val current = d.id == desk.currentDesktopId
    val apps = desk.appsOn(d.id)
    val feedback = remember(d.id) { PressFeedback() }
    // Another desktop under a dragged app.
    val target = DesktopDrag.hovered == d.id && !current
    Column(
        Modifier
            .dropTarget(d.id)
            .width(200.dp)
            .lifted(feedback.lifted, RoundedCornerShape(14.dp))
            .deskGestures(
                onTap = { shell.switchDesktop(d.id) },
                onMenu = { shell.desktopCardMenu(d.id, it) },
                feedback = feedback,
            ),
    ) {
        // Previews show the apps on each desktop; other apps' screens can't be captured.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(0.dp)
                .size(width = 200.dp, height = 128.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(previewGradient()))
                .then(if (target) Modifier.shadow(10.dp, RoundedCornerShape(14.dp), spotColor = Dex.accent) else Modifier)
                .border(if (target) 3.dp else 2.dp, if (current || target) Dex.accent else Color.Transparent, RoundedCornerShape(14.dp)),
        ) {
            if (apps.isNotEmpty()) MiniWindow(Offset(40f, 16f), 90, 64, 0.8f)
            if (apps.size > 1) MiniWindow(Offset(80f, 40f), 80, 66, 1f)
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                for (app in apps.takeLast(6)) Image(app.icon, contentDescription = app.label, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shell.renamingDesktop == d.id) {
                RenameField(d) { shell.finishRename(d.id, it) }
            } else {
                BasicText(
                    d.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.text, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    when {
                        current -> "Current"
                        apps.size == 1 -> "1 app"
                        else -> "${apps.size} apps"
                    },
                    style = TextStyle(color = Dex.subtle, fontSize = 11.5.sp),
                )
            }
        }
        if (target) {
            BasicText(
                "Drop to move · hold to go",
                style = TextStyle(color = Dex.accent, fontSize = 11.5.sp),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

/** Reports this card's screen bounds to [DesktopDrag] while an app is dragged. */
@Composable
private fun Modifier.dropTarget(id: Int): Modifier {
    val view = LocalView.current
    return onGloballyPositioned { coords ->
        if (DesktopDrag.active) {
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            DesktopDrag.targets[id] = coords.boundsInWindow().translate(Offset(loc[0].toFloat(), loc[1].toFloat()))
        }
    }
}

/** The dragged app's icon, following the finger (taskbar drags). */
@Composable
fun DragGhost() {
    val app = DesktopDrag.app ?: return
    val view = LocalView.current
    val loc = IntArray(2).also { view.getLocationOnScreen(it) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val half = with(density) { 32.dp.toPx() }
    val at = DesktopDrag.position - Offset(loc[0] + half, loc[1] + half)
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(at.x.toInt(), at.y.toInt()) }
            .size(64.dp)
            .lifted(true, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Dex.pressed),
        contentAlignment = Alignment.Center,
    ) {
        Image(app.icon, contentDescription = app.label, modifier = Modifier.size(48.dp))
    }
}

/** Stand-in for the wallpaper in desktop previews. */
@Composable
private fun previewGradient(): List<Color> =
    if (LocalDexColors.current == LightDex) listOf(Color(0xFFF3E9E2), Color(0xFFDFE7F5), Color(0xFFCFD9EE))
    else listOf(Color(0xFF1D3F86), Color(0xFF13295A), Color(0xFF0B1B3D))

@Composable
private fun MiniWindow(at: Offset, width: Int, height: Int, alpha: Float) {
    Box(
        Modifier
            .offset(at.x.dp, at.y.dp)
            .size(width.dp, height.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.85f * alpha)),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RenameField(d: Desktop, onDone: (String) -> Unit) {
    var name by remember(d.id) { mutableStateOf(d.name) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        singleLine = true,
        textStyle = TextStyle(color = Dex.text, fontSize = 13.sp),
        cursorBrush = SolidColor(Dex.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(name) }),
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Dex.field)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .focusRequester(focus),
    )
}
