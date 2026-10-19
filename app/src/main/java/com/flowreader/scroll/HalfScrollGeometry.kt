package com.flowreader.scroll

internal object HalfScrollGeometry {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Swipe(val x: Float, val fromY: Float, val toY: Float)
    fun plan(view: Box, overlay: Box?, scrollPercent: Float = 50f, avoid: List<Box> = emptyList()): Swipe? {
        val width = view.right - view.left; val height = view.bottom - view.top
        if (width < 32 || height < 80) return null
        val fraction = scrollPercent.coerceIn(.1f, 100f) / 100f
        val from = view.top + height * (.5f + fraction / 2f); val to = view.top + height * (.5f - fraction / 2f)
        val candidates = listOf(.9f, .1f, .5f, .75f, .25f).map { view.left + width * it }.filter { x ->
            overlay == null || x < overlay.left - 8 || x > overlay.right + 8 ||
                from < overlay.top - 8 || to > overlay.bottom + 8
        }
        // Prefer a media-free lane; full-width media must not make the page unscrollable.
        val x = candidates.firstOrNull { x -> avoid.none { media ->
            x >= media.left - 8 && x <= media.right + 8 && from >= media.top - 8 && to <= media.bottom + 8
        } } ?: candidates.firstOrNull() ?: return null
        return Swipe(x, from, to)
    }
}
