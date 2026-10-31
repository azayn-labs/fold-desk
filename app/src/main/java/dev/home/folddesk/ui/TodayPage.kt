package dev.home.folddesk.ui

import android.Manifest
import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import dev.home.folddesk.AppEntry
import dev.home.folddesk.CoverCalendar
import dev.home.folddesk.CoverEvent
import dev.home.folddesk.CoverNotification
import dev.home.folddesk.DeskWidgetView
import dev.home.folddesk.MenuItem
import dev.home.folddesk.MenuState
import dev.home.folddesk.News
import dev.home.folddesk.NewsStory
import dev.home.folddesk.ShellController
import dev.home.folddesk.WidgetActions
import dev.home.folddesk.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Left of the first desktop, laid out like the Google page on other launchers:
 * the day at a glance, a search bar, recent apps, then big cards. FoldDesk has
 * no internet, so the news is what news apps sent as notifications, plus any
 * widget added here.
 */
@Composable
fun TodayPage(
    shell: ShellController,
    actions: WidgetActions?,
    progress: () -> Float,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    contentPadding: PaddingValues,
) {
    val desk = shell.desk
    val context = LocalContext.current
    val open = shell.todayOpen
    var query by remember { mutableStateOf("") }
    val search = remember { FocusRequester() }
    FocusSearch(open && desk.drawerKeyboard, search)
    LaunchedEffect(open) { if (!open) query = "" }

    val recent by produceState(emptyList<AppEntry>(), open) {
        if (open) value = withContext(Dispatchers.IO) { desk.recentlyUsed(RECENT_CHIPS) }
    }
    var calendarGranted by remember { mutableStateOf(CoverCalendar.hasPermission(context)) }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarGranted = it }
    val events by produceState(emptyList<CoverEvent>(), open, calendarGranted) {
        // The same event in two calendars (e.g. holidays) shows once.
        if (open && calendarGranted) value = withContext(Dispatchers.IO) {
            CoverCalendar.load(context).distinctBy { Triple(it.title, it.begin, it.allDay) }
        }
    }
    // Filtered again: stories saved before an app stopped counting as news.
    val stories = if (desk.newsCards) desk.newsStories.filter { News.isNewsApp(context.packageManager, it.pkg) } else emptyList()
    val others = desk.coverNotifications.filter { !News.isNewsApp(context.packageManager, it.pkg) }
    val suggestion = remember(desk.pageWidgets, open) {
        if (desk.pageWidgets.isEmpty()) News.suggestedWidget(context) else null
    }

    // One UI's panel blur over the wallpaper, like the notification panel. It
    // stays put and fades in with the swipe; the content slides over it.
    Panel(0.dp, Modifier.fillMaxSize(), blurAmount = progress()) {}
    Box(
        Modifier
            .fillMaxSize()
            .pageDrag(direction = -1, enabled = { true }, onDrag = onDrag, onRelease = onRelease)
            .graphicsLayer { translationX = (progress() - 1f) * size.width },
    ) {
        LazyColumn(
            Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxHeight(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "glance") { Glance(events, stories.size) }
            item(key = "search") {
                SearchPill(query, { query = it }, search) {
                    val first = desk.apps.firstOrNull { matches(it, query) }
                    if (first != null) shell.open(first) else webSearch(context, query)
                }
            }
            if (query.isNotBlank()) {
                val found = desk.apps.filter { !desk.isHidden(it) && matches(it, query) }.take(SEARCH_RESULTS)
                items(found, key = { "app-${it.key}" }) { app ->
                    ResultRow(app.icon, app.label) { shell.open(app) }
                }
                item(key = "web") { ResultRow(null, "Search the web for “${query.trim()}”") { webSearch(context, query) } }
                return@LazyColumn
            }
            if (recent.isNotEmpty()) item(key = "recent") { RecentChips(recent) { shell.open(it) } }

            // News first, with your day in between, like a feed.
            stories.firstOrNull()?.let { s -> item(key = "story-${s.key}") { StoryCard(shell, s) } }
            item(key = "calendar") {
                CalendarCard(events, calendarGranted) { askCalendar.launch(Manifest.permission.READ_CALENDAR) }
            }
            stories.getOrNull(1)?.let { s -> item(key = "story-${s.key}") { StoryCard(shell, s) } }
            if (others.isNotEmpty()) item(key = "notifications") { NotificationsCard(others) }
            items(stories.drop(2), key = { "story-${it.key}" }) { StoryCard(shell, it) }
            if (desk.newsCards && stories.isEmpty()) {
                item(key = "noNews") {
                    Hint("Headlines your news apps send as notifications show up here as cards.")
                }
            }
            items(desk.pageWidgets, key = { "widget-$it" }) { id -> PageWidget(shell, actions, id) }
            if (suggestion != null && actions != null) {
                item(key = "suggest") {
                    val label = remember(suggestion) { suggestion.loadLabel(context.packageManager) }
                    SuggestCard("More stories? Add the $label widget", "Add widget") { actions.addPageWidget(suggestion) }
                }
            }
        }
        // Edit, top right: what shows on the page.
        var editAt by remember { mutableStateOf(Offset.Zero) }
        BasicText(
            "Edit",
            style = TextStyle(color = Dex.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(contentPadding).padding(end = 18.dp)
                .onGloballyPositioned { editAt = it.boundsInRoot().bottomLeft }
                .deskGestures(onTap = { editMenu(shell, actions, editAt, context.packageManager) }),
        )
    }
}

