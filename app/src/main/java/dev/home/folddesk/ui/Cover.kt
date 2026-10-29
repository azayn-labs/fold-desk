package dev.home.folddesk.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Dp
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.PointerEventPass
import dev.home.folddesk.CoverRow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.gestures.detectDragGestures
import dev.home.folddesk.Widgets
import dev.home.folddesk.coverDockMax
import dev.home.folddesk.WidgetActions
import dev.home.folddesk.DeskWidgetView
import dev.home.folddesk.CoverWidget
import dev.home.folddesk.dropCoverWidget
import dev.home.folddesk.freeCoverSpot
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import kotlin.math.min
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.home.folddesk.AppEntry
import dev.home.folddesk.CoverCalendar
import dev.home.folddesk.CoverEvent
import dev.home.folddesk.CoverNotification
import dev.home.folddesk.DeskState
import dev.home.folddesk.Launcher
import dev.home.folddesk.NotificationWatcher
import dev.home.folddesk.ShellController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FoldDesk's own cover screen, after Larva Labs' SlideScreen: notifications on
 * top, a clock bar that slides to share the room, today's calendar below, and
 * the app list one swipe away.
 */
@Composable
fun CoverScreen(shell: ShellController, homePresses: Int, widgetActions: WidgetActions) {
    // Widgets ← main → apps; the main page is in the middle.
    val pager = rememberPagerState(initialPage = MAIN_PAGE) { 3 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(homePresses) { pager.animateScrollToPage(MAIN_PAGE) }
    var visits by remember { mutableIntStateOf(0) }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage == MAIN_PAGE) visits++ }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { visits++; shell.coverRearranging = null }
    BackHandler(enabled = pager.currentPage != MAIN_PAGE) { scope.launch { pager.animateScrollToPage(MAIN_PAGE) } }
    // Declared after, so it goes first: Back closes an open menu before leaving the page.
    BackHandler(enabled = shell.hasPopup) { shell.closePopups() }
    BackHandler(enabled = shell.coverRearranging != null && !shell.hasPopup) { shell.coverRearranging = null }
    // A menu (or rearranging) belongs to the page it was opened on.
    LaunchedEffect(pager.currentPage) { shell.closePopups(); shell.coverRearranging = null }
    // Editing widgets: sideways drags are for resizing, not for changing page.
    var widgetsEditing by remember { mutableStateOf(false) }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !widgetsEditing && shell.coverRearranging == null) { page ->
            Box(Modifier.fillMaxSize().padding(top = top + 18.dp, bottom = bottom)) {
                when (page) {
                    MAIN_PAGE -> {
                        Glance(shell, visits)
                        PopupLayer(shell, Modifier.fillMaxSize(), dragTargets = false)
                    }
                    WIDGETS_PAGE -> key(visits) { CoverWidgets(shell, widgetActions, onEditing = { widgetsEditing = it }) }
                    // Keyed on visits, so the search starts empty each time.
                    else -> {
                        key(visits) { CoverApps(shell) }
                        PopupLayer(shell, Modifier.fillMaxSize(), dragTargets = false)
                    }
                }
            }
        }
        PageDots(pager.currentPage, Modifier.align(Alignment.TopCenter).padding(top = top + 4.dp))
    }
}

private const val WIDGETS_PAGE = 0
private const val MAIN_PAGE = 1

@Composable
private fun PageDots(current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 3) {
            Box(
                Modifier.size(width = if (i == current) 16.dp else 6.dp, height = 6.dp).clip(CircleShape)
                    .background(Dex.text.copy(alpha = if (i == current) 1f else 0.35f)),
            )
        }
    }
}

private val rowShape = RoundedCornerShape(16.dp)

/** Rows sit straight on the wallpaper, so they borrow the taskbar's tint. */
@Composable
private fun rowBackground() = Dex.bar.copy(alpha = 0.55f)

// --- First page: notifications, clock bar, calendar, dock ---

@Composable
private fun Glance(shell: ShellController, visits: Int) {
    val desk = shell.desk
    var split by remember { mutableFloatStateOf(desk.coverSplit) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = System.currentTimeMillis(); resumes++ }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = System.currentTimeMillis()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().deskGestures(onMenu = shell::coverMenu)) {
        val barHeight = 112.dp
        val dockHeight = 84.dp
        val freePx = with(LocalDensity.current) { (maxHeight - barHeight - dockHeight).toPx() }.coerceAtLeast(1f)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(split)) { NotificationsSection(desk, visits) }
            ClockBar(
                desk, now,
                Modifier.height(barHeight).draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> split = (split + delta / freePx).coerceIn(0.12f, 0.88f) },
                    onDragStopped = { desk.updateCoverSplit(split) },
                ),
            )
            Box(Modifier.fillMaxWidth().weight(1f - split)) { CalendarSection(now, resumes) }
            if (shell.coverRearranging == CoverRow.Dock) RearrangeBar { shell.coverRearranging = null }
            Dock(shell, Modifier.height(dockHeight))
        }
    }
}

