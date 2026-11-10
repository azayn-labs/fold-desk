package dev.home.folddesk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.home.folddesk.AppEntry
import dev.home.folddesk.OpenMode
import dev.home.folddesk.ShellController

/** Width of the stage strip's window; the stage window keeps clear of it. */
const val STAGE_STRIP_DP = 112

/**
 * Stage mode's strip, at the left edge: the other open apps as cards, the most
 * recently used on top. Tapping one brings it to the stage; the app that was
 * there steps aside into the strip.
 */
@Composable
fun StageStrip(shell: ShellController) {
    val desk = shell.desk
    val apps = desk.stageStrip(desk.focusedPackage)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(160)) + slideInHorizontally(tween(200)) { -it / 2 },
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (app in apps) key(app.key) { StageCard(shell, app) }
        }
    }
}

@Composable
private fun StageCard(shell: ShellController, app: AppEntry) {
    val feedback = remember { PressFeedback() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(width = 96.dp, height = 84.dp)
            .clip(shape)
            .background(Dex.menu)
            .border(1.dp, Dex.outline, shape)
            .deskGestures(onTap = { shell.open(app, OpenMode.Front) }, feedback = feedback),
    ) {
        HoverBox(
            modifier = Modifier.fillMaxSize(),
            selected = feedback.pressed,
            selectedColor = Dex.pressed,
            corner = 16,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(40.dp))
                BasicText(
                    app.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Dex.text, fontSize = 11.sp),
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp),
                )
            }
        }
    }
}
