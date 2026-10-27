package dev.home.folddesk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One theme's colours. Dark matches design/mockup.html, light matches design/light.html. */
@Immutable
data class DexColors(
    val bar: Color,
    val panel: Color,
    val menu: Color,
    val hover: Color,
    val pressed: Color,
    val accent: Color,
    val text: Color,
    val subtle: Color,
    val divider: Color,
    val separator: Color,
    /** Search field and similar inset surfaces. */
    val field: Color,
    /** Hairline around floating panels. */
    val outline: Color,
    val date: Color,
    // Settings window (One UI settings look).
    val page: Color,
    val card: Color,
    val segment: Color,
    val switchOff: Color,
    /** One UI panel material: tint over blur, and the no-blur fallback. */
    val panelBlurTint: Color,
    val panelOpaque: Color,
)

val DarkDex = DexColors(
    bar = Color(0xDB0C0C0E),
    panel = Color(0xF026262A),
    menu = Color(0xFF2B2B2F),
    hover = Color(0x1FFFFFFF),
    pressed = Color(0x33FFFFFF),
    accent = Color(0xFF3E91FF),
    text = Color(0xFFFAFAFA),
    subtle = Color(0xFFA8A8AD),
    divider = Color(0x1AFFFFFF),
    separator = Color(0x2EFFFFFF),
    field = Color(0x17FFFFFF),
    outline = Color(0x1FFFFFFF),
    date = Color(0xFFD6D6DA),
    page = Color(0xFF0C0C0E),
    card = Color(0xFF1C1C1F),
    segment = Color(0xFF2A2A2E),
    switchOff = Color(0xFF4A4A4F),
    // Measured on the Fold7's quick settings: ~78% #1C1C1E over the blur.
    panelBlurTint = Color(0xC71C1C1E),
    panelOpaque = Color(0xF21C1C1E),
)

val LightDex = DexColors(
    bar = Color(0xE0F8F8FA),
    panel = Color(0xF5FAFAFC),
    menu = Color(0xFFFFFFFF),
    hover = Color(0x0F000000),
    pressed = Color(0x1A000000),
    accent = Color(0xFF0A7CFF),
    text = Color(0xFF1B1B1F),
    subtle = Color(0xFF6B6B73),
    divider = Color(0x14000000),
    separator = Color(0x26000000),
    field = Color(0x0D000000),
    outline = Color(0x0F000000),
    date = Color(0xFF6B6B73),
    page = Color(0xFFF4F4F6),
    card = Color(0xFFFFFFFF),
    segment = Color(0xFFE9E9EE),
    switchOff = Color(0xFFC7C7CC),
    panelBlurTint = Color(0xC7F4F4F6),
    panelOpaque = Color(0xF7F7F7F9),
)

val LocalDexColors = staticCompositionLocalOf { DarkDex }

/** Follows the phone's light/dark setting, including scheduled dark mode. */
@Composable
fun DexTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDexColors provides if (isSystemInDarkTheme()) DarkDex else LightDex,
        content = content,
    )
}

/** Colours (from the current theme) and sizes modelled on Samsung DeX. */
object Dex {
    val bar: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.bar
    val panel: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.panel
    val menu: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.menu
    val hover: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.hover
    val pressed: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.pressed
    val accent: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.accent
    val text: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.text
    val subtle: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.subtle
    val divider: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.divider
    val separator: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.separator
    val field: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.field
    val outline: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.outline
    val date: Color @Composable @ReadOnlyComposable get() = LocalDexColors.current.date

    /** Labels on the wallpaper stay white with a shadow in both themes, like One UI Home. */
    val onWallpaper = Color(0xFFFAFAFA)

    val taskbarHeight = 48.dp
    /** Width of the taskbar on the left or right edge. */
    val taskbarWidth = 56.dp
    val desktopCellWidth = 84.dp
    val desktopCellHeight = 98.dp
}

/** Line-drawn taskbar glyphs, matching the mockup's SVGs. */
enum class DexGlyph { Apps, Recents, Home, Back, Wifi, Cellular, Power, Settings, Sliders, Trash, Desktops }