@Composable
private fun SectionHeader(title: String, end: String?, onEnd: (() -> Unit)? = null) {
    // Sits straight on the wallpaper; the shadow keeps it readable on bright ones.
    val style = TextStyle(
        color = Dex.text.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
        shadow = Shadow(Dex.bar, blurRadius = 8f),
    )
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp)) {
        BasicText(title, style = style, modifier = Modifier.weight(1f))
        end?.let {
            BasicText(
                it, style = style,
                modifier = if (onEnd != null) Modifier.deskGestures(onTap = onEnd) else Modifier,
            )
        }
    }
}

@Composable
private fun NoticeRow(text: String, onTap: (() -> Unit)? = null) {
    BasicText(
        text,
        style = TextStyle(color = if (onTap != null) Dex.accent else Dex.subtle, fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth().clip(rowShape).background(rowBackground())
            .then(if (onTap != null) Modifier.deskGestures(onTap = onTap) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    )
}

@Composable
private fun NotificationsSection(desk: DeskState, visits: Int) {
    val context = LocalContext.current
    val list = desk.coverNotifications
    val listening = remember(list) {
        val me = ComponentName(context, NotificationWatcher::class.java)
        context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(me)
    }
    // One app's group open at a time; it closes again when you come back to the page.
    var expanded by remember(visits) { mutableStateOf<String?>(null) }
    val state = rememberLazyListState()
    // Once open, scroll only if the group runs off the bottom, and no further than its top.
    LaunchedEffect(expanded) {
        val key = expanded ?: return@LaunchedEffect
        delay(GROUP_ANIMATION_MS.toLong())
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@LaunchedEffect
        val overflow = item.offset + item.size - info.viewportEndOffset
        if (overflow > 0) state.animateScrollBy(min(overflow, item.offset - info.viewportStartOffset).toFloat())
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { SectionHeader("Notifications", list.size.takeIf { it > 0 }?.toString()) }
        when {
            !listening -> item {
                NoticeRow("Turn on notification access to see them here") {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            list.isEmpty() -> item { NoticeRow("No new notifications") }
            else -> items(list, key = { it.pkg }) { n ->
                // Grows and shrinks smoothly; the rows below follow its size.
                Box(Modifier.animateContentSize(tween(GROUP_ANIMATION_MS))) {
                    if (n.pkg == expanded && n.count > 1) {
                        NotificationGroup(desk, n, onCollapse = { expanded = null })
                    } else {
                        SwipeAway(n.keys, onDismiss = { NotificationWatcher.dismiss(n.keys) }) {
                            NotificationRow(desk, n, onExpand = { expanded = n.pkg })
                        }
                    }
                }
            }
        }
    }
}

private const val GROUP_ANIMATION_MS = 220

/** The app's name and icon, asking the system when it isn't in the app list (a system app, say). */
@Composable
private fun notificationApp(desk: DeskState, pkg: String): Pair<String?, ImageBitmap?> {
    val context = LocalContext.current
    val app = desk.appForPackage(pkg)
    val fallback = remember(pkg) {
        if (app != null) null else runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString() to info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    return (app?.label ?: fallback?.first) to (app?.icon ?: fallback?.second)
}

@Composable
private fun notificationStripe(n: CoverNotification) = if (n.color != 0) Color(n.color).copy(alpha = 1f) else Dex.accent

/** Swipe right to dismiss; leftward drags are left for the pager. */
@Composable
private fun SwipeAway(key: Any, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var offset by remember(key) { mutableFloatStateOf(0f) }
    val dismiss by rememberUpdatedState(onDismiss)
    // Plain layouts only: the group card measures this for its height.
    var width by remember { mutableFloatStateOf(1f) }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
        Box(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .alpha((1f - offset / width).coerceIn(0f, 1f))
                .fillMaxWidth()
                .pointerInput(key) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragging = false
                        var dx = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val step = change.positionChange().x
                            dx += step
                            if (!dragging && dx > viewConfiguration.touchSlop &&
                                dx > abs(change.position.y - down.position.y)
                            ) dragging = true
                            if (dragging) {
                                offset = (offset + step).coerceAtLeast(0f)
                                change.consume()
                            }
                        }
                        if (dragging) {
                            if (offset > width * 0.35f) {
                                offset = width
                                dismiss()
                            } else {
                                offset = 0f
                            }
                        }
                    }
                },
        ) { content() }
    }
}

@Composable
private fun NotificationRow(desk: DeskState, n: CoverNotification, onExpand: () -> Unit) {
    val context = LocalContext.current
    val (appLabel, appIcon) = notificationApp(desk, n.pkg)
    val stripe = notificationStripe(n)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(rowShape)
            .background(rowBackground())
            .deskGestures(onTap = { n.open(context) { desk.appForPackage(n.pkg)?.let { Launcher.open(context, it, windowed = false) } } }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).heightIn(min = 58.dp).fillMaxHeight().background(stripe))
        Spacer(Modifier.width(10.dp))
        appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(30.dp)) }
            ?: Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(stripe))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The title takes what's left; the app name stays flush right.
                BasicText(
                    n.title.ifEmpty { appLabel ?: n.pkg },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.text, fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    appLabel.orEmpty(), maxLines = 1,
                    style = TextStyle(color = Dex.subtle, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (n.text.isNotEmpty()) {
                BasicText(
                    n.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (n.count > 1) {
                BasicText(
                    "${n.count - 1} more",
                    style = TextStyle(color = Dex.text, fontSize = 11.5.sp),
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Dex.text.copy(alpha = 0.12f))
                        // Its own tap, consumed, so the row doesn't open the newest as well.
                        .pointerInput(Unit) { detectTapGestures { onExpand() } }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (n.count > 1) {
            ExpandArrow(expanded = false, onTap = onExpand, Modifier.padding(start = 4.dp, end = 6.dp))
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** Opens or closes a group; on the right of the card, so it reads as expandable at a glance. */
@Composable
private fun ExpandArrow(expanded: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Image(
        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) "Collapse" else "Expand",
        colorFilter = ColorFilter.tint(Dex.subtle),
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Dex.text.copy(alpha = 0.08f))
            // Consumed, so the row doesn't open the newest as well.
            .pointerInput(Unit) { detectTapGestures { onTap() } }
            .padding(6.dp),
    )
}

/** An app's notifications, opened out from its row. */
@Composable
private fun NotificationGroup(desk: DeskState, n: CoverNotification, onCollapse: () -> Unit) {
    val context = LocalContext.current
    val (appLabel, appIcon) = notificationApp(desk, n.pkg)
    val stripe = notificationStripe(n)
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(18.dp)).background(rowBackground())) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().deskGestures(onTap = onCollapse).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(22.dp)) }
                BasicText(
                    "${appLabel ?: n.pkg} · ${n.count}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                BasicText(
                    "Clear all",
                    style = TextStyle(color = Dex.accent, fontSize = 13.sp),
                    modifier = Modifier.deskGestures(onTap = { NotificationWatcher.dismiss(n.keys) }).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                ExpandArrow(expanded = true, onTap = onCollapse, Modifier.padding(start = 4.dp))
            }
            for (item in n.items) {
                key(item.key) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Dex.text.copy(alpha = 0.06f)))
                    SwipeAway(item.key, onDismiss = { NotificationWatcher.dismiss(listOf(item.key)) }) {
                        Column(
                            Modifier.fillMaxWidth()
                                .deskGestures(onTap = { item.open(context) { desk.appForPackage(n.pkg)?.let { Launcher.open(context, it, windowed = false) } } })
                                .padding(start = 42.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    item.title.ifEmpty { appLabel ?: n.pkg }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = Dex.text, fontSize = 14.sp),
                                    modifier = Modifier.weight(1f),
                                )
                                BasicText(
                                    timeFormat.format(item.postTime),
                                    style = TextStyle(color = Dex.subtle, fontSize = 11.5.sp),
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            if (item.text.isNotEmpty()) {
                                BasicText(
                                    item.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp),
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ClockBar(desk: DeskState, now: Long, modifier: Modifier) {
    val context = LocalContext.current
    val time = remember(now) { DateFormat.getTimeFormat(context).format(now) }
    val date = remember(now) {
        java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), Locale.getDefault())
            .format(now)
    }
    Panel(22.dp, modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 8.dp)) {
            BasicText(time, style = TextStyle(color = Dex.text, fontSize = 50.sp, fontWeight = FontWeight.Light))
            Row(Modifier.fillMaxWidth()) {
                BasicText(date, style = TextStyle(color = Dex.subtle, fontSize = 13.sp), modifier = Modifier.weight(1f))
                desk.batteryLevel?.let {
                    BasicText(
                        if (desk.charging) "$it% ⚡" else "$it%",
                        style = TextStyle(color = Dex.subtle, fontSize = 13.sp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Dex.subtle.copy(alpha = 0.6f)),
            )
        }
    }
}

@Composable
private fun CalendarSection(now: Long, resumes: Int) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(CoverCalendar.hasPermission(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var events by remember { mutableStateOf<List<CoverEvent>>(emptyList()) }
    // Reload on resume and every few minutes; the minute ticker alone would be wasteful.
    LaunchedEffect(granted, resumes, now / 300_000) {
        granted = CoverCalendar.hasPermission(context)
        if (granted) events = withContext(Dispatchers.IO) { CoverCalendar.load(context) }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { SectionHeader("Today", "Calendar", onEnd = { CoverCalendar.openCalendar(context) }) }
        when {
            !granted -> item { NoticeRow("Show your calendar here") { ask.launch(Manifest.permission.READ_CALENDAR) } }
            events.isEmpty() -> item { NoticeRow("Nothing else today or tomorrow") }
            else -> items(events, key = { "${it.id}-${it.begin}" }) { EventRow(it, now) }
        }
    }
}

@Composable
private fun EventRow(event: CoverEvent, now: Long) {
    val context = LocalContext.current
    val today = remember(now) { java.time.LocalDate.now() }
    // Starts tomorrow; anything already running counts as today.
    val tomorrow = event.day > today
    val timeText = DateFormat.getTimeFormat(context).format(event.begin)
    val label = when {
        tomorrow -> "Tomorrow"
        event.allDay -> "All day"
        event.begin <= now -> "Now"
        else -> timeText
    }
    val detail = listOfNotNull(
        if (tomorrow && !event.allDay) timeText else null,
        event.location.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(rowShape).background(rowBackground())
            .deskGestures(onTap = { CoverCalendar.openEvent(context, event) })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (event.color != 0) Color(event.color).copy(alpha = 1f) else Dex.accent))
        Spacer(Modifier.width(10.dp))
        // At least a column's width so titles line up, but never cut short with big text.
        BasicText(
            label, maxLines = 1, style = TextStyle(color = Dex.accent, fontSize = 13.sp),
            modifier = Modifier.widthIn(min = 72.dp).padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                event.title.ifEmpty { "(No title)" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Dex.text, fontSize = 14.sp),
            )
            if (detail.isNotEmpty()) {
                BasicText(
                    detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.subtle, fontSize = 12.sp),
                )
            }
        }
    }
}

