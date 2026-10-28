package dev.home.folddesk

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil

/** FoldDesk's widget host: one per process, listening while the home screen is visible. */
object Widgets {
    private const val HOST_ID = 0xF01D

    lateinit var host: AppWidgetHost
        private set
    lateinit var manager: AppWidgetManager
        private set

    fun init(context: Context) {
        manager = AppWidgetManager.getInstance(context)
        host = object : AppWidgetHost(context, HOST_ID) {
            override fun onCreateView(c: Context, id: Int, info: AppWidgetProviderInfo?) = DeskWidgetView(c)
        }
    }

    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    /** Default span on FoldDesk's grid: the provider's target cells, else from its minimum size. */
    fun defaultSpan(info: AppWidgetProviderInfo, context: Context, cellWidthDp: Float, cellHeightDp: Float): Pair<Int, Int> {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) return info.targetCellWidth to info.targetCellHeight
        val density = context.resources.displayMetrics.density
        val w = ceil(info.minWidth / density / cellWidthDp).toInt().coerceAtLeast(1)
        val h = ceil(info.minHeight / density / cellHeightDp).toInt().coerceAtLeast(1)
        return w to h
    }

    /** Smallest span the provider allows when resizing. */
    fun minSpan(info: AppWidgetProviderInfo, context: Context, cellWidthDp: Float, cellHeightDp: Float): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val mw = (info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth) / density
        val mh = (info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight) / density
        // Never larger than its default size: some providers report a min resize
        // size bigger than the cells they ask for.
        val (dw, dh) = defaultSpan(info, context, cellWidthDp, cellHeightDp)
        return ceil(mw / cellWidthDp).toInt().coerceIn(1, dw) to ceil(mh / cellHeightDp).toInt().coerceIn(1, dh)
    }

    // --- Cover screen widget grid: COVER_COLUMNS square cells across ---

    const val COVER_COLUMNS = 3
    /** Roughly a cell on the Fold7's cover screen; only used to pick starting sizes. */
    private const val COVER_CELL_DP = 150f

    fun coverRows(heightDp: Int) = Math.round(heightDp / COVER_CELL_DP).coerceAtLeast(1)

    /** Starting span on the cover screen grid, from what the widget asks for. */
    fun coverSpan(info: AppWidgetProviderInfo, context: Context): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val w = ceil(info.minWidth / density / COVER_CELL_DP).toInt()
        val h = ceil(info.minHeight / density / COVER_CELL_DP).toInt()
        return w.coerceIn(1, COVER_COLUMNS) to h.coerceIn(1, COVER_MAX_ROWS)
    }

    /** Smallest span it can be resized to there. */
    fun coverMinSpan(info: AppWidgetProviderInfo?, context: Context): Pair<Int, Int> {
        info ?: return 1 to 1
        val density = context.resources.displayMetrics.density
        val mw = (info.minResizeWidth.takeIf { it > 0 } ?: info.minWidth) / density
        val mh = (info.minResizeHeight.takeIf { it > 0 } ?: info.minHeight) / density
        val (dw, dh) = coverSpan(info, context)
        return ceil(mw / COVER_CELL_DP).toInt().coerceIn(1, dw) to ceil(mh / COVER_CELL_DP).toInt().coerceIn(1, dh)
    }

    const val COVER_MAX_ROWS = 4

    fun canResize(info: AppWidgetProviderInfo) = info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE

    fun isReconfigurable(info: AppWidgetProviderInfo) = info.configure != null &&
        (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0

    /** Tells the widget how big it is now, so it can pick a layout. */
    fun reportSize(view: AppWidgetHostView, widthDp: Float, heightDp: Float) {
        view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
    }
}

/**
 * A widget on the desktop. Taps and scrolls go to the widget as usual; a long
 * press is FoldDesk's (menu, then drag to move), like One UI Home.
 */
class DeskWidgetView(context: Context) : AppWidgetHostView(context) {
    /** Long press at a point in this window's coordinates. */
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var longPressed = false
    private var dragging = false

    private val longPress = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        // The widget's own press (a button held down) ends here.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0).let {
            super.dispatchTouchEvent(it)
            it.recycle()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val loc = IntArray(2).also { getLocationInWindow(it) }
        onLongPress?.invoke(loc[0] + downX, loc[1] + downY)
    }

    /**
     * Watches every touch before the widget gets it: some widgets' buttons stop
     * their parent from intercepting, which would hide the drag after a long press.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.rawX; lastY = ev.rawY
                longPressed = false
                dragging = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longPressed && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (!longPressed) return super.dispatchTouchEvent(ev)
        // Long pressed: the gesture is FoldDesk's now (menu, then drag to move).
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - lastX
                val dy = ev.rawY - lastY
                if (!dragging && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) onDrag?.invoke(dx, dy)
                lastX = ev.rawX
                lastY = ev.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd?.invoke()
                longPressed = false
                dragging = false
            }
        }
        return true
    }
}
