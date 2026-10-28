package dev.home.folddesk.ui

import android.appwidget.AppWidgetProviderInfo
import android.os.UserManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.home.folddesk.ShellController
import dev.home.folddesk.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One app's widgets in the picker. */
private class WidgetApp(val pkg: String, val label: String, val icon: ImageBitmap?, val widgets: List<AppWidgetProviderInfo>)

/** A widget being dragged out of the picker onto the desktop. */
class WidgetPlacement(val info: AppWidgetProviderInfo, val preview: ImageBitmap?, val w: Int, val h: Int) {
    var at by mutableStateOf(Offset.Zero)
}

/**
 * Lists apps with widgets (see design/widgets.html). Tap a widget to add it;
 * long-press and drag to drop it in a spot.
 */
@Composable
fun WidgetPicker(
    shell: ShellController,
    modifier: Modifier,
    onAdd: (AppWidgetProviderInfo) -> Unit,
    onDragOut: (WidgetPlacement) -> Unit,
    onDrop: (WidgetPlacement) -> Unit,
    placing: Boolean,
    /** Take the whole width, for the narrow cover screen. */
    fullWidth: Boolean = false,
) {
    val context = LocalContext.current
    val desk = shell.desk
    val apps by produceState<List<WidgetApp>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val users = context.getSystemService(UserManager::class.java).userProfiles
            users.flatMap { Widgets.manager.getInstalledProvidersForProfile(it) }
                .groupBy { it.provider.packageName }
                .map { (pkg, list) ->
                    val label = runCatching {
                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    WidgetApp(pkg, label, desk.appForPackage(pkg)?.icon, list.sortedBy { it.loadLabel(context.packageManager) })
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val shown = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps else apps.filter { q in it.label.lowercase() }
    }

    BoxWithConstraints(modifier) {
        val width = if (fullWidth) maxWidth else minOf(600.dp, maxWidth * 0.75f)
        val height = minOf(if (fullWidth) 640.dp else 760.dp, maxHeight)
        // Kept composed (but invisible) while a widget is dragged out, so the drag isn't cut off.
        Panel(26.dp, Modifier.size(width, height).alpha(if (placing) 0f else 1f)) {
            Column(Modifier.fillMaxSize().deskGestures(onTap = shell::closeMenu).padding(18.dp)) {
                BasicText(
                    "Widgets",
                    style = TextStyle(color = Dex.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
                )
                Row(
                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp)).background(Dex.field).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) BasicText("Search widgets", style = TextStyle(color = Dex.subtle, fontSize = 14.sp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Dex.text, fontSize = 14.sp), cursorBrush = SolidColor(Dex.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.pkg }) { app ->
                        AppWidgets(app, open = expanded == app.pkg || query.isNotBlank(),
                            onToggle = { expanded = if (expanded == app.pkg) null else app.pkg },
                            onAdd = onAdd, onDragOut = onDragOut, onDrop = onDrop)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppWidgets(
    app: WidgetApp,
    open: Boolean,
    onToggle: () -> Unit,
    onAdd: (AppWidgetProviderInfo) -> Unit,
    onDragOut: (WidgetPlacement) -> Unit,
    onDrop: (WidgetPlacement) -> Unit,
) {
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Dex.field)) {
        HoverBox(corner = 0, modifier = Modifier.fillMaxWidth().deskGestures(onTap = onToggle)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.width(12.dp))
                BasicText(app.label, style = TextStyle(color = Dex.text, fontSize = 15.sp), modifier = Modifier.weight(1f))
                BasicText(
                    if (app.widgets.size == 1) "1 widget" else "${app.widgets.size} widgets",
                    style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp),
                )
            }
        }
        if (open) {
            LazyRow(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(app.widgets, key = { it.provider.flattenToShortString() }) { info ->
                    WidgetPreview(info, onAdd, onDragOut, onDrop)
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(
    info: AppWidgetProviderInfo,
    onAdd: (AppWidgetProviderInfo) -> Unit,
    onDragOut: (WidgetPlacement) -> Unit,
    onDrop: (WidgetPlacement) -> Unit,
) {
    val context = LocalContext.current
    val (w, h) = remember(info) { Widgets.defaultSpan(info, context, Dex.desktopCellWidth.value, Dex.desktopCellHeight.value) }
    val preview by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                (info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0))?.let { d ->
                    val maxW = 480
                    val scale = minOf(1f, maxW.toFloat() / d.intrinsicWidth.coerceAtLeast(1))
                    d.toBitmap((d.intrinsicWidth * scale).toInt().coerceAtLeast(1), (d.intrinsicHeight * scale).toInt().coerceAtLeast(1))
                        .asImageBitmap()
                }
            }.getOrNull()
        }
    }
    var placement by remember { mutableStateOf<WidgetPlacement?>(null) }
    Column(
        Modifier
            .width(150.dp)
            .deskGestures(
                onTap = { onAdd(info) },
                // Long-press: pick it up and drag it onto the desktop.
                onMenu = { at ->
                    placement = WidgetPlacement(info, preview, w, h).also {
                        it.at = at
                        onDragOut(it)
                    }
                },
                onDragStart = {},
                onDrag = { d -> placement?.let { it.at = it.at + d } },
                onDragEnd = { placement?.let(onDrop); placement = null },
                onLongPressUp = { placement?.let(onDrop); placement = null },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 110.dp).clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let { Image(it, contentDescription = info.loadLabel(context.packageManager), modifier = Modifier.fillMaxWidth()) }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(
            info.loadLabel(context.packageManager), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Dex.text, fontSize = 12.sp),
        )
        BasicText("$w × $h", style = TextStyle(color = Dex.subtle, fontSize = 11.5.sp))
    }
}