/** The taskbar's pinned apps, for the cover screen. */
@Composable
private fun Dock(shell: ShellController, modifier: Modifier) {
    val desk = shell.desk
    val context = LocalContext.current
    val apps = remember(desk.coverDock, desk.apps) {
        desk.coverDock.mapNotNull { key -> desk.apps.firstOrNull { it.key == key } }
    }.take(coverDockMax(context))
    // An icon being dragged sideways to a new place: its key and how far it has moved.
    var moving by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var rowWidth by remember { mutableFloatStateOf(1f) }
    // Menus open above the dock rather than over it.
    var dockTop by remember { mutableFloatStateOf(0f) }
    val rearranging = shell.coverRearranging == CoverRow.Dock
    if (apps.isEmpty()) {
        Spacer(modifier)
        return
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Panel(
            24.dp,
            Modifier.fillMaxSize().onGloballyPositioned { dockTop = it.positionInRoot().y }
                .then(if (rearranging) Modifier.dashedOutline(24.dp) else Modifier),
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp).onSizeChanged { rowWidth = it.width.toFloat() },
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Evenly spaced, so every icon has the same share of the row.
                val slot = rowWidth / apps.size
                val from = moving?.let { (key, _) -> apps.indexOfFirst { it.key == key } } ?: -1
                val to = moving?.let { (_, dx) -> landing(from, dx, slot, apps.lastIndex) } ?: -1
                apps.forEachIndexed { index, app ->
                    val shift = when {
                        index == from -> moving!!.second
                        from < 0 -> 0f
                        index in (from + 1)..to -> -slot
                        index in to until from -> slot
                        else -> 0f
                    }
                    val lifted = index == from
                    Image(
                        app.icon, contentDescription = app.label,
                        // Its own long press, so the page's menu (and a tap on release) stay out of it.
                        modifier = Modifier
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer {
                                translationX = shift
                                if (lifted) { scaleX = 1.12f; scaleY = 1.12f }
                            }
                            .size(44.dp)
                            .then(
                                if (rearranging) {
                                    // Rearranging: a plain drag moves the icon, taps do nothing.
                                    Modifier.rearrangeDrag(
                                        app.key,
                                        onStart = { moving = app.key to 0f },
                                        onDrag = { dx -> moving = moving?.let { (key, x) -> key to x + dx } },
                                        onEnd = {
                                            moving?.let { (key, dx) -> desk.moveCoverDock(key, landing(apps.indexOfFirst { it.key == key }, dx, slot, apps.lastIndex)) }
                                            moving = null
                                        },
                                    )
                                } else {
                                    Modifier.deskGestures(
                                        onTap = { Launcher.open(context, app, windowed = false) },
                                        onMenu = { shell.coverAppMenu(app, Offset(it.x, dockTop), CoverRow.Dock.takeIf { apps.size > 1 }) },
                                        onDragStart = {
                                            shell.closeMenu()
                                            moving = app.key to 0f
                                        },
                                        onDrag = { delta -> moving = moving?.let { (key, dx) -> key to dx + delta.x } },
                                        onDragEnd = {
                                            if (to >= 0) desk.moveCoverDock(app.key, to)
                                            moving = null
                                        },
                                    )
                                },
                            ),
                    )
                }
            }
        }
    }
}