private fun editMenu(shell: ShellController, actions: WidgetActions?, at: Offset, pm: android.content.pm.PackageManager) {
    val desk = shell.desk
    val widgets = desk.pageWidgets.map { id ->
        val label = Widgets.info(id)?.loadLabel(pm) ?: "widget"
        MenuItem("Remove $label") { actions?.removePageWidget(id) }
    }
    shell.showMenu(
        MenuState(
            at = at,
            groups = listOfNotNull(
                listOf(MenuItem("News cards", checked = desk.newsCards) { desk.updateNewsCards(!desk.newsCards) }),
                widgets.takeIf { it.isNotEmpty() },
            ),
        ),
    )
}

private fun matches(app: AppEntry, query: String) = query.isNotBlank() && query.trim().lowercase() in app.label.lowercase()

private fun webSearch(context: android.content.Context, query: String) {
    if (query.isBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query.trim())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: android.content.ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query.trim())))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private val cardShape = RoundedCornerShape(20.dp)

/** Cards and the search bar: see-through, so the blurred wallpaper shows a little. */
@Composable
private fun cardColor() = LocalDexColors.current.card.copy(alpha = 0.62f)

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(cardShape).background(cardColor()).then(modifier)) { content() }
}

@Composable
private fun Glance(events: List<CoverEvent>, stories: Int) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    val next = events.firstOrNull { !it.allDay && it.day == today && it.begin > System.currentTimeMillis() }
    val line = listOfNotNull(
        next?.let { "Next: ${it.title.ifEmpty { "(No title)" }} at ${DateFormat.getTimeFormat(context).format(it.begin)}" },
        when (stories) { 0 -> null; 1 -> "1 story"; else -> "$stories stories" },
    ).joinToString(" · ")
    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        BasicText(
            today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = TextStyle(color = Dex.text, fontSize = 26.sp),
        )
        if (line.isNotEmpty()) {
            BasicText(line, style = TextStyle(color = Dex.subtle, fontSize = 14.sp), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SearchPill(query: String, onQuery: (String) -> Unit, focus: FocusRequester, onGo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(cardColor())
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(Icons.Default.Search, contentDescription = null, colorFilter = ColorFilter.tint(Dex.subtle), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) BasicText("Search apps and the web", style = TextStyle(color = Dex.subtle, fontSize = 16.sp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Dex.text, fontSize = 16.sp),
                cursorBrush = SolidColor(Dex.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

@Composable
private fun ResultRow(icon: ImageBitmap?, label: String, onTap: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).deskGestures(onTap = onTap).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(34.dp))
        else Image(Icons.Default.Search, contentDescription = null, colorFilter = ColorFilter.tint(Dex.subtle), modifier = Modifier.size(34.dp).padding(6.dp))
        Spacer(Modifier.width(14.dp))
        BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Dex.text, fontSize = 15.sp))
    }
}

/** No horizontal scroll: sideways swipes on the page close it. */
@Composable
private fun RecentChips(apps: List<AppEntry>, onOpen: (AppEntry) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (app in apps) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(cardColor())
                    .deskGestures(onTap = { onOpen(app) }).padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(app.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                BasicText(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Dex.text, fontSize = 12.5.sp))
            }
        }
    }
}

@Composable
private fun rememberPackageIcon(pkg: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull()
    }
}

@Composable
private fun rememberPackageLabel(pkg: String): String {
    val context = LocalContext.current
    return remember(pkg) {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }
}

@Composable
private fun CardHeader(pkg: String, text: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        rememberPackageIcon(pkg)?.let { Image(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp))
    }
}

