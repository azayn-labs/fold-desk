package dev.home.folddesk.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First letter for the A–Z index; digits and symbols go under "#". */
internal fun indexLetter(label: String): Char = label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'

/** The letter under the finger while sliding along the index, and where the finger is. */
internal class Scrub(val letter: Char, val y: Float)

/**
 * The A–Z column beside an app list (cover screen and app drawer). Tap a letter,
 * or slide along it to run through them like the scroller in Contacts, with a
 * tick on each new letter.
 */
@Composable
internal fun LetterIndex(letters: List<Char>, current: Char?, onMove: (Char, Float) -> Unit, onRelease: () -> Unit) {
    val view = LocalView.current
    Column(
        Modifier
            .fillMaxHeight()
            .width(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (current != null) Dex.text.copy(alpha = 0.08f) else Color.Transparent)
            .pointerInput(letters) {
                val pad = 8.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var last: Char? = null
                    fun pick(y: Float) {
                        // The letters share the column evenly, so the finger's height picks one.
                        val share = (y - pad) / (size.height - 2 * pad)
                        val letter = letters[(share * letters.size).toInt().coerceIn(0, letters.lastIndex)]
                        if (letter != last) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        last = letter
                        onMove(letter, y.coerceIn(0f, size.height.toFloat()))
                    }
                    down.consume()
                    pick(down.position.y)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        // Keep the pager from taking a slightly sideways slide.
                        change.consume()
                        if (!change.pressed) break
                        pick(change.position.y)
                    }
                    onRelease()
                }
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (letter in letters) {
            val on = letter == current
            BasicText(
                letter.toString(),
                style = TextStyle(
                    color = if (on) Dex.text else Dex.subtle,
                    fontSize = if (on) 12.sp else 10.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                ),
            )
        }
    }
}

/** The big letter beside the finger, which covers the column while sliding. */
@Composable
internal fun LetterBubble(scrub: Scrub, modifier: Modifier) {
    val size = 56.dp
    Box(
        modifier
            .offset { IntOffset(-(22.dp + 14.dp).roundToPx(), (scrub.y - size.toPx()).toInt().coerceAtLeast(0)) }
            .size(size)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 28.dp, bottomEnd = 4.dp))
            .background(Dex.accent),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(scrub.letter.toString(), style = TextStyle(color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold))
    }
}
