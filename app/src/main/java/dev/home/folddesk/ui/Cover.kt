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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
fun CoverScreen(shell: ShellController, homePresses: Int) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(homePresses) { pager.animateScrollToPage(0) }
    var visits by remember { mutableIntStateOf(0) }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage == 0) visits++ }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { visits++ }
    BackHandler(enabled = pager.currentPage == 1) { scope.launch { pager.animateScrollToPage(0) } }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
        Box(Modifier.fillMaxSize().padding(top = top + 6.dp, bottom = bottom)) {
            if (page == 0) {
                Glance(shell)
                PopupLayer(shell, Modifier.fillMaxSize(), dragTargets = false)
            } else {
                // Keyed on visits, so the search starts empty each time.
                key(visits) { CoverApps(shell) }
            }
        }
    }
}

private val rowShape = RoundedCornerShape(16.dp)

/** Rows sit straight on the wallpaper, so they borrow the taskbar's tint. */
@Composable
private fun rowBackground() = Dex.bar.copy(alpha = 0.55f)

// --- First page: notifications, clock bar, calendar, dock ---

@Composable
private fun Glance(shell: ShellController) {
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
            Box(Modifier.fillMaxWidth().weight(split)) { NotificationsSection(desk) }
            ClockBar(
                desk, now,
                Modifier.height(barHeight).draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> split = (split + delta / freePx).coerceIn(0.12f, 0.88f) },
                    onDragStopped = { desk.updateCoverSplit(split) },
                ),
            )
            Box(Modifier.fillMaxWidth().weight(1f - split)) { CalendarSection(now, resumes) }
            Dock(desk, Modifier.height(dockHeight))
        }
        // Hint that the app list is a swipe away.
        Box(
            Modifier.align(Alignment.CenterEnd).size(width = 4.dp, height = 80.dp)
                .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)).background(Dex.subtle.copy(alpha = 0.5f)),
        )
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
private fun NotificationsSection(desk: DeskState) {
    val context = LocalContext.current
    val list = desk.coverNotifications
    val listening = remember(list) {
        val me = ComponentName(context, NotificationWatcher::class.java)
        context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(me)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
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
            else -> items(list, key = { it.pkg }) { NotificationRow(desk, it) }
        }
    }
}

@Composable
private fun NotificationRow(desk: DeskState, n: CoverNotification) {
    val context = LocalContext.current
    val app = desk.appForPackage(n.pkg)
    // Not in the app list (a system app, say): ask the system for its name and icon.
    val fallback = remember(n.pkg) {
        if (app != null) null else runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(n.pkg, 0)
            pm.getApplicationLabel(info).toString() to info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    val appLabel = app?.label ?: fallback?.first
    val appIcon = app?.icon ?: fallback?.second
    val stripe = if (n.color != 0) Color(n.color).copy(alpha = 1f) else Dex.accent
    var offset by remember(n.pkg) { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        Row(
            Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .alpha((1f - offset / width).coerceIn(0f, 1f))
                .fillMaxWidth()
                .clip(rowShape)
                .background(rowBackground())
                // Swipe right to dismiss; leftward drags are left for the pager.
                .pointerInput(n.keys) {
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
                                NotificationWatcher.dismiss(n.keys)
                            } else {
                                offset = 0f
                            }
                        }
                    }
                }
                .deskGestures(onTap = { n.open(context) { app?.let { Launcher.open(context, it, windowed = false) } } }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(58.dp).background(stripe))
            Spacer(Modifier.width(10.dp))
            appIcon?.let { Image(it, contentDescription = null, modifier = Modifier.size(30.dp)) }
                ?: Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(stripe))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Title and badge take what's left; the app name stays flush right.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            n.title.ifEmpty { appLabel ?: n.pkg },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = Dex.text, fontSize = 14.sp),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (n.count > 1) {
                            BasicText(
                                n.count.toString(),
                                style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(stripe)
                                    .padding(horizontal = 6.dp),
                            )
                        }
                    }
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
            }
            Spacer(Modifier.width(12.dp))
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
private fun Dock(desk: DeskState, modifier: Modifier) {
    val context = LocalContext.current
    val apps = remember(desk.pinned, desk.apps) {
        desk.pinned.mapNotNull { key -> desk.apps.firstOrNull { it.key == key } }.take(5)
    }
    if (apps.isEmpty()) {
        Spacer(modifier)
        return
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Panel(24.dp, Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (app in apps) {
                    Image(
                        app.icon, contentDescription = app.label,
                        modifier = Modifier.size(44.dp).deskGestures(onTap = { Launcher.open(context, app, windowed = false) }),
                    )
                }
            }
        }
    }
}

// --- Second page: all apps ---

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
    // Where each letter's header sits in the list, for the scroller.
    val headerIndex = remember(groups) {
        var i = 0
        groups.mapValues { (_, apps) -> i.also { i += apps.size + 1 } }
    }
    val list = rememberLazyListState()

    // A light tint behind the whole list, like the app drawer's panel.
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Dex.bar.copy(alpha = 0.6f))) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(42.dp)
                .clip(RoundedCornerShape(21.dp)).background(rowBackground()).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) BasicText("Search apps", style = TextStyle(color = Dex.subtle, fontSize = 14.sp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Dex.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Dex.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = list) {
                groups.forEach { (letter, apps) ->
                    item(key = "header-$letter") {
                        BasicText(
                            letter.toString(),
                            style = TextStyle(color = Dex.accent, fontSize = 12.sp),
                            modifier = Modifier.padding(start = 26.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                    items(apps, key = { it.key }) { CoverAppRow(it) { Launcher.open(context, it, windowed = false) } }
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
    }
}

@Composable
private fun CoverAppRow(app: AppEntry, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().deskGestures(onTap = onOpen).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        BasicText(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Dex.text, fontSize = 15.sp))
    }
}
