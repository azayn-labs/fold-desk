package dev.home.folddesk.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.home.folddesk.AppEntry
import dev.home.folddesk.DeskFolder
import dev.home.folddesk.DeskState
import dev.home.folddesk.ShellController
import kotlin.math.roundToInt

private val Glass = Color.White.copy(alpha = 0.24f)
private val GlassEdge = Color.White.copy(alpha = 0.22f)

/** Apps shown in a folder's tile: all up to four, else three and a "+N" slot. */
private fun <T> tileSlots(items: List<T>): List<T> = if (items.size > 4) items.take(3) else items

/** The folder's icon: a glass square with up to four of its apps (or three and "+N"). */
@Composable
fun FolderGlyph(icons: List<ImageBitmap>, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.31f)
    val pad = size * 0.1f
    val gap = size * 0.0625f
    val slot = (size - pad * 2 - gap) / 2
    val shown = tileSlots(icons)
    Box(modifier.size(size).clip(shape).background(Glass).border(1.dp, GlassEdge, shape).padding(pad)) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (col in 0..1) {
                    val i = row * 2 + col
                    when {
                        i < shown.size -> Image(shown[i], contentDescription = null, modifier = Modifier.size(slot))
                        icons.size > 4 && i == 3 -> Box(
                            Modifier.size(slot).clip(RoundedCornerShape(slot * 0.3f)).background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText("+${icons.size - 3}", style = TextStyle(color = Color.White, fontSize = (slot.value * 0.5f).sp, fontWeight = FontWeight.SemiBold))
                        }
                        else -> Spacer(Modifier.size(slot))
                    }
                }
            }
        }
    }
}

/** A desktop label: white on the wallpaper, with a shadow to keep it readable. */
@Composable
private fun WallpaperLabel(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    BasicText(
        text,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = Dex.onWallpaper,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            shadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 3f),
        ),
        modifier = modifier,
    )
}

/** A folder on the desktop, one cell big: the same footprint as an app tile. */
@Composable
fun FolderTile(
    folder: DeskFolder,
    icons: List<ImageBitmap>,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    feedback: PressFeedback? = null,
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
            FolderGlyph(icons)
            Spacer(Modifier.height(5.dp))
            WallpaperLabel(folder.name)
        }
    }
}

/**
 * A large folder: 2×2 cells with the apps right on the desktop. [slot] gives
 * each app its touch handling (tap launches); [more] is the "+N" slot's.
 * The tile's own [modifier] handles the name and the empty space.
 */
@Composable
fun LargeFolderTile(
    folder: DeskFolder,
    apps: List<AppEntry>,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    feedback: PressFeedback? = null,
    slot: @Composable (AppEntry) -> Modifier,
    more: Modifier = Modifier,
) {
    val lifted = feedback?.lifted == true
    val shape = RoundedCornerShape(28.dp)
    val shown = tileSlots(apps)
    Box(
        modifier
            .size(Dex.desktopCellWidth * 2, Dex.desktopCellHeight * 2)
            .padding(2.dp)
            .lifted(lifted, shape)
            .clip(shape)
            .background(if (selected || feedback?.pressed == true) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.2f))
            .border(1.dp, GlassEdge, shape),
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            WallpaperLabel(folder.name, Modifier.fillMaxWidth().padding(horizontal = 12.dp), maxLines = 1)
            Spacer(Modifier.height(3.dp))
            for (row in 0..1) Row {
                for (col in 0..1) {
                    val i = row * 2 + col
                    val slotSize = Modifier.size(Dex.desktopCellWidth - 2.dp, Dex.desktopCellHeight - 14.dp)
                    when {
                        i < shown.size -> {
                            val app = shown[i]
                            Column(slotSize.then(slot(app)), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(6.dp))
                                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(4.dp))
                                WallpaperLabel(app.label, Modifier.padding(horizontal = 2.dp), maxLines = 1)
                            }
                        }
                        apps.size > 4 && i == 3 -> Column(slotSize.then(more), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.22f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText("+${apps.size - 3}", style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                            }
                            Spacer(Modifier.height(4.dp))
                            WallpaperLabel("More", maxLines = 1)
                        }
                        else -> Spacer(slotSize)
                    }
                }
            }
        }
    }
}

/** The ring and glow that say "dropping here makes (or fills) a folder". */
@Composable
fun Modifier.mergeTarget(on: Boolean, corner: Dp): Modifier {
    if (!on) return this
    val shape = RoundedCornerShape(corner)
    return graphicsLayer {
        scaleX = 1.08f
        scaleY = 1.08f
    }
        .shadow(14.dp, shape, spotColor = Dex.accent, ambientColor = Dex.accent)
        .border(2.dp, Dex.accent, shape)
}

