package com.flowreader.scroll

/** Keep the current paragraph in the upper reading band, including bottom-clipped paragraphs. */
internal object PlaybackViewportFollow {
    private val bounds = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
    private fun vertical(value: String?): Pair<Int, Int>? {
        val match = value?.let(bounds::matchEntire) ?: return null
        val top = match.groupValues[2].toIntOrNull() ?: return null
        val bottom = match.groupValues[4].toIntOrNull() ?: return null
        return (top to bottom).takeIf { bottom > top }
    }
    fun shouldAdvance(body: String?, viewport: String?, visible: Boolean): Boolean {
        if (!visible) return false
        val (top, bottom) = vertical(body) ?: return false
        val (viewTop, viewBottom) = vertical(viewport) ?: return false
        val threshold = viewTop.toLong() + (viewBottom.toLong() - viewTop) * 30 / 100
        return top.toLong() >= threshold && top < viewBottom && bottom > viewTop
    }
}
