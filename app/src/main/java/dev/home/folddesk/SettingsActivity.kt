package dev.home.folddesk

import dev.home.folddesk.ui.Panel
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.home.folddesk.ui.Dex
import dev.home.folddesk.ui.DexTheme
import dev.home.folddesk.ui.LocalDexColors
import dev.home.folddesk.ui.HoverBox
import dev.home.folddesk.ui.deskGestures
import kotlin.math.roundToInt

/** FoldDesk settings, styled like One UI's own settings (see design/settings.html). */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val desk = (application as FoldDeskApp).desk
        setContent { DexTheme { SettingsScreen(desk) } }
    }
}

private object Ui {
    val background: Color @Composable get() = LocalDexColors.current.page
    val card: Color @Composable get() = LocalDexColors.current.card
    val segment: Color @Composable get() = LocalDexColors.current.segment
    val switchOff: Color @Composable get() = LocalDexColors.current.switchOff
    val running = Color(0xFF4CD964)
    val stopped = Color(0xFFFF6B5E)
}

/** An open action sheet: title, body, and the text buttons along its foot. */
private class Sheet(
    val title: String,
    val subtitle: String? = null,
    val buttons: List<Pair<String, () -> Unit>>,
    val body: @Composable () -> Unit,
)

@Composable
private fun SettingsScreen(desk: DeskState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    // Pick-one settings open an action sheet, like One UI's own choosers.
    fun <E : Enum<E>> pick(
        title: String, values: Array<E>, label: (E) -> String, current: E, onPick: (E) -> Unit, subtitle: String? = null,
    ) {
        sheet = Sheet(title, subtitle, buttons = listOf("Cancel" to { sheet = null })) {
            for (v in values) OptionRow(label(v), selected = v == current) { onPick(v); sheet = null }
        }
    }

    fun pickCoverLauncher() {
        val options = desk.installedLaunchers() + (context.packageName to "FoldDesk (both screens)")
        sheet = Sheet(
            "Cover screen launcher", "Used when the phone is folded",
            buttons = listOf("Cancel" to { sheet = null }),
        ) {
            for ((pkg, label) in options) {
                OptionRow(label, icon = rememberAppIcon(pkg), selected = pkg == desk.coverLauncher) {
                    desk.updateCoverLauncher(pkg)
                    sheet = null
                }
            }
        }
    }

    fun pickColours() {
        // Colours apply live; Cancel puts back what was there.
        val before = desk.taskbarColour to desk.accentColour
        sheet = Sheet(
            "Colours", "Taskbar colour and accent",
            buttons = listOf(
                "Cancel" to { desk.updateColours(before.first, before.second); sheet = null },
                "Apply" to { sheet = null },
            ),
        ) { ColourPicker(desk) }
    }

    Box(Modifier.fillMaxSize().background(Ui.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            BasicText(
                "FoldDesk settings",
                style = TextStyle(color = Dex.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 18.dp),
            )

            Group("Layout") {
                SettingRow("Taskbar position") {
                    Segmented(TaskbarSide.entries, desk.taskbarSide, { it.label }, desk::updateTaskbarSide)
                }
                Divider()
                SettingRow("Taskbar icons", hint = "Start or centre of the taskbar") {
                    Segmented(Placement.entries, desk.taskbarAlignment, { it.label }, desk::updateTaskbarAlignment)
                }
            }

            Group("Taskbar") {
                SettingRow(
                    "Auto hide taskbar",
                    hint = "Hides while an app is open. Tap or swipe the handle at the edge to show it",
                    onTapAt = { desk.updateAutoHideTaskbar(!desk.autoHideTaskbar) },
                ) { Switch(desk.autoHideTaskbar) }
                Divider()
                SettingRow("Background") {
                    Segmented(TaskbarBackground.entries, desk.taskbarBackground, { it.label }, desk::updateTaskbarBackground)
                }
                Divider()
                SettingRow("Size") {
                    Segmented(TaskbarSize.entries, desk.taskbarSize, { it.label }, desk::updateTaskbarSize)
                }
                Divider()
                SettingRow("Colours", value = desk.coloursName, onTapAt = { pickColours() }) {
                    ColourChip(desk)
                }
            }

            Group("Taskbar shortcuts") {
                SettingRow("Tap Wi-Fi and battery", value = desk.statusAction.label, onTapAt = {
                    pick("Tap Wi-Fi and battery", StatusAction.entries.toTypedArray(), { e -> e.label }, desk.statusAction, desk::updateStatusAction)
                })
                Divider()
                SettingRow("Tap the date and time", value = desk.clockAction.label, onTapAt = {
                    pick("Tap the date and time", ClockAction.entries.toTypedArray(), { e -> e.label }, desk.clockAction, desk::updateClockAction)
                })
            }

            Group("Windows") {
                SettingRow(
                    "Open apps in windows",
                    hint = "Tap an app to open it as a pop-up window",
                    onTapAt = { desk.updateOpenInWindow(!desk.openInWindow) },
                ) { Switch(desk.openInWindow) }
                Divider()
                SettingRow(
                    "Open notifications in windows",
                    hint = "Apps opened from a notification switch to a pop-up window",
                    onTapAt = { desk.updateNotificationsInWindows(!desk.notificationsInWindows) },
                ) { Switch(desk.notificationsInWindows) }
                Divider()
                SettingRow("New window size", value = desk.windowSize.label, onTapAt = {
                    pick("New window size", WindowSize.entries.toTypedArray(), { e -> e.label }, desk.windowSize, desk::updateWindowSize)
                })
            }

            Group("Gestures") {
                SettingRow("Swipe up on desktop", value = desk.swipeUp.label, onTapAt = {
                    pick("Swipe up on desktop", SwipeAction.entries.toTypedArray(), { e -> e.label }, desk.swipeUp, desk::updateSwipeUp, "On an empty part of the desktop")
                })
                Divider()
                SettingRow("Swipe down on desktop", value = desk.swipeDown.label, onTapAt = {
                    pick("Swipe down on desktop", SwipeAction.entries.toTypedArray(), { e -> e.label }, desk.swipeDown, desk::updateSwipeDown, "On an empty part of the desktop")
                })
                Divider()
                SettingRow(
                    "Swipe to switch desktops",
                    hint = "Swipe left or right on empty desktop",
                    onTapAt = { desk.updateSwipeSwitchesDesktops(!desk.swipeSwitchesDesktops) },
                ) { Switch(desk.swipeSwitchesDesktops) }
                Divider()
                SettingRow(
                    "Swipe down to close app drawer",
                    hint = "When the app list is scrolled to the top",
                    onTapAt = { desk.updateSwipeDownClosesDrawer(!desk.swipeDownClosesDrawer) },
                ) { Switch(desk.swipeDownClosesDrawer) }
            }

            Group("Cover screen") {
                val label = if (!desk.coverUsesOneUi) "FoldDesk (both screens)"
                else desk.installedLaunchers().firstOrNull { it.first == desk.coverLauncher }?.second ?: "One UI Home"
                SettingRow("Cover screen launcher", value = label, onTapAt = { pickCoverLauncher() })
            }

            Group("Status") {
                val running = TaskbarService.running
                SettingRow(
                    "Taskbar service",
                    hint = if (running) null else "Tap to turn it on in Accessibility settings",
                    onTapAt = if (running) null else { _ -> Launcher.openAccessibilitySettings(context) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = if (running) Ui.running else Ui.stopped
                        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(8.dp))
                        BasicText(if (running) "Running" else "Off", style = TextStyle(color = color, fontSize = 13.sp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        SheetHost(sheet, onDismiss = { sheet?.buttons?.first()?.second?.invoke() })
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    BasicText(
        title,
        style = TextStyle(color = Dex.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp),
    )
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ui.card)) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Dex.divider))
}

/**
 * A settings row: title, then either a blue current value or a grey hint.
 * [onTapAt] gets the row's bottom-left, where a choice list should hang.
 */
@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    hint: String? = null,
    onTapAt: ((Offset) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var anchor by remember { mutableStateOf(Offset.Zero) }
    HoverBox(
        corner = 0,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchor = it.boundsInRoot().bottomLeft }
            .then(if (onTapAt != null) Modifier.deskGestures(onTap = { onTapAt(anchor) }) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(title, style = TextStyle(color = Dex.text, fontSize = 15.5.sp))
                val sub = value ?: hint
                if (sub != null) {
                    BasicText(
                        sub,
                        style = TextStyle(color = if (value != null) Dex.accent else Dex.subtle, fontSize = 13.sp),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(14.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Ui.segment).padding(3.dp)) {
        for (option in options) {
            val on = option == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) Dex.accent else Color.Transparent)
                    .deskGestures(onTap = { onSelect(option) })
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                BasicText(label(option), style = TextStyle(color = if (on) Color.White else Dex.subtle, fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Dex.accent else Ui.switchOff)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun Radio(on: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(if (on) Dex.accent else Color(0xFF8A8A90))
            .padding(2.dp)
            .clip(CircleShape)
            .background(Dex.menu)
            .padding(3.dp)
            .clip(CircleShape)
            .background(if (on) Dex.accent else Dex.menu),
    )
}

// --- Action sheets (One UI style: blurred panel rising from the bottom) ---

@Composable
private fun SheetHost(sheet: Sheet?, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf<Sheet?>(null) }
    if (sheet != null) shown = sheet
    val state = remember { MutableTransitionState(false) }
    state.targetState = sheet != null
    if (!state.currentState && !state.targetState) return
    Box(Modifier.fillMaxSize().zIndex(5f)) {
        AnimatedVisibility(state, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x59000000)).deskGestures(onTap = onDismiss))
        }
        AnimatedVisibility(
            state,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(260)) { it } + fadeIn(),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(),
        ) {
            val s = shown ?: return@AnimatedVisibility
            BoxWithConstraints {
                Panel(26.dp, Modifier.padding(bottom = 12.dp).width(minOf(560.dp, maxWidth - 24.dp))) {
                    Column(Modifier.fillMaxWidth().deskGestures().padding(top = 10.dp)) {
                        Box(
                            Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp)).background(Dex.separator),
                        )
                        BasicText(
                            s.title,
                            style = TextStyle(color = Dex.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                        )
                        s.subtitle?.let {
                            BasicText(it, style = TextStyle(color = Dex.subtle, fontSize = 13.sp), modifier = Modifier.padding(start = 24.dp, top = 3.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { s.body() }
                        // Plain text buttons split by a thin divider, as One UI does.
                        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                            s.buttons.forEachIndexed { i, (label, action) ->
                                if (i > 0) Box(Modifier.size(width = 1.dp, height = 20.dp).background(Dex.separator))
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().deskGestures(onTap = action),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val strong = i == s.buttons.lastIndex && s.buttons.size > 1
                                    BasicText(
                                        label,
                                        style = TextStyle(
                                            color = if (strong) Dex.accent else Dex.text,
                                            fontSize = 16.sp,
                                            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, icon: ImageBitmap? = null, onClick: () -> Unit) {
    HoverBox(corner = 0, modifier = Modifier.fillMaxWidth().deskGestures(onTap = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
            }
            BasicText(label, style = TextStyle(color = Dex.text, fontSize = 15.sp), modifier = Modifier.weight(1f))
            Radio(selected)
        }
    }
}

@Composable
private fun rememberAppIcon(pkg: String): ImageBitmap? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
}

/** The current colours as a tiny taskbar, like the tiles in the sheet. */
@Composable
private fun ColourChip(desk: DeskState) {
    val bar = desk.taskbarColour?.let { Color(it) } ?: Dex.bar.copy(alpha = 1f)
    val mark = if (bar.luminance() > 0.5f) Color(0xBF000000) else Color(0xBFFFFFFF)
    Row(
        Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(7.dp)).background(bar)
            .border(1.dp, Dex.separator, RoundedCornerShape(7.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) { Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(RoundedCornerShape(3.dp)).background(mark)) }
        Box(Modifier.padding(start = 3.dp).size(width = 12.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Dex.accent))
    }
}

/** Ready-made colour pairs, then custom taskbar and accent swatches (design/v012-colours.html). */
@Composable
private fun ColourPicker(desk: DeskState) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        for (row in ColourPresets.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (p in row) {
                    val on = desk.taskbarColour == p.bar && desk.accentColour == p.accent
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Dex.field)
                            .border(2.dp, if (on) Dex.accent else Color.Transparent, RoundedCornerShape(14.dp))
                            .deskGestures(onTap = { desk.updateColours(p.bar, p.accent) })
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // A tiny taskbar: two icons and the running-app bar.
                        Row(
                            Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(7.dp)).background(Color(p.bar)),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val mark = if (Color(p.bar).luminance() > 0.5f) Color(0xBF000000) else Color(0xBFFFFFFF)
                            repeat(2) { Box(Modifier.padding(horizontal = 2.dp).size(12.dp).clip(RoundedCornerShape(4.dp)).background(mark)) }
                            Box(Modifier.padding(start = 3.dp).size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(p.accent)))
                        }
                        BasicText(p.name, style = TextStyle(color = Dex.text, fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        BasicText(
            "Custom",
            style = TextStyle(color = Dex.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 8.dp),
        )
        SwatchRow("Taskbar", CustomBarColours, desk.taskbarColour) { desk.updateColours(it, desk.accentColour ?: CustomAccents.first()) }
        SwatchRow("Accent", CustomAccents, desk.accentColour) { desk.updateColours(desk.taskbarColour ?: CustomBarColours[2], it) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun SwatchRow(label: String, colours: List<Long>, selected: Long?, onPick: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = TextStyle(color = Dex.text, fontSize = 14.sp), modifier = Modifier.width(96.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in colours) {
                val on = c == selected
                Box(
                    Modifier.size(26.dp)
                        .border(2.dp, if (on) Dex.text else Color.Transparent, CircleShape)
                        .padding(3.dp).clip(CircleShape).background(Color(c))
                        // A hairline so dark swatches show on the dark sheet.
                        .border(1.dp, Dex.separator, CircleShape)
                        .deskGestures(onTap = { onPick(c) }),
                )
            }
        }
    }
}
