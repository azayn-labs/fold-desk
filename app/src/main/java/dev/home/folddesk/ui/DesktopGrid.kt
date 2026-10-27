package dev.home.folddesk.ui

import androidx.compose.ui.geometry.Offset
import dev.home.folddesk.Cell

/**
 * The desktop grid as last laid out on the home screen, for code outside the
 * composition (placing a new widget, dropping one from the picker).
 */
object DesktopGrid {
    var cols = 1
    var rows = 1
    /** Grid's top-left in the home window, and cell size, in px. */
    var origin = Offset.Zero
    var cellW = 1f
    var cellH = 1f
    var layout: Map<String, Cell> = emptyMap()

    /** Cell for a [w]×[h] widget centred on window point [at]. */
    fun cellAt(at: Offset, w: Int, h: Int): Cell {
        val col = ((at.x - origin.x) / cellW - w / 2f).let { kotlin.math.round(it).toInt() }
        val row = ((at.y - origin.y) / cellH - h / 2f).let { kotlin.math.round(it).toInt() }
        return Cell(col.coerceIn(0, (cols - w).coerceAtLeast(0)), row.coerceIn(0, (rows - h).coerceAtLeast(0)))
    }
}
