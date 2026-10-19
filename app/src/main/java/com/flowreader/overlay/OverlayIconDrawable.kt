package com.flowreader.overlay

import android.graphics.*
import android.graphics.drawable.Drawable

internal class OverlayIconDrawable(private val kind: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
    }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        when (kind) {
            "play" -> { val p = Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }; canvas.drawPath(p, paint) }
            "pause" -> { canvas.drawLine(8f, 5f, 8f, 19f, paint); canvas.drawLine(16f, 5f, 16f, 19f, paint) }
            "stop" -> canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint)
            "scroll" -> {
                canvas.drawLine(12f, 3f, 12f, 21f, paint)
                canvas.drawLine(7f, 8f, 12f, 3f, paint); canvas.drawLine(17f, 8f, 12f, 3f, paint)
                canvas.drawLine(7f, 16f, 12f, 21f, paint); canvas.drawLine(17f, 16f, 12f, 21f, paint)
            }
            "read" -> {
                canvas.drawRoundRect(4f, 4f, 20f, 20f, 2f, 2f, paint)
                canvas.drawLine(8f, 8f, 16f, 8f, paint); canvas.drawLine(8f, 12f, 16f, 12f, paint); canvas.drawLine(8f, 16f, 13f, 16f, paint)
            }
            "text" -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 2f, 2f, paint)
                canvas.drawLine(8f, 8f, 16f, 8f, paint); canvas.drawLine(12f, 8f, 12f, 16f, paint)
            }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
