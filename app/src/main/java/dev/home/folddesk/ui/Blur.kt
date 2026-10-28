package dev.home.folddesk.ui

import dev.home.folddesk.HiddenApi
import android.util.Log
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "FoldDesk"
private const val BLUR_RADIUS_DP = 30f

/**
 * A FoldDesk panel in One UI's panel material (notification panel, quick
 * settings): a tint over a blurred copy of what's behind it. Android's own
 * cross-window blur is off for apps on Samsung phones, so this uses Samsung's
 * SemBlurInfo, which is what One UI's panels use. Without it, the panel gets
 * a plain, slightly more opaque tint.
 */
@Composable
fun Panel(
    corner: Dp,
    modifier: Modifier = Modifier,
    /** 0..1; the system draws the blur, so it has to be faded by hand during animations. */
    blurAmount: Float = 1f,
    /** Tint over the blur; defaults to One UI's panel tint. */
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalDexColors.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(corner)
    if (!SemBlur.available) {
        val fallback = tint?.copy(alpha = maxOf(tint.alpha, 0.92f)) ?: colors.panelOpaque
        Box(modifier.clip(shape).background(fallback), content = content)
        return
    }
    val amount = blurAmount.coerceIn(0f, 1f)
    val radius = (BLUR_RADIUS_DP * density.density * amount).toInt()
    val cornerPx = with(density) { corner.toPx() }
    val tintArgb = (tint ?: colors.panelBlurTint).let { it.copy(alpha = it.alpha * amount) }.toArgb()
    Box(modifier.clip(shape)) {
        AndroidView(
            factory = { View(it) },
            update = { SemBlur.apply(it, radius, tintArgb, cornerPx) },
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}

/** Reflection wrapper around android.view.SemBlurInfo (One UI only). */
private object SemBlur {
    private val infoClass = runCatching { Class.forName("android.view.SemBlurInfo") }.getOrNull()
    private val builderClass = runCatching { Class.forName("android.view.SemBlurInfo\$Builder") }.getOrNull()

    val available: Boolean = infoClass != null && builderClass != null

    fun apply(view: View, radius: Int, color: Int, corner: Float) {
        try {
            val mode = infoClass!!.getField("BLUR_MODE_WINDOW").getInt(null)
            var builder: Any = builderClass!!.getConstructor(Int::class.javaPrimitiveType).newInstance(mode)
            builder = call(builder, "setRadius", radius) ?: builder
            builder = call(builder, "setBackgroundColor", color) ?: builder
            builder = call(builder, "setBackgroundCornerRadius", corner) ?: builder
            val info = HiddenApi.invoke(builderClass, builder, "build")
            HiddenApi.invoke(View::class.java, view, "semSetBlurInfo", info)
        } catch (e: Throwable) {
            Log.w(TAG, "SemBlurInfo failed", e)
        }
    }

    private fun call(target: Any, name: String, arg: Any): Any? = try {
        HiddenApi.invoke(builderClass!!, target, name, arg)
    } catch (e: Throwable) {
        Log.w(TAG, "SemBlurInfo.Builder.$name unavailable: $e")
        null
    }
}
