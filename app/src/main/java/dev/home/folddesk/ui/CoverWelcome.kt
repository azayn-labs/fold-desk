package dev.home.folddesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * Shown when FoldDesk is opened on a Fold's cover screen while that screen belongs to
 * One UI, e.g. right after installing it. Without it, FoldDesk would hand straight
 * back to One UI Home and look broken. [onDone] gets whether to use FoldDesk here.
 */
@Composable
fun CoverWelcome(onDone: (useFoldDesk: Boolean) -> Unit) {
    val context = LocalContext.current
    val icon = remember { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }
    var useFoldDesk by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp)) {
            Image(icon, contentDescription = null, modifier = Modifier.size(84.dp))
            BasicText(
                "Welcome to FoldDesk",
                style = TextStyle(color = Dex.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 22.dp),
            )
            BasicText(
                "A desktop for your Fold's big inner screen: a taskbar, apps in windows and more.",
                style = TextStyle(color = Dex.text.copy(alpha = 0.85f), fontSize = 15.5.sp, lineHeight = 23.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
            Panel(22.dp, Modifier.padding(top = 26.dp).fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    UnfoldGlyph()
                    Spacer(Modifier.width(14.dp))
                    Column {
                        BasicText("Unfold your phone to set it up", style = TextStyle(color = Dex.text, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                        BasicText(
                            "FoldDesk walks you through it on the inner screen.",
                            style = TextStyle(color = Dex.subtle, fontSize = 13.sp),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
            BasicText(
                "And on this cover screen?",
                style = TextStyle(color = Dex.subtle, fontSize = 13.sp),
                modifier = Modifier.padding(start = 4.dp, top = 30.dp, bottom = 10.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Keep One UI here", "FoldDesk only on the inner screen", selected = !useFoldDesk) { useFoldDesk = false }
                Choice("Use FoldDesk here too", "Notifications, widgets, your dock and apps", selected = useFoldDesk) { useFoldDesk = true }
            }
            Box(
                Modifier.padding(top = 18.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(Dex.accent).deskGestures(onTap = { onDone(useFoldDesk) }),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Done", style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** The same first open on a phone without a fold, where FoldDesk is simply a home screen. */
@Composable
fun PhoneWelcome(onSetHome: () -> Unit, onNotNow: () -> Unit) {
    val context = LocalContext.current
    val icon = remember { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp)) {
            Image(icon, contentDescription = null, modifier = Modifier.size(84.dp))
            BasicText(
                "Welcome to FoldDesk",
                style = TextStyle(color = Dex.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 22.dp),
            )
            BasicText(
                "A clean home screen that puts what matters first.",
                style = TextStyle(color = Dex.text.copy(alpha = 0.85f), fontSize = 15.5.sp, lineHeight = 23.sp),
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Feature("Notifications at a glance", "Grouped by app, with the time and your next events")
                Feature("A page of widgets", "Swipe right, place them anywhere on the grid")
                Feature("Your apps, your dock", "Recent apps, favourites, search and your own dock")
            }
            Box(
                Modifier.padding(top = 22.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(Dex.accent).deskGestures(onTap = onSetHome),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Set as home screen", style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            }
            Box(
                Modifier.padding(top = 6.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(21.dp))
                    .deskGestures(onTap = onNotNow),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("Not now", style = TextStyle(color = Dex.accent, fontSize = 14.5.sp))
            }
        }
    }
}

@Composable
private fun Feature(title: String, hint: String) {
    Panel(18.dp, Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Dex.accent.copy(alpha = 0.25f)))
            Spacer(Modifier.width(12.dp))
            Column {
                BasicText(title, style = TextStyle(color = Dex.text, fontSize = 14.5.sp))
                BasicText(hint, style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun Choice(title: String, hint: String, selected: Boolean, onTap: () -> Unit) {
    Panel(20.dp, Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().deskGestures(onTap = onTap).padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .border(2.dp, if (selected) Dex.accent else Dex.subtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Dex.accent))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                BasicText(title, style = TextStyle(color = Dex.text, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                BasicText(hint, style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** Two panels of a phone opening up. */
@Composable
private fun UnfoldGlyph() {
    val ink = Dex.text.copy(alpha = 0.8f)
    Canvas(Modifier.size(width = 54.dp, height = 54.dp)) {
        val w = size.width * 0.4f
        val h = size.height * 0.78f
        val top = (size.height - h) / 2
        val stroke = Stroke(width = 2.dp.toPx())
        val corner = CornerRadius(5.dp.toPx())
        drawRoundRect(ink, Offset(size.width * 0.08f, top), Size(w, h), corner, style = stroke)
        rotate(-8f, pivot = Offset(size.width * 0.72f, size.height / 2)) {
            drawRoundRect(ink.copy(alpha = 0.6f), Offset(size.width * 0.52f, top), Size(w, h), corner, style = stroke)
        }
    }
}