@Composable
fun Glyph(glyph: DexGlyph, size: Dp = 22.dp, color: Color = Dex.text) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            DexGlyph.Apps -> for (x in 0..2) for (y in 0..2) {
                drawCircle(color, 2f * u, Offset((5 + 7 * x) * u, (5 + 7 * y) * u))
            }
            DexGlyph.Recents -> for (x in listOf(7f, 12f, 17f)) {
                drawLine(color, Offset(x * u, 6 * u), Offset(x * u, 18 * u), stroke.width, StrokeCap.Round)
            }
            DexGlyph.Home -> drawRoundRect(
                color, Offset(5 * u, 5 * u), Size(14 * u, 14 * u), CornerRadius(5 * u), style = stroke,
            )
            DexGlyph.Back -> drawPath(path(u, 15f to 5f, 8f to 12f, 15f to 19f), color, style = stroke)
            DexGlyph.Wifi -> {
                for ((i, r) in listOf(3.5f, 7f, 10.5f).withIndex()) {
                    drawArc(
                        color, startAngle = 225f, sweepAngle = 90f, useCenter = i == 0,
                        topLeft = Offset((12 - r) * u, (19 - r) * u), size = Size(2 * r * u, 2 * r * u),
                        style = if (i == 0) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2f * u, cap = StrokeCap.Round),
                    )
                }
            }
            DexGlyph.Cellular -> drawPath(path(u, 3f to 21f, 21f to 21f, 21f to 3f).apply { close() }, color)
            DexGlyph.Power -> {
                drawLine(color, Offset(12 * u, 3 * u), Offset(12 * u, 11 * u), 2f * u, StrokeCap.Round)
                drawArc(
                    color, startAngle = -50f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(4 * u, 5 * u), size = Size(16 * u, 16 * u), style = Stroke(2f * u, cap = StrokeCap.Round),
                )
            }
            DexGlyph.Settings -> {
                drawCircle(color, 7.5f * u, center, style = Stroke(2.2f * u))
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = center + Offset((kotlin.math.cos(a) * 9.5 * u).toFloat(), (kotlin.math.sin(a) * 9.5 * u).toFloat())
                    drawCircle(color, 1.8f * u, c)
                }
                drawCircle(color, 2.6f * u, center, style = Stroke(1.8f * u))
            }
            DexGlyph.Trash -> {
                val trash = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawLine(color, Offset(4 * u, 7 * u), Offset(20 * u, 7 * u), trash.width, StrokeCap.Round)
                drawPath(path(u, 9f to 7f, 9f to 4f, 15f to 4f, 15f to 7f), color, style = trash)
                drawPath(path(u, 6f to 7f, 7f to 20f, 17f to 20f, 18f to 7f), color, style = trash)
                drawLine(color, Offset(10 * u, 11 * u), Offset(10 * u, 17 * u), trash.width, StrokeCap.Round)
                drawLine(color, Offset(14 * u, 11 * u), Offset(14 * u, 17 * u), trash.width, StrokeCap.Round)
            }
            DexGlyph.Desktops -> {
                drawRoundRect(color, Offset(3 * u, 7 * u), Size(13 * u, 11 * u), CornerRadius(2.5f * u), style = stroke)
                drawPath(path(u, 8f to 4f, 18.5f to 4f, 21f to 6.5f, 21f to 14f), color, style = stroke)
            }
            DexGlyph.Sliders -> for ((y, w) in listOf(5f to 18f, 12f to 12f, 19f to 18f)) {
                drawLine(color, Offset(3 * u, y * u), Offset((3 + w) * u, y * u), 2f * u, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun BatteryGlyph(level: Int, charging: Boolean, color: Color = Dex.text) {
    val accent = Dex.accent
    Canvas(Modifier.size(width = 9.dp, height = 16.dp)) {
        val u = size.width / 9f
        drawRoundRect(color, Offset(3 * u, 0f), Size(3 * u, 2 * u), CornerRadius(u * 0.5f))
        drawRoundRect(
            color, Offset(0.75f * u, 2 * u), Size(7.5f * u, size.height - 2.75f * u), CornerRadius(1.5f * u),
            style = Stroke(1.3f * u),
        )
        val inner = size.height - 5.5f * u
        val fill = inner * level.coerceIn(0, 100) / 100f
        drawRect(
            if (charging) accent else color,
            Offset(2.2f * u, 2 * u + 1.4f * u + inner - fill), Size(4.6f * u, fill),
        )
    }
}

private fun DrawScope.path(u: Float, vararg points: Pair<Float, Float>) = Path().apply {
    points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
}