// --- Open folder ---

private const val PANEL_MAX_WIDTH = 440
private const val PANEL_COLUMNS = 4

/**
 * The open folder: a panel over the desktop with its apps and an editable name.
 * Apps launch on tap; dragging one off the panel puts it back on the desktop.
 */
@Composable
fun FolderPanel(shell: ShellController, id: Int) {
    val desk = shell.desk
    val folder = desk.folderById(id)
    val apps = folder?.apps.orEmpty().mapNotNull(desk::app)
    if (folder == null || apps.isEmpty()) {
        // Dissolved (or its apps are gone): nothing left to show.
        LaunchedEffect(Unit) { shell.closeFolder() }
        return
    }

    var dragApp by remember { mutableStateOf<AppEntry?>(null) }
    // The finger in root coordinates, like the layer and the desktop grid.
    var finger by remember { mutableStateOf(Offset.Zero) }
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().onGloballyPositioned { layerOrigin = it.positionInRoot() }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        // Above the keyboard while renaming.
        Box(
            Modifier.fillMaxSize().imePadding().padding(16.dp).alpha(if (dragApp != null) 0.25f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            Panel(
                28.dp,
                Modifier
                    .widthIn(max = PANEL_MAX_WIDTH.dp)
                    .fillMaxWidth()
                    .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                    // Taps on the panel's empty parts stay on the panel.
                    .deskGestures(),
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp)) {
                    FolderTitle(shell, folder)
                    Column(
                        Modifier.padding(top = 10.dp).heightIn(max = Dex.desktopCellHeight * 3).verticalScroll(rememberScrollState()),
                    ) {
                        for (row in apps.chunked(PANEL_COLUMNS)) {
                            Row(Modifier.fillMaxWidth()) {
                                for (col in 0 until PANEL_COLUMNS) {
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                        val app = row.getOrNull(col) ?: return@Box
                                        val feedback = remember(app.key) { PressFeedback() }
                                        AppTile(
                                            app, onWallpaper = false,
                                            feedback = feedback,
                                            modifier = Modifier
                                                .width(Dex.desktopCellWidth)
                                                .deskGestures(
                                                    onTap = { shell.open(app) },
                                                    onMenu = {
                                                        finger = it
                                                        shell.appMenu(app, it)
                                                    },
                                                    feedback = feedback,
                                                    onDragStart = {
                                                        shell.closeMenu()
                                                        dragApp = app
                                                    },
                                                    onDrag = { finger += it },
                                                    onDragEnd = {
                                                        val dropped = dragApp
                                                        dragApp = null
                                                        // Dropped back on the panel: stays in.
                                                        if (dropped != null && !panelBounds.contains(finger)) {
                                                            desk.takeOutOfFolder(
                                                                id, dropped.key, DesktopGrid.cellAt(finger, 1, 1),
                                                                DesktopGrid.cols, DesktopGrid.rows, DesktopGrid.layout,
                                                            )
                                                            shell.closeFolder()
                                                        }
                                                    },
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    BasicText(
                        "Tap the name to rename · drag an app out to put it back on the desktop",
                        style = TextStyle(color = Dex.subtle, fontSize = 11.5.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
        dragApp?.let { app ->
            val half = with(density) { 24.dp.toPx() }
            Image(
                app.icon, contentDescription = null,
                modifier = Modifier
                    .offset { IntOffset((finger.x - layerOrigin.x - half).roundToInt(), (finger.y - layerOrigin.y - half).roundToInt()) }
                    .size(48.dp),
            )
        }
    }
}

/** The folder's name, with a pencil; renaming swaps it for a text field and a tick. */
@Composable
private fun FolderTitle(shell: ShellController, folder: DeskFolder) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shell.renamingFolder) {
            FolderNameField(folder.name) { shell.finishFolderRename(folder.id, it) }
        } else {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).deskGestures(onTap = shell::startFolderRename).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.widthIn(max = 300.dp),
                )
                Spacer(Modifier.width(8.dp))
                Glyph(DexGlyph.Edit, 16.dp, Dex.subtle)
            }
        }
    }
}

@Composable
private fun FolderNameField(name: String, onDone: (String) -> Unit) {
    // Starts with the whole name selected, so typing replaces it.
    var value by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = { value = it.copy(text = it.text.take(DeskState.MAX_FOLDER_NAME)) },
        singleLine = true,
        textStyle = TextStyle(color = Dex.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(Dex.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
        modifier = Modifier
            .widthIn(min = 160.dp, max = 260.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Dex.field)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .focusRequester(focus),
    )
    Spacer(Modifier.width(12.dp))
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Dex.accent)
            .deskGestures(onTap = { onDone(value.text) }),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(DexGlyph.Check, 18.dp, Color.White)
    }
}