// --- Widget page ---

@Composable
private fun CoverWidgets(shell: ShellController, actions: WidgetActions, onEditing: (Boolean) -> Unit) {
    val desk = shell.desk
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf(false) }
    val latestOnEditing by rememberUpdatedState(onEditing)
    LaunchedEffect(editing, picking) { latestOnEditing(editing || picking) }
    DisposableEffect(Unit) { onDispose { latestOnEditing(false) } }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // A resize in progress, shown before it's saved: widget id to its span.
    var preview by remember { mutableStateOf<CoverWidget?>(null) }
    // A widget being dragged: its id and how far it has moved, in px.
    var moving by remember { mutableStateOf<Pair<Int, Offset>?>(null) }
    BackHandler(enabled = editing || picking) { if (picking) picking = false else { editing = false; selected = null } }
    // Widget long presses come in window coordinates; the menu layer is placed in this box.
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        // The grid fills the screen, so widgets can go anywhere on it, gaps and all.
        val pageHeight = maxHeight
        // The grid fits the screen exactly, so this only scrolls once widgets go further down.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 8.dp, end = 8.dp)) {
                SectionTitle("Widgets", Modifier.weight(1f))
                if (desk.coverWidgets.isNotEmpty()) {
                    SectionTitle(
                        if (editing) "Done" else "Edit",
                        Modifier.deskGestures(onTap = { editing = !editing; selected = null }),
                    )
                }
            }
            val shown = desk.coverWidgets.map { w -> preview?.takeIf { it.id == w.id } ?: w }
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = Widgets.COVER_COLUMNS
                val gap = 8.dp
                val cell = (maxWidth + gap) / cols
                val screenRows = ((pageHeight - HEADER_HEIGHT + gap) / cell).toInt().coerceAtLeast(1)
                val usedRows = shown.maxOfOrNull { it.row + it.h } ?: 0
                val addAt = if (editing || shown.isEmpty()) {
                    freeCoverSpot(shown, if (shown.isEmpty()) cols else 1, 1, cols).let { (c, r) -> IntOffset(c, r) }
                } else {
                    null
                }
                val totalRows = maxOf(screenRows, usedRows, (addAt?.y ?: -1) + 1)
                Box(Modifier.fillMaxWidth().height(cell * totalRows)) {
                    if (editing) GridLines(cols, totalRows, cell)
                    for (widget in shown) {
                        val at = IntOffset(widget.col, widget.row)
                        key(widget.id) {
                            CoverWidgetView(
                                widget,
                                cell = cell,
                                gap = gap,
                                at = at,
                                selected = selected == widget.id,
                                onLongPress = { pos ->
                                    editing = true
                                    shell.coverWidgetMenu(widget.id, pos - origin, actions, onResize = { selected = widget.id })
                                },
                                dragOffset = moving?.takeIf { it.first == widget.id }?.second,
                                onMoveStart = { shell.closeMenu(); selected = null; moving = widget.id to Offset.Zero },
                                onMove = { dx, dy -> moving = moving?.let { it.first to it.second + Offset(dx, dy) } },
                                onMoveEnd = {
                                    val d = moving?.second ?: Offset.Zero
                                    moving = null
                                    val cellPx = with(density) { cell.toPx() }
                                    val col = Math.round(at.x + d.x / cellPx).coerceIn(0, cols - widget.w)
                                    val row = Math.round(at.y + d.y / cellPx).coerceAtLeast(0)
                                    // Whatever it lands on makes way for it.
                                    desk.updateCoverWidgets(dropCoverWidget(desk.coverWidgets, widget.copy(col = col, row = row), cols))
                                },
                                // A resize stops short of other widgets.
                                onResizing = { resized ->
                                    if (desk.coverWidgets.none { it.id != widget.id && it.overlaps(resized) }) preview = resized
                                },
                                onResized = {
                                    val final = preview?.takeIf { it.id == widget.id }
                                    preview = null
                                    if (final != null) desk.updateCoverWidgets(desk.coverWidgets.map { if (it.id == widget.id) final else it })
                                },
                            )
                        }
                    }
                    addAt?.let { at ->
                        val span = if (shown.isEmpty()) cols else 1
                        BasicText(
                            if (shown.isEmpty()) "+ Add a widget" else "+ Add widget",
                            style = TextStyle(color = Dex.text.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center),
                            modifier = Modifier
                                .offset(cell * at.x, cell * at.y)
                                .size(cell * span - gap, cell - gap)
                                .clip(RoundedCornerShape(22.dp))
                                .border(2.dp, Dex.text.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                                .deskGestures(onTap = { picking = true })
                                .wrapContentHeight(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
        PopupLayer(shell, Modifier.fillMaxSize(), dragTargets = false)
        if (picking) {
            Box(Modifier.fillMaxSize().background(Color(0x73000000)).deskGestures(onTap = { picking = false })) {
                WidgetPicker(
                    shell,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                    onAdd = { info -> picking = false; actions.addCoverWidget(info) },
                    onDragOut = {},
                    onDrop = { p -> picking = false; actions.addCoverWidget(p.info) },
                    placing = false,
                    fullWidth = true,
                )
            }
        }
    }
}

private val HEADER_HEIGHT = 28.dp

/** Faint cell outlines while editing. */
@Composable
private fun GridLines(cols: Int, rows: Int, cell: Dp) {
    val color = Dex.text.copy(alpha = 0.07f)
    for (r in 0 until rows) for (c in 0 until cols) {
        Box(
            Modifier.offset(cell * c, cell * r).size(cell - 8.dp)
                .border(1.dp, color, RoundedCornerShape(22.dp)),
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = TextStyle(
            color = Dex.text.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
            shadow = Shadow(Dex.bar, blurRadius = 8f),
        ),
        modifier = modifier,
    )
}

@Composable
private fun CoverWidgetView(
    widget: CoverWidget,
    cell: Dp,
    gap: Dp,
    at: IntOffset,
    selected: Boolean,
    onLongPress: (Offset) -> Unit,
    dragOffset: Offset?,
    onMoveStart: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onMoveEnd: () -> Unit,
    onResizing: (CoverWidget) -> Unit,
    onResized: (CoverWidget) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val info = remember(widget.id) { Widgets.info(widget.id) }
    val (minW, minH) = remember(widget.id) { Widgets.coverMinSpan(info, context) }
    val latestLongPress by rememberUpdatedState(onLongPress)
    val latestMoveStart by rememberUpdatedState(onMoveStart)
    val latestMove by rememberUpdatedState(onMove)
    val latestMoveEnd by rememberUpdatedState(onMoveEnd)
    val shape = RoundedCornerShape(22.dp)
    val width = cell * widget.w - gap
    val height = cell * widget.h - gap
    // Corner drag in progress, in px, and the widget as it was when it started.
    var drag by remember(widget.id) { mutableStateOf<Offset?>(null) }
    var start by remember(widget.id) { mutableStateOf(widget) }
    val cols = Widgets.COVER_COLUMNS
    Box(
        Modifier.offset(cell * at.x, cell * at.y)
            .offset { dragOffset?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } ?: IntOffset.Zero }
            .zIndex(if (dragOffset != null) 1f else 0f)
            .lifted(dragOffset != null, shape)
            .size(width, height)
            .then(if (selected) Modifier.border(2.dp, Dex.accent, shape) else Modifier),
    ) {
        if (info == null) {
            // The widget's app was removed or the widget can't load.
            BasicText(
                "Widget unavailable",
                style = TextStyle(color = Dex.subtle, fontSize = 13.sp),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            AndroidView(
                factory = { ctx -> Widgets.host.createView(ctx, widget.id, info) },
                update = { view ->
                    (view as? DeskWidgetView)?.let { v ->
                        v.onLongPress = { x, y -> latestLongPress(Offset(x, y)) }
                        // Keep holding after the long press and drag to move it.
                        v.onDragStart = { latestMoveStart() }
                        v.onDrag = { dx, dy -> latestMove(dx, dy) }
                        v.onDragEnd = { latestMoveEnd() }
                    }
                    Widgets.reportSize(view as android.appwidget.AppWidgetHostView, width.value, height.value)
                },
                modifier = Modifier.fillMaxSize().clip(shape),
            )
        }
        if (selected) {
            if (drag != null) {
                BasicText(
                    "${widget.w} × ${widget.h}",
                    style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Dex.accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            // Drag a bottom corner to resize, snapping to whole cells. The right one
            // moves the right edge; the left one the left edge, so a widget can
            // sit on either side of the screen.
            for (leftEdge in listOf(false, true)) {
                Box(
                    Modifier.align(if (leftEdge) Alignment.BottomStart else Alignment.BottomEnd)
                        .offset(if (leftEdge) (-10).dp else 10.dp, 10.dp).size(28.dp).clip(CircleShape).background(Dex.accent)
                        .pointerInput(widget.id, cell, leftEdge) {
                            val cellPx = cell.toPx()
                            fun resized(d: Offset): CoverWidget {
                                val h = Math.round(start.h + d.y / cellPx).coerceIn(minH, Widgets.COVER_MAX_ROWS)
                                return if (leftEdge) {
                                    val right = start.col + start.w
                                    val col = Math.round(start.col + d.x / cellPx).coerceIn(0, right - minW)
                                    start.copy(w = right - col, h = h, col = col)
                                } else {
                                    val w = Math.round(start.w + d.x / cellPx).coerceIn(minW, cols - start.col)
                                    start.copy(w = w, h = h)
                                }
                            }
                            detectDragGestures(
                                onDragStart = { start = widget; drag = Offset.Zero },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val d = (drag ?: Offset.Zero) + amount
                                    drag = d
                                    onResizing(resized(d))
                                },
                                onDragEnd = { onResized(resized(drag ?: Offset.Zero)); drag = null },
                                onDragCancel = { onResized(resized(drag ?: Offset.Zero)); drag = null },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(if (leftEdge) "⤢" else "⤡", style = TextStyle(color = Color.White, fontSize = 13.sp))
                }
            }
        }
    }
}

// --- Last page: all apps ---

/** Apps used most recently, as many as fit the width; at the top of the list. */
@Composable
private fun RecentApps(shell: ShellController, recent: List<AppEntry>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        AppIconRow(shell, "Recent", recent, (maxWidth / RECENT_SLOT).toInt().coerceAtLeast(1), scrolls = false)
    }
}

/** Favourites at the bottom, in the order added; scrolls sideways when there are more than fit. */
@Composable
private fun FavouriteApps(shell: ShellController) {
    val desk = shell.desk
    val favourites = remember(desk.coverFavourites, desk.apps, desk.hiddenApps) {
        desk.coverFavourites.mapNotNull { key -> desk.apps.firstOrNull { it.key == key } }.filter { !desk.isHidden(it) }
    }
    if (favourites.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        AppIconRow(shell, null, favourites, (maxWidth / RECENT_SLOT).toInt().coerceAtLeast(1), scrolls = true, row = CoverRow.Favourites)
    }
}

/** Takes whatever sideways scroll a row leaves over, so it doesn't reach the pager. */
private object KeepSideways : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = available.copy(y = 0f)
    override suspend fun onPostFling(consumed: Velocity, available: Velocity) = available.copy(y = 0f)
}

/**
 * A labelled row of app icons, [perRow] to the width. When [row] is being
 * rearranged, a plain drag moves an icon and the row scrolls at its edges.
 */
@Composable
private fun AppIconRow(
    shell: ShellController,
    title: String?,
    apps: List<AppEntry>,
    perRow: Int,
    scrolls: Boolean,
    row: CoverRow? = null,
) {
    val context = LocalContext.current
    val rearranging = row != null && shell.coverRearranging == row
    val scroll = rememberScrollState()
    val view = LocalView.current
    // The icon being dragged; tracked on the row, so scrolling never loses the finger.
    var drag by remember { mutableStateOf<RowDrag?>(null) }
    LaunchedEffect(rearranging) { if (!rearranging) drag = null }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slot = (maxWidth - 24.dp) / perRow
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        val padPx = with(LocalDensity.current) { 6.dp.toPx() }
        val viewPx = with(LocalDensity.current) { (maxWidth - 12.dp).toPx() }
        val from = drag?.let { d -> apps.indexOfFirst { it.key == d.key } } ?: -1
        val dx = drag?.let { it.finger - it.startFinger + scroll.value - it.startScroll } ?: 0f
        val to = if (from >= 0) landing(from, dx, slotPx, apps.lastIndex) else -1
        // Held near an edge: scroll that way; the icon stays under the finger.
        LaunchedEffect(drag != null) {
            while (true) {
                val d = drag ?: break
                val step = when {
                    d.finger < slotPx * 0.6f -> -EDGE_SCROLL_PX
                    d.finger > viewPx - slotPx * 0.6f -> EDGE_SCROLL_PX
                    else -> 0f
                }
                if (step != 0f) scroll.scrollBy(step)
                delay(16)
            }
        }
        Column(Modifier.fillMaxWidth()) {
            if (title != null) {
                BasicText(
                    title,
                    style = TextStyle(color = Dex.accent, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 26.dp, top = 10.dp, bottom = 6.dp),
                )
            } else {
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier.fillMaxWidth()
                    .then(if (rearranging) Modifier.padding(horizontal = 6.dp).dashedOutline(18.dp) else Modifier)
                    .then(
                        if (rearranging) {
                            // A quick swipe scrolls the row; holding still for a moment picks an icon up.
                            Modifier.pointerInput(apps, slotPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val held = withTimeoutOrNull(PICK_UP_MS) {
                                        while (true) {
                                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                                .firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                        }
                                    } == null
                                    if (!held) return@awaitEachGesture
                                    val index = ((down.position.x - padPx + scroll.value) / slotPx).toInt()
                                    val app = apps.getOrNull(index) ?: return@awaitEachGesture
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    drag = RowDrag(app.key, down.position.x, scroll.value, down.position.x)
                                    while (true) {
                                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                            .firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                        drag = drag?.copy(finger = change.position.x)
                                    }
                                    drag?.let { d ->
                                        val keys = apps.map { it.key }.toMutableList()
                                        val at = keys.indexOf(d.key)
                                        val moved = d.finger - d.startFinger + scroll.value - d.startScroll
                                        keys.removeAt(at)
                                        keys.add(landing(at, moved, slotPx, apps.lastIndex), d.key)
                                        shell.desk.reorderCoverFavourites(keys)
                                    }
                                    drag = null
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    // Sideways swipes here are for the row, never for changing page.
                    .then(if (scrolls) Modifier.nestedScroll(KeepSideways).horizontalScroll(scroll) else Modifier)
                    .padding(horizontal = if (rearranging) 6.dp else 12.dp),
            ) {
                (if (scrolls) apps else apps.take(perRow)).forEachIndexed { index, app ->
                    key(app.key) {
                        val feedback = remember { PressFeedback() }
                        val lifted = index == from
                        val shift = when {
                            lifted -> dx
                            from < 0 -> 0f
                            index in (from + 1)..to -> -slotPx
                            index in to until from -> slotPx
                            else -> 0f
                        }
                        Column(
                            Modifier.width(slot)
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer {
                                    translationX = shift
                                    if (lifted) { scaleX = 1.12f; scaleY = 1.12f }
                                }
                                .then(
                                    if (rearranging) {
                                        Modifier
                                    } else {
                                        Modifier.deskGestures(
                                            onTap = { Launcher.open(context, app, windowed = false) },
                                            onMenu = { shell.coverAppMenu(app, it, row.takeIf { apps.size > 1 }) },
                                            feedback = feedback,
                                        )
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(46.dp))
                            BasicText(
                                app.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = Dex.text, fontSize = 11.sp, textAlign = TextAlign.Center),
                                modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Where an icon dragged [dx] from slot [from] lands, in a row of [slot]-wide places. */
private fun landing(from: Int, dx: Float, slot: Float, last: Int) =
    (from + (dx / slot).roundToInt()).coerceIn(0, last)

/** A plain sideways drag, for rows being rearranged; it starts at once, no long press. */
private fun Modifier.rearrangeDrag(key: Any, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) =
    pointerInput(key) {
        detectDragGestures(
            onDragStart = { onStart() },
            onDrag = { change, delta -> change.consume(); onDrag(delta.x) },
            onDragEnd = onEnd,
            onDragCancel = onEnd,
        )
    }

/** The dashed outline that marks a row being rearranged. */
@Composable
private fun Modifier.dashedOutline(radius: Dp): Modifier {
    val color = Dex.accent.copy(alpha = 0.75f)
    return drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
        )
    }
}

/** "Drag icons to move them" and a Done button, above a row being rearranged. */
@Composable
private fun RearrangeBar(onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "Drag icons to move them",
            style = TextStyle(color = Dex.text.copy(alpha = 0.85f), fontSize = 12.5.sp, shadow = Shadow(Dex.bar, blurRadius = 8f)),
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Dex.accent)
                .deskGestures(onTap = onDone).padding(horizontal = 16.dp, vertical = 7.dp),
        ) {
            BasicText("Done", style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

private const val EDGE_SCROLL_PX = 18f
/** How long a finger rests on a favourite before it's picked up instead of scrolling. */
private const val PICK_UP_MS = 200L

/** A favourite being dragged: where the finger and the scroll were at the start, and the finger now. */
private data class RowDrag(val key: String, val startFinger: Float, val startScroll: Int, val finger: Float)

private val RECENT_SLOT = 72.dp
/** More than any cover screen fits in a row; the row takes what fits. */
private const val RECENT_MAX = 8

@Composable
private fun CoverApps(shell: ShellController) {
    val desk = shell.desk
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val groups = remember(desk.apps, desk.hiddenApps, query) {
        val q = query.trim().lowercase()
        desk.apps.filter { !desk.isHidden(it) && (q.isEmpty() || q in it.label.lowercase()) }
            .sortedBy { it.label.lowercase() }
            .groupBy { app -> app.label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#' }
    }
    // The recent apps sit above the letters while not searching. They load a
    // moment after the list, which then jumps back up to show them.
    val recent by produceState<List<AppEntry>?>(null, desk.apps, desk.hiddenApps) {
        value = withContext(Dispatchers.IO) { desk.recentlyUsed(RECENT_MAX) }
    }
    val showRecent = query.isEmpty() && !recent.isNullOrEmpty()
    val list = rememberLazyListState()
    LaunchedEffect(recent != null) { if (recent != null) list.scrollToItem(0) }
    // Where each letter's header sits in the list, for the scroller.
    val headerIndex = remember(groups, showRecent) {
        var i = if (showRecent) 1 else 0
        groups.mapValues { (_, apps) -> i.also { i += apps.size + 1 } }
    }

    // A light tint behind the whole list, like the app drawer's panel.
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Dex.bar.copy(alpha = 0.6f)).imePadding()) {
        // The list on top; search and recent apps at the bottom, within thumb reach.
        Row(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = list) {
                if (showRecent) item(key = "recent") { RecentApps(shell, recent.orEmpty()) }
                groups.forEach { (letter, apps) ->
                    item(key = "header-$letter") {
                        BasicText(
                            letter.toString(),
                            style = TextStyle(color = Dex.accent, fontSize = 12.sp),
                            modifier = Modifier.padding(start = 26.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(apps, key = { it.key }) { app ->
                        CoverAppRow(app, onOpen = { Launcher.open(context, app, windowed = false) }, onMenu = { shell.coverAppMenu(app, it) })
                    }
                }
            }
            Column(
                Modifier.fillMaxHeight().width(22.dp).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                for (letter in groups.keys) {
                    BasicText(
                        letter.toString(),
                        style = TextStyle(color = Dex.subtle, fontSize = 10.sp),
                        modifier = Modifier.deskGestures(onTap = {
                            scope.launch { list.scrollToItem(headerIndex.getValue(letter)) }
                        }),
                    )
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(42.dp)
                // Same as the drawer's search, but on the rows' tint so it reads over the wallpaper.
                .clip(RoundedCornerShape(21.dp)).background(rowBackground()).padding(start = 16.dp, end = 8.dp),
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Image(
                    Icons.Default.Close, contentDescription = "Clear",
                    colorFilter = ColorFilter.tint(Dex.subtle),
                    modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape)
                        .deskGestures(onTap = { query = "" }).padding(6.dp),
                )
            }
        }
        if (query.isEmpty()) {
            if (shell.coverRearranging == CoverRow.Favourites) RearrangeBar { shell.coverRearranging = null }
            FavouriteApps(shell)
        }
    }
}

@Composable
private fun CoverAppRow(app: AppEntry, onOpen: () -> Unit, onMenu: (Offset) -> Unit) {
    val feedback = remember { PressFeedback() }
    Row(
        Modifier.fillMaxWidth().deskGestures(onTap = onOpen, onMenu = onMenu, feedback = feedback)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        BasicText(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Dex.text, fontSize = 15.sp))
    }
}
