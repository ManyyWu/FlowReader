package com.flowreader.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Solid concentric AssistiveTouch-style surface; overall transparency belongs to the view. */
internal class FloatingBallDrawable(private val tint: Int) : Drawable() {
    companion object {
        fun innerRingColor(tint: Int): Int = Color.rgb(
            (150 + Color.red(tint) - 225).coerceIn(0, 255),
            (157 + Color.green(tint) - 233).coerceIn(0, 255),
            (162 + Color.blue(tint) - 245).coerceIn(0, 255))
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = minOf(bounds.width(), bounds.height()) / 2f - 1f
        // Default colors follow the supplied reference. Color settings tint all layers together.
        fun tinted(reference: Int): Int {
            val default = Color.rgb(225, 233, 245)
            fun component(value: Int, selected: Int, baseline: Int) = (value + selected - baseline).coerceIn(0, 255)
            return Color.rgb(component(Color.red(reference), Color.red(tint), Color.red(default)),
                component(Color.green(reference), Color.green(tint), Color.green(default)),
                component(Color.blue(reference), Color.blue(tint), Color.blue(default)))
        }
        val layers = listOf(
            1f to Color.rgb(32, 41, 48),
            .73f to Color.rgb(88, 98, 102),
            .61f to Color.rgb(150, 157, 162),
            .48f to Color.WHITE
        )
        paint.alpha = opacity
        layers.forEach { (scale, color) ->
            paint.color = tinted(color)
            paint.alpha = opacity
            canvas.drawCircle(cx, cy, radius * scale, paint)
        }
    }

    override fun getOutline(outline: Outline) {
        outline.setOval(bounds)
        outline.alpha = .25f
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
