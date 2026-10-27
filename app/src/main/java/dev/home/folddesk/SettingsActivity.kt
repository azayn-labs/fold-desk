package dev.home.folddesk

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

/** An open choice list: which options, which is picked, and where it hangs. */
private class Choice(val at: Offset, val labels: List<String>, val selected: Int, val onPick: (Int) -> Unit)

@Composable
private fun SettingsScreen(desk: DeskState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var choice by remember { mutableStateOf<Choice?>(null) }

    // Opens a choice list anchored under the row that was tapped.
    fun <E : Enum<E>> pick(at: Offset, values: Array<E>, label: (E) -> String, current: E, onPick: (E) -> Unit) {
        choice = Choice(at, values.map(label), values.indexOf(current)) { onPick(values[it]) }
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

            Group("Taskbar shortcuts") {
                SettingRow("Tap Wi-Fi and battery", value = desk.statusAction.label, onTapAt = {
                    pick(it, StatusAction.entries.toTypedArray(), { e -> e.label }, desk.statusAction, desk::updateStatusAction)
                })
                Divider()
                SettingRow("Tap the date and time", value = desk.clockAction.label, onTapAt = {
                    pick(it, ClockAction.entries.toTypedArray(), { e -> e.label }, desk.clockAction, desk::updateClockAction)
                })
            }

            Group("Windows") {
                SettingRow(
                    "Open apps in windows",
                    hint = "Tap an app to open it as a pop-up window",
                    onTapAt = { desk.updateOpenInWindow(!desk.openInWindow) },
                ) { Switch(desk.openInWindow) }
                Divider()
                SettingRow("New window size", value = desk.windowSize.label, onTapAt = {
                    pick(it, WindowSize.entries.toTypedArray(), { e -> e.label }, desk.windowSize, desk::updateWindowSize)
                })
            }

            Group("Gestures") {
                SettingRow(
                    "Swipe up for app drawer",
                    hint = "Swipe up on empty desktop to open it",
                    onTapAt = { desk.updateSwipeUpForDrawer(!desk.swipeUpForDrawer) },
                ) { Switch(desk.swipeUpForDrawer) }
                Divider()
                SettingRow(
                    "Swipe down to close app drawer",
                    hint = "When the app list is scrolled to the top",
                    onTapAt = { desk.updateSwipeDownClosesDrawer(!desk.swipeDownClosesDrawer) },
                ) { Switch(desk.swipeDownClosesDrawer) }
                Divider()
                SettingRow(
                    "Swipe to switch desktops",
                    hint = "Swipe left or right on empty desktop",
                    onTapAt = { desk.updateSwipeSwitchesDesktops(!desk.swipeSwitchesDesktops) },
                ) { Switch(desk.swipeSwitchesDesktops) }
                Divider()
                SettingRow(
                    "Swipe down for notifications",
                    hint = "Swipe down on empty desktop to open the notification panel",
                    onTapAt = { desk.updateSwipeDownForNotifications(!desk.swipeDownForNotifications) },
                ) { Switch(desk.swipeDownForNotifications) }
            }

            Group("Cover screen") {
                SettingRow(
                    "Use One UI Home when folded",
                    hint = "FoldDesk only runs on the inner screen",
                    onTapAt = { desk.updateCoverUsesOneUi(!desk.coverUsesOneUi) },
                ) { Switch(desk.coverUsesOneUi) }
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

        choice?.let { ChoiceList(it, onDismiss = { choice = null }) }
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

/** One UI's pop-up radio list, dimming the page behind it. */
@Composable
private fun ChoiceList(choice: Choice, onDismiss: () -> Unit) {
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val inset = with(LocalDensity.current) { 120.dp.roundToPx() }
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(5f)
            .background(Color(0x59000000))
            .deskGestures(onTap = onDismiss, onMenu = { onDismiss() }),
        content = {
            Column(
                Modifier
                    .width(320.dp)
                    .shadow(18.dp, RoundedCornerShape(22.dp))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Dex.menu)
                    .deskGestures()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                choice.labels.forEachIndexed { i, label ->
                    HoverBox(
                        corner = 0,
                        modifier = Modifier.fillMaxWidth().deskGestures(onTap = {
                            choice.onPick(i)
                            onDismiss()
                        }),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Radio(i == choice.selected)
                            Spacer(Modifier.width(12.dp))
                            BasicText(label, style = TextStyle(color = Dex.text, fontSize = 14.5.sp))
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val list = measurables.first().measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Hang under the row, indented like the mockup; flip up near the bottom.
            val x = (choice.at.x.roundToInt() + inset)
                .coerceIn(margin, (constraints.maxWidth - list.width - margin).coerceAtLeast(margin))
            val below = choice.at.y.roundToInt()
            val y = if (below + list.height > constraints.maxHeight - margin) below - list.height else below
            list.place(x, y.coerceAtLeast(margin))
        }
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
