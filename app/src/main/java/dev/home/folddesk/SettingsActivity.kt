package dev.home.folddesk

import dev.home.folddesk.ui.Panel
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.MutableState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.home.folddesk.ui.Dex
import dev.home.folddesk.ui.DexTheme
import dev.home.folddesk.ui.rememberTurnOnTaskbar
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
    val heart = Color(0xFFFF6B81)
    val stopped: Color @Composable get() = LocalDexColors.current.warning
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

    fun showFeedback() {
        sheet = Sheet(
            "Feedback and bug reports", "Tell me what's broken or what you'd like to see",
            buttons = listOf("Close" to { sheet = null }),
        ) {
            OptionRow("Report on GitHub", selected = null, hint = "Best for bugs, with steps and screenshots") {
                Launcher.openLink(context, "$REPO/issues"); sheet = null
            }
            OptionRow("Reddit thread", selected = null, hint = "r/GalaxyFold, ask anything") {
                Launcher.openLink(context, REDDIT_THREAD); sheet = null
            }
            OptionRow("Email", selected = null, hint = FEEDBACK_EMAIL) {
                Launcher.openLink(context, "mailto:$FEEDBACK_EMAIL"); sheet = null
            }
        }
    }

    // Play build only: tips through Google Play, opened from About.
    val tipStore = remember { createTipStore(context) }
    DisposableEffect(tipStore) { onDispose { tipStore?.close() } }

    fun showSupport(tips: TipStore) {
        sheet = Sheet(
            "Support FoldDesk",
            "FoldDesk is free and stays free, with nothing locked. If it's useful to you, a tip helps keep it going.",
            buttons = listOf("Close" to { sheet = null }),
        ) {
            if (tips.tips.isEmpty()) {
                BasicText(
                    "Tips aren't available right now. Check that Google Play is signed in, then try again.",
                    style = TextStyle(color = Dex.subtle, fontSize = 13.sp),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            for (tip in tips.tips) {
                TipRow(tip) { (context as? android.app.Activity)?.let { tips.buy(it, tip) } }
            }
        }
    }

    tipStore?.let { tips ->
        LaunchedEffect(tips.thanked) {
            if (tips.thanked == 0) return@LaunchedEffect
            sheet = Sheet("Thank you!", buttons = listOf("Close" to { sheet = null })) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("♥", style = TextStyle(color = Ui.heart, fontSize = 40.sp))
                    BasicText(
                        "Your support means a lot. FoldDesk keeps getting better because of people like you.",
                        style = TextStyle(color = Dex.text, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    fun pickCoverLauncher() {
        val options = desk.installedLaunchers() + listOf(
            COVER_SCREEN to "FoldDesk cover screen",
            context.packageName to "FoldDesk desktop (both screens)",
        )
        sheet = Sheet(
            "Cover screen launcher", "Used when the phone is folded",
            buttons = listOf("Cancel" to { sheet = null }),
        ) {
            for ((pkg, label) in options) {
                val own = pkg == COVER_SCREEN
                OptionRow(
                    label,
                    icon = rememberAppIcon(if (own) context.packageName else pkg),
                    selected = pkg == desk.coverLauncher,
                    hint = if (own) "Notifications and your day at a glance" else null,
                    tag = if (own) "PREVIEW" else null,
                ) {
                    desk.updateCoverLauncher(pkg)
                    sheet = null
                }
            }
        }
    }

    fun pickHiddenApps() {
        // Each tick saves at once, like a switch, so closing the sheet any way
        // (Back, tapping outside) keeps what was ticked.
        val query = mutableStateOf("")
        sheet = Sheet(
            "Hidden apps", "Ticked apps don't show in the app drawer",
            buttons = listOf("Done" to { sheet = null }),
        ) { HiddenAppsPicker(desk, query) }
    }

    fun pickSwipeRightApp() {
        val query = mutableStateOf("")
        sheet = Sheet(
            "Swipe right on the first desktop", "On an empty part of the desktop",
            buttons = listOf("Cancel" to { sheet = null }),
        ) {
            SwipeAppPicker(desk, query) { key ->
                desk.updateSwipeRightApp(key)
                sheet = null
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

            // A phone without a fold only has FoldDesk's phone layout; the rest is for the desktop.
            val phoneOnly = desk.noFold && !FoldDeskApp.isInnerScreen(LocalConfiguration.current)
            if (phoneOnly) {
                Group("Home screen") {
                    var isHome by remember { mutableStateOf(Launcher.isDefaultHome(context)) }
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isHome = Launcher.isDefaultHome(context) }
                    SettingRow(
                        "Default home app",
                        hint = "Tap to choose",
                        value = if (isHome) "FoldDesk" else "Another app",
                        onTapAt = { Launcher.openHomeSettings(context) },
                    )
                    Divider()
                    SettingRow("Wallpaper", onTapAt = { Launcher.openWallpaperPicker(context) })
                }
                Group("App list") {
                    val count = desk.hiddenApps.count { key -> desk.apps.any { it.key == key } }
                    SettingRow(
                        "Hidden apps",
                        value = when (count) { 0 -> "None"; 1 -> "1 app"; else -> "$count apps" },
                        onTapAt = { pickHiddenApps() },
                    )
                }
            } else {
                Group("Layout") {
                    SettingRow("Taskbar position", wide = true) {
                        Segmented(TaskbarSide.entries, desk.taskbarSide, { it.label }, desk::updateTaskbarSide)
                    }
                    Divider()
                    SettingRow("Taskbar icons", hint = "Start or centre of the taskbar", wide = true) {
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
                    SettingRow(
                        "Keep over full-screen apps",
                        hint = "Stays visible over videos and games too. It covers the bottom of the app, so some controls may be hidden under it",
                        onTapAt = { desk.updateKeepOverFullScreen(!desk.keepOverFullScreen) },
                    ) { Switch(desk.keepOverFullScreen) }
                    Divider()
                    SettingRow("Background", wide = true) {
                        Segmented(TaskbarBackground.entries, desk.taskbarBackground, { it.label }, desk::updateTaskbarBackground)
                    }
                    Divider()
                    SettingRow("Size", wide = true) {
                        Segmented(TaskbarSize.entries, desk.taskbarSize, { it.label }, desk::updateTaskbarSize)
                    }
                    Divider()
                    SettingRow("Colours", value = desk.coloursName, onTapAt = { pickColours() }) {
                        ColourChip(desk)
                    }
                }

                Group("Desktop") {
                    SettingRow(
                        "Hide status bar on desktop",
                        hint = "Clock and icons at the top are hidden while you're on the FoldDesk desktop. Apps still show it",
                        onTapAt = { desk.updateHideStatusBar(!desk.hideStatusBar) },
                    ) { Switch(desk.hideStatusBar) }
                }

                Group("App drawer") {
                    SettingRow(
                        "Open keyboard with app drawer",
                        hint = "Start typing to search straight away. Also on the cover screen's apps page",
                        onTapAt = { desk.updateDrawerKeyboard(!desk.drawerKeyboard) },
                    ) { Switch(desk.drawerKeyboard) }
                    Divider()
                    val count = desk.hiddenApps.count { key -> desk.apps.any { it.key == key } }
                    SettingRow(
                        "Hidden apps",
                        value = when (count) { 0 -> "None"; 1 -> "1 app"; else -> "$count apps" },
                        onTapAt = { pickHiddenApps() },
                    )
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
                        "Swipe right on the first desktop",
                        value = desk.swipeRightApp?.let(desk::app)?.label ?: "Nothing",
                        onTapAt = { pickSwipeRightApp() },
                    )
                    Divider()
                    SettingRow(
                        "Swipe down to close app drawer",
                        hint = "When the app list is scrolled to the top",
                        onTapAt = { desk.updateSwipeDownClosesDrawer(!desk.swipeDownClosesDrawer) },
                    ) { Switch(desk.swipeDownClosesDrawer) }
                }

                Group("Cover screen") {
                    val label = if (desk.ownCoverScreen) "FoldDesk cover screen"
                    else if (!desk.coverUsesOneUi) "FoldDesk desktop (both screens)"
                    else desk.installedLaunchers().firstOrNull { it.first == desk.coverLauncher }?.second ?: "One UI Home"
                    SettingRow("Cover screen launcher", value = label, onTapAt = { pickCoverLauncher() })
                }

                Group("Status") {
                    var isHome by remember { mutableStateOf(Launcher.isDefaultHome(context)) }
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { isHome = Launcher.isDefaultHome(context) }
                    SettingRow("Default home app", hint = "Tap to choose", onTapAt = { Launcher.openHomeSettings(context) }) {
                        BasicText(
                            if (isHome) "FoldDesk" else "Another app",
                            style = TextStyle(color = if (isHome) Dex.accent else Ui.stopped, fontSize = 13.sp),
                        )
                    }
                    Divider()
                    val running = TaskbarService.running
                    val turnOnTaskbar = rememberTurnOnTaskbar()
                    SettingRow(
                        "Taskbar service",
                        hint = if (running) null else "Tap to turn it on in Accessibility settings",
                        onTapAt = if (running) null else { _ -> turnOnTaskbar() },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val color = if (running) Ui.running else Ui.stopped
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(8.dp))
                            BasicText(if (running) "Running" else "Off", style = TextStyle(color = color, fontSize = 13.sp))
                        }
                    }
                }
            }
            AboutGroup(context, tipStore, onFeedback = ::showFeedback, onSupport = { tipStore?.let(::showSupport) })
            Spacer(Modifier.height(24.dp))
        }

        // The taskbar overlay is drawn over the bottom of this window, so action
        // sheets rising from the bottom start above it.
        val overTaskbar = TaskbarService.running && desk.taskbarSide == TaskbarSide.Bottom &&
            FoldDeskApp.isInnerScreen(LocalConfiguration.current)
        SheetHost(
            sheet,
            bottomInset = if (overTaskbar) desk.taskbarSize.bar.dp else 0.dp,
            onDismiss = { sheet?.buttons?.first()?.second?.invoke() },
        )
    }
}

private const val REPO = "https://github.com/azayn-labs/fold-desk"
private const val REDDIT_THREAD = "https://www.reddit.com/r/GalaxyFold/comments/1xgvkgb/"
private const val FEEDBACK_EMAIL = "info@azayn.com"
private const val KOFI = "https://ko-fi.com/azayn"

/**
 * Version and where to get updates, notes and help. FoldDesk has no internet
 * permission, so "Check for updates" just opens the latest release in the browser.
 * The Play version updates itself, and Play doesn't allow outside payment links,
 * so its "Support FoldDesk" takes tips through Play billing instead of Ko-fi.
 */
@Composable
private fun AboutGroup(
    context: android.content.Context,
    tips: TipStore?,
    onFeedback: () -> Unit,
    onSupport: () -> Unit,
) {
    val play = BuildConfig.PLAY_STORE
    // Hints under the titles; the version and the arrows sit on the right.
    val arrow: @Composable () -> Unit = { BasicText("›", style = TextStyle(color = Dex.subtle, fontSize = 20.sp)) }
    Group("About") {
        SettingRow("Version", hint = if (play) "Updates come from Google Play" else "Free version from GitHub") {
            BasicText(BuildConfig.VERSION_NAME, style = TextStyle(color = Dex.accent, fontSize = 14.sp))
        }
        if (!play) {
            Divider()
            SettingRow("Check for updates", hint = "Opens the latest release on GitHub",
                onTapAt = { Launcher.openLink(context, "$REPO/releases/latest") }, trailing = arrow)
        }
        Divider()
        SettingRow("What's new", hint = "Release notes for every version",
            onTapAt = { Launcher.openLink(context, "$REPO/releases") }, trailing = arrow)
        Divider()
        SettingRow("Feedback and bug reports", hint = "GitHub issues, the Reddit thread or email",
            onTapAt = { onFeedback() }, trailing = arrow)
        if (!play) {
            Divider()
            SettingRow("Support FoldDesk", hint = "ko-fi.com/azayn",
                onTapAt = { Launcher.openLink(context, KOFI) }, trailing = arrow)
        } else if (tips != null) {
            Divider()
            SettingRow(
                "Support FoldDesk",
                hint = if (tips.supported) "♥ Thanks for supporting FoldDesk" else "FoldDesk is free. Leave a tip if you like it",
                onTapAt = { onSupport() }, trailing = arrow,
            )
        }
    }
}

/** A tip in the support sheet: emoji tile, name, and Play's price on the right. */
@Composable
private fun TipRow(tip: Tip, onClick: () -> Unit) {
    val (emoji, name, hint) = when (tip.id) {
        "tip_coffee" -> Triple("☕", "A coffee", "Small thanks")
        "tip_lunch" -> Triple("🍕", "A lunch", "Big thanks")
        else -> Triple("🎉", "A great day", "Huge thanks")
    }
    HoverBox(corner = 0, modifier = Modifier.fillMaxWidth().deskGestures(onTap = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Dex.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { BasicText(emoji, style = TextStyle(fontSize = 18.sp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(name, style = TextStyle(color = Dex.text, fontSize = 15.sp))
                BasicText(hint, style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
            }
            BasicText(tip.price, style = TextStyle(color = Dex.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
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
    /** Set for wide controls: they move under the title when space is short. */
    wide: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    var anchor by remember { mutableStateOf(Offset.Zero) }
    val config = LocalConfiguration.current
    val stacked = wide && config.screenWidthDp / config.fontScale < 520
    HoverBox(
        corner = 0,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anchor = it.boundsInRoot().bottomLeft }
            .then(if (onTapAt != null) Modifier.deskGestures(onTap = { onTapAt(anchor) }) else Modifier),
    ) {
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
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
        }
        if (stacked) {
            // Wide controls (segments) on a narrow screen or with big text:
            // title on top, control underneath, so neither gets squeezed.
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                text(Modifier)
                if (trailing != null) {
                    Spacer(Modifier.height(10.dp))
                    trailing()
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                text(Modifier.weight(1f))
                if (trailing != null) {
                    Spacer(Modifier.width(14.dp))
                    trailing()
                }
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
private fun SheetHost(sheet: Sheet?, bottomInset: Dp, onDismiss: () -> Unit) {
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
                Panel(26.dp, Modifier.padding(bottom = bottomInset + 12.dp).width(minOf(560.dp, maxWidth - 24.dp))) {
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

/** Every app with a tick box; ticked (hidden) apps are listed first. */
@Composable
private fun HiddenAppsPicker(desk: DeskState, query: MutableState<String>) {
    val apps = desk.apps
    AppSearchField(query)
    val q = query.value.trim().lowercase()
    val matching = apps.filter { q.isEmpty() || q in it.label.lowercase() }
    // Sorted by what was hidden when the sheet opened, so rows don't jump while ticking.
    val hiddenAtOpen = remember { desk.hiddenApps }
    val (hidden, shown) = matching.partition { it.key in hiddenAtOpen }
    if (hidden.isNotEmpty()) PickerSection("Hidden · ${hidden.size}")
    hidden.forEach { HiddenAppRow(desk, it) }
    if (shown.isNotEmpty()) PickerSection("All apps")
    shown.forEach { HiddenAppRow(desk, it) }
}

/** "Nothing", then every app, the Google app first: it's the one that gets you Discover. */
@Composable
private fun SwipeAppPicker(desk: DeskState, query: MutableState<String>, onPick: (String?) -> Unit) {
    OptionRow("Nothing", selected = desk.swipeRightApp == null) { onPick(null) }
    AppSearchField(query)
    val q = query.value.trim().lowercase()
    val (google, rest) = desk.apps.filter { q.isEmpty() || q in it.label.lowercase() }
        .partition { it.pkg == GOOGLE_APP }
    for (app in google + rest) {
        OptionRow(app.label, selected = app.key == desk.swipeRightApp, icon = app.icon) { onPick(app.key) }
    }
}

@Composable
private fun AppSearchField(query: MutableState<String>) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(Dex.field).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (query.value.isEmpty()) BasicText("Search apps", style = TextStyle(color = Dex.subtle, fontSize = 14.sp))
            BasicTextField(
                value = query.value,
                onValueChange = { query.value = it },
                singleLine = true,
                textStyle = TextStyle(color = Dex.text, fontSize = 14.sp),
                cursorBrush = SolidColor(Dex.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

@Composable
private fun PickerSection(title: String) {
    BasicText(
        title,
        style = TextStyle(color = Dex.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun HiddenAppRow(desk: DeskState, app: AppEntry) {
    val on = desk.isHidden(app)
    HoverBox(
        corner = 0,
        modifier = Modifier.fillMaxWidth().deskGestures(onTap = {
            desk.updateHiddenApps(if (on) desk.hiddenApps - app.key else desk.hiddenApps + app.key)
        }),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(app.icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            BasicText(app.label, style = TextStyle(color = Dex.text, fontSize = 15.sp), modifier = Modifier.weight(1f))
            Checkbox(on)
        }
    }
}

@Composable
private fun Checkbox(on: Boolean) {
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
            .then(if (on) Modifier.background(Dex.accent) else Modifier.border(2.dp, Dex.subtle, RoundedCornerShape(6.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (on) BasicText("✓", style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun OptionRow(
    label: String,
    /** Null for rows that open something rather than pick a value: no radio button. */
    selected: Boolean?,
    icon: ImageBitmap? = null,
    hint: String? = null,
    tag: String? = null,
    onClick: () -> Unit,
) {
    HoverBox(corner = 0, modifier = Modifier.fillMaxWidth().deskGestures(onTap = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Image(it, contentDescription = null, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(label, style = TextStyle(color = Dex.text, fontSize = 15.sp))
                    tag?.let {
                        BasicText(
                            it,
                            style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp))
                                .background(Dex.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                hint?.let { BasicText(it, style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp)) }
            }
            if (selected != null) Radio(selected)
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
