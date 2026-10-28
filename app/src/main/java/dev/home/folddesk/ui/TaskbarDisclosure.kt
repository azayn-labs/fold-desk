package dev.home.folddesk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.home.folddesk.BuildConfig
import dev.home.folddesk.Launcher

/**
 * "Turn on" for the taskbar service. The Play version first explains what the
 * accessibility service is used for and asks for consent, as Play's policy requires.
 */
@Composable
fun rememberTurnOnTaskbar(): () -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        TaskbarDisclosure(
            onAgree = {
                asking = false
                Launcher.openAccessibilitySettings(context)
            },
            onDismiss = { asking = false },
        )
    }
    return { if (BuildConfig.PLAY_STORE) asking = true else Launcher.openAccessibilitySettings(context) }
}

@Composable
private fun TaskbarDisclosure(onAgree: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(LocalDexColors.current.panelOpaque)
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 20.dp),
        ) {
            BasicText(
                "Turn on the FoldDesk taskbar?",
                style = TextStyle(color = Dex.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
            )
            Paragraph(
                "FoldDesk uses Android's accessibility service to run its taskbar. It is not an accessibility tool. " +
                    "With it, FoldDesk can:",
            )
            Bullet("show the taskbar and app drawer above other apps' windows", Dex.accent)
            Bullet(
                "see which apps and windows are open, to show them on the taskbar and bring you back to the desktop when you unfold",
                Dex.accent,
            )
            Bullet(
                "press Back, Home and Recents for you, and the window buttons (maximise, close) when you use the taskbar's window controls",
                Dex.accent,
            )
            BasicText(
                "What it doesn't do",
                style = TextStyle(color = Dex.subtle, fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(top = 16.dp),
            )
            Bullet("It doesn't read what you type or what's on screen beyond window names and buttons", Dex.subtle)
            Bullet("Nothing leaves your phone: FoldDesk has no internet permission and collects no data", Dex.subtle)
            BasicText(
                buildAnnotatedString {
                    append("On the next screen, tap ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Installed apps → FoldDesk taskbar") }
                    append(" and switch it on. You can turn it off there any time.")
                },
                style = TextStyle(color = Dex.text.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                DialogButton("Not now", filled = false, onTap = onDismiss)
                DialogButton("Agree and continue", filled = true, onTap = onAgree)
            }
        }
    }
}

@Composable
private fun Paragraph(text: String) = BasicText(
    text,
    style = TextStyle(color = Dex.text.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 20.sp),
    modifier = Modifier.padding(top = 12.dp),
)

@Composable
private fun Bullet(text: String, dot: Color) {
    Row(Modifier.padding(top = 8.dp)) {
        Box(Modifier.padding(start = 6.dp, top = 7.dp).size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(13.dp))
        BasicText(text, style = TextStyle(color = Dex.text.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 19.sp))
    }
}

@Composable
private fun DialogButton(label: String, filled: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .then(if (filled) Modifier.background(Dex.accent) else Modifier)
            .deskGestures(onTap = onTap)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (filled) Color.White else Dex.accent,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
