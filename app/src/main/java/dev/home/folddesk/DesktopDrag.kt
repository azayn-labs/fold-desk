package dev.home.folddesk

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * An app being dragged onto a desktop card (see design/desktops.html, "drag").
 * Shared by the taskbar overlay and the home screen; everything is in screen
 * coordinates because the finger, the cards and the icon live in different windows.
 */
object DesktopDrag {
    /** Card id for "+ New desktop". */
    const val NEW_DESKTOP = -1
    private const val HOLD_MS = 900L

    var app by mutableStateOf<AppEntry?>(null)
        private set
    /** Finger position on screen. */
    var position by mutableStateOf(Offset.Zero)
        private set
    /** Desktop card under the finger, or [NEW_DESKTOP]. */
    var hovered by mutableStateOf<Int?>(null)
        private set
    /** Whether the overlay should draw the dragged icon (the home screen draws its own). */
    var showIcon = false
        private set
    /** Card bounds on screen, reported by the cards as they lay out. */
    val targets = mutableStateMapOf<Int, Rect>()

    val active get() = app != null

    private val handler = Handler(Looper.getMainLooper())
    private var onDrop: (Int) -> Unit = {}
    private var onHold: (Int) -> Unit = {}
    private val listeners = mutableSetOf<() -> Unit>()
    private val hold = Runnable { hovered?.let { target -> finish()?.let { onHold(target) } } }

    /** Called when a drag starts or ends, so the overlay can open its window. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /**
     * [onDrop]: released over a card. [onHold]: kept over another desktop's
     * card long enough to go there.
     */
    fun start(app: AppEntry, at: Offset, showIcon: Boolean, onDrop: (Int) -> Unit, onHold: (Int) -> Unit) {
        this.app = app
        this.showIcon = showIcon
        this.onDrop = onDrop
        this.onHold = onHold
        position = at
        hovered = null
        listeners.toList().forEach { it() }
    }

    fun moveTo(at: Offset) {
        if (!active) return
        position = at
        val over = targets.entries.firstOrNull { it.value.contains(at) }?.key
        if (over != hovered) {
            hovered = over
            handler.removeCallbacks(hold)
            if (over != null && over != NEW_DESKTOP) handler.postDelayed(hold, HOLD_MS)
        }
    }

    /** Finger lifted: drop on the card under it, if any. */
    fun release() {
        val target = hovered
        val drop = onDrop
        if (finish() != null && target != null) drop(target)
    }

    /** Ends the drag; returns the app if one was being dragged. */
    fun finish(): AppEntry? {
        val dragged = app ?: return null
        handler.removeCallbacks(hold)
        app = null
        hovered = null
        targets.clear()
        listeners.toList().forEach { it() }
        return dragged
    }
}