@Composable
private fun StoryCard(shell: ShellController, story: NewsStory) {
    val context = LocalContext.current
    var at by remember { mutableStateOf(Offset.Zero) }
    val label = rememberPackageLabel(story.pkg)
    val ago = DateUtils.getRelativeTimeSpanString(story.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    // Wide pictures go on top; small square ones (often the app's logo) beside the text.
    val picture = story.picture
    val wide = picture != null && picture.width >= picture.height * 1.3f
    Card(
        Modifier
            .onGloballyPositioned { at = it.boundsInRoot().topLeft }
            .deskGestures(
                onTap = {
                    CoverNotification.send(context, story.intent, listOf(story.key), story.autoCancel) {
                        context.packageManager.getLaunchIntentForPackage(story.pkg)?.let {
                            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    shell.desk.removeStory(story.key)
                },
                onMenu = { p ->
                    shell.showMenu(
                        MenuState(
                            at = p,
                            groups = listOf(
                                listOf(MenuItem("Remove from page") { shell.desk.removeStory(story.key) }),
                                listOf(MenuItem("Turn off news cards") { shell.desk.updateNewsCards(false) }),
                            ),
                        ),
                    )
                },
            ),
    ) {
        if (wide && picture != null) {
            Image(picture, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(190.dp))
        }
        CardHeader(story.pkg, "$label · $ago")
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)) {
            Column(Modifier.weight(1f)) {
                BasicText(story.title, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                if (story.text.isNotEmpty()) {
                    BasicText(story.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = Dex.subtle, fontSize = 13.5.sp), modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (!wide && picture != null) {
                Spacer(Modifier.width(12.dp))
                Image(picture, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
            }
        }
    }
}

@Composable
private fun CalendarCard(events: List<CoverEvent>, granted: Boolean, onAsk: () -> Unit) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }
    Card {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            BasicText("Today", style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
            when {
                !granted -> BasicText(
                    "Show your calendar here", style = TextStyle(color = Dex.accent, fontSize = 15.sp),
                    modifier = Modifier.deskGestures(onTap = onAsk).padding(vertical = 6.dp),
                )
                events.isEmpty() -> BasicText(
                    "Nothing else today or tomorrow", style = TextStyle(color = Dex.subtle, fontSize = 15.sp),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                else -> events.take(CALENDAR_ROWS).forEach { event ->
                    val time = when {
                        event.day > today -> "Tmrw"
                        event.allDay -> "All day"
                        else -> DateFormat.getTimeFormat(context).format(event.begin)
                    }
                    Row(
                        Modifier.fillMaxWidth().deskGestures(onTap = { CoverCalendar.openEvent(context, event) }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(time, style = TextStyle(color = Dex.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.width(64.dp))
                        BasicText(event.title.ifEmpty { "(No title)" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = Dex.text, fontSize = 15.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsCard(list: List<CoverNotification>) {
    val context = LocalContext.current
    Card {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            BasicText("Notifications · ${list.size}", style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp))
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
            for (n in list.take(NOTIFICATION_ROWS)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).deskGestures(onTap = { n.open(context) {} })
                        .padding(horizontal = 4.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rememberPackageIcon(n.pkg)?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(n.title.ifEmpty { rememberPackageLabel(n.pkg) }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = Dex.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold))
                        if (n.text.isNotEmpty()) {
                            BasicText(n.text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Dex.subtle, fontSize = 13.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageWidget(shell: ShellController, actions: WidgetActions?, id: Int) {
    val info = remember(id) { Widgets.info(id) }
    val density = LocalDensity.current
    val latestShell by rememberUpdatedState(shell)
    // Its own preferred height, within reason; the width is the column's.
    val height = with(density) { (info?.minHeight ?: 0).toDp() }.coerceIn(120.dp, 420.dp)
    Card {
        if (info == null) {
            BasicText("Widget unavailable", style = TextStyle(color = Dex.subtle, fontSize = 13.sp), modifier = Modifier.padding(16.dp))
            return@Card
        }
        var origin by remember { mutableStateOf(Offset.Zero) }
        AndroidView(
            factory = { ctx -> Widgets.host.createView(ctx, id, info) },
            update = { view ->
                (view as? DeskWidgetView)?.onLongPress = { x, y ->
                    latestShell.showMenu(
                        MenuState(
                            at = origin + Offset(x, y),
                            groups = listOf(listOf(MenuItem("Remove widget") { actions?.removePageWidget(id) })),
                        ),
                    )
                }
                Widgets.reportSize(view as AppWidgetHostView, view.width / density.density, height.value)
            },
            modifier = Modifier.fillMaxWidth().height(height).onGloballyPositioned { origin = it.boundsInRoot().topLeft },
        )
    }
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = TextStyle(color = Dex.subtle, fontSize = 13.sp), modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
private fun SuggestCard(text: String, button: String, onTap: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(cardShape).border(1.5.dp, Dex.separator, cardShape).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(text, style = TextStyle(color = Dex.text, fontSize = 14.sp))
        BasicText(
            button,
            style = TextStyle(color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(Dex.accent)
                .deskGestures(onTap = onTap).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A sideways drag in [direction] (1 to the right, -1 to the left), claimed once
 * it's clearly sideways so vertical scrolling inside keeps working. Reports the
 * movement in px, then the release velocity in px/s.
 */
internal fun Modifier.pageDrag(
    direction: Int,
    enabled: () -> Boolean,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
): Modifier = pointerInput(direction) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var claimed = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            if (claimed) {
                change.consume()
                onDrag(delta.x)
                continue
            }
            total += delta
            if (abs(total.x) > slop && abs(total.x) > 2 * abs(total.y)) {
                if (total.x * direction <= 0) return@awaitEachGesture
                claimed = true
                change.consume()
                onDrag(total.x)
            } else if (abs(total.y) > slop) {
                return@awaitEachGesture
            }
        }
        if (claimed) onRelease(tracker.calculateVelocity().x)
    }
}

private const val RECENT_CHIPS = 5
private const val SEARCH_RESULTS = 6
private const val CALENDAR_ROWS = 4
private const val NOTIFICATION_ROWS = 3
