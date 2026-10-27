package dev.home.folddesk.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView

private class Origin(var value: Offset = Offset.Zero)

/** Touch feedback for an icon: highlighted while held, lifted once the long press lands. */
class PressFeedback {
    var pressed by mutableStateOf(false)
        internal set
    var lifted by mutableStateOf(false)
        internal set
}

/**
 * Tap, long-press / right-click for a context menu, and optional drag after a
 * long press. Menu positions are reported in root coordinates.
 *
 * A long press vibrates and opens the menu at once, while the finger is still
 * down; moving on from there (when draggable) closes it and starts a drag.
 */
@Composable
fun Modifier.deskGestures(
    onTap: () -> Unit = {},
    onMenu: ((Offset) -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    feedback: PressFeedback? = null,
    /** A quick straight swipe; called with the total movement. */
    onSwipe: ((Offset) -> Unit)? = null,
    /** Released after a long press without dragging. */
    onLongPressUp: (() -> Unit)? = null,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val menu by rememberUpdatedState(onMenu)
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val swipe by rememberUpdatedState(onSwipe)
    val longPressUp by rememberUpdatedState(onLongPressUp)
    val origin = remember { Origin() }
    val view = LocalView.current
    val draggable = onDragStart != null
    val longPressable = onMenu != null || draggable

    return this
        .onGloballyPositioned { origin.value = it.positionInRoot() }
        .pointerInput(draggable, longPressable, onSwipe != null) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // An icon inside this element already owns the gesture.
                if (down.isConsumed) return@awaitEachGesture
                // Claim it so containers (the desktop, the drawer) stay out of the way.
                if (longPressable) down.consume()
                feedback?.pressed = true
                try {
                    if (currentEvent.buttons.isSecondaryPressed) {
                        menu?.invoke(origin.value + down.position)
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }

                    var press = Press.Cancelled
                    var travel = Offset.Zero
                    val track: suspend AwaitPointerEventScope.() -> Unit = {
                        val (result, moved) = trackPress(down, viewConfiguration.touchSlop)
                        press = result
                        travel = moved
                    }
                    val longPressed = longPressable && try {
                        withTimeout(viewConfiguration.longPressTimeoutMillis) { track() }
                        false
                    } catch (_: PointerEventTimeoutCancellationException) {
                        true
                    }
                    if (!longPressed) {
                        if (!longPressable) track()
                        when (press) {
                            Press.Up -> tap()
                            // Moving cancels tap and long press; it may be a swipe.
                            Press.Moved -> {
                                travel += trackToUp(down)
                                val s = swipe
                                val ax = kotlin.math.abs(travel.x)
                                val ay = kotlin.math.abs(travel.y)
                                val min = SWIPE_MIN_DP * density
                                // Mostly horizontal or mostly vertical; diagonals don't count.
                                if (s != null && ((ax > min && ax > 2 * ay) || (ay > min && ay > 2 * ax))) s(travel)
                            }
                            Press.Cancelled -> Unit
                        }
                        return@awaitEachGesture
                    }

                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    feedback?.lifted = true
                    menu?.invoke(origin.value + down.position)

                    var dragging = false
                    var total = Offset.Zero
                    while (true) {
                        val change = currentEventChange(down) ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        total += delta
                        change.consume()
                        if (dragging) {
                            drag(delta)
                        } else if (draggable && total.getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            dragStart?.invoke()
                            drag(total)
                        }
                    }
                    if (dragging) dragEnd() else longPressUp?.invoke()
                } finally {
                    feedback?.pressed = false
                    feedback?.lifted = false
                }
            }
        }
}

private enum class Press { Up, Moved, Cancelled }

private const val SWIPE_MIN_DP = 80f

/** Follows [down] until it lifts, moves past [slop], or is taken (e.g. by a scroll). */
private suspend fun AwaitPointerEventScope.trackPress(down: PointerInputChange, slop: Float): Pair<Press, Offset> {
    var total = Offset.Zero
    while (true) {
        val change = currentEventChange(down) ?: return Press.Cancelled to total
        if (change.isConsumed) return Press.Cancelled to total
        if (!change.pressed) {
            change.consume()
            return Press.Up to total
        }
        total += change.positionChange()
        if (total.getDistance() > slop) return Press.Moved to total
    }
}

/** Movement of [down] until it lifts. */
private suspend fun AwaitPointerEventScope.trackToUp(down: PointerInputChange): Offset {
    var total = Offset.Zero
    while (true) {
        val change = currentEventChange(down) ?: return total
        total += change.positionChange()
        if (!change.pressed) return total
    }
}

private suspend fun AwaitPointerEventScope.currentEventChange(
    down: PointerInputChange,
): PointerInputChange? = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
