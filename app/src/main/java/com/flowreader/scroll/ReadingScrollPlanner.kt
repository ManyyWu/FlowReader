package com.flowreader.scroll

import com.flowreader.diagnostics.ReadingBlock
import com.flowreader.diagnostics.ScreenSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/** Per-window calibration from uncropped real text nodes; summaries/media never train the gain. */
internal class ReadingScrollPlanner {
    data class Plan(val percent: Int, val desiredPixels: Int, val gain: Float, val reason: String)
    private val gains = linkedMapOf<String, ArrayDeque<Float>>()
    private fun viewport(snapshot: ScreenSnapshot, blocks: List<ReadingBlock>): ReadingViewportLayout.Box? {
        val target = ScrollTargets.select(snapshot, blocks) ?: return null
        return ReadingViewportLayout.box(snapshot.nodes[ScrollTargets.profileViewport(snapshot, target) ?: target].boundsInScreen)
    }
    private fun key(s: ScreenSnapshot, v: ReadingViewportLayout.Box) = "${s.packageName}:${s.windowId}:${v.left}:${v.top}:${v.width}:${v.height}"
    private fun gain(s: ScreenSnapshot, v: ReadingViewportLayout.Box): Float = gains[key(s, v)]?.sorted()?.let { it[it.size / 2] } ?: 1f
    fun plan(snapshot: ScreenSnapshot, blocks: List<ReadingBlock>, activeNode: Int?, fallback: Int = 50): Plan? {
        val view = viewport(snapshot, blocks) ?: return null
        if (!ReadingViewportLayout.settled(snapshot, blocks)) return null
        fun precise(index: Int) = snapshot.nodes.getOrNull(index)?.takeIf { it.isVisibleToUser && it.className == "android.widget.TextView" }
            ?.boundsInScreen?.let(ReadingViewportLayout::box)?.takeIf {
                it.left >= view.left && it.right <= view.right && it.top > view.top + 8 && it.bottom < view.bottom - 8
            }
        // Following needs the paragraph opening, even when its lower half is clipped.
        // Calibration below still requires complete, uncropped TextViews.
        val active = activeNode?.let { snapshot.nodes.getOrNull(it) }?.takeIf { it.isVisibleToUser }
            ?.boundsInScreen?.let(ReadingViewportLayout::box)?.takeIf {
                it.left >= view.left && it.right <= view.right && it.top >= view.top && it.top < view.bottom
            }
        val last = blocks.mapNotNull { precise(it.nodeIndex) }.maxByOrNull { it.top }
        val desired = when {
            active != null && active.top >= view.top + view.height * .3f -> active.top - view.top - view.height * .15f
            activeNode == null && last != null && last.top >= view.top + view.height * .5f -> last.top - view.top - view.height * .2f
            else -> view.height * fallback.coerceIn(10, 65) / 100f
        }.coerceIn(view.height * .2f, view.height * .65f)
        val factor = gain(snapshot, view)
        val percent = (desired / view.height * 100 / factor).roundToInt().coerceIn(10, 65)
        return Plan(percent, desired.roundToInt(), factor,
            if (active != null) "当前正文定位" else if (activeNode == null && last != null) "尾部正文定位" else "保留重叠区域")
    }
    fun observe(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot, next: List<ReadingBlock>, gesturePixels: Float): Float? {
        if (!before.isTraversalComplete || !after.isTraversalComplete || before.packageName != after.packageName ||
            before.windowId != after.windowId || gesturePixels < 80) return null
        val view = viewport(before, read) ?: return null
        if (viewport(after, next) != view || !ReadingViewportLayout.settled(before, read) || !ReadingViewportLayout.settled(after, next)) return null
        fun body(s: ScreenSnapshot, b: ReadingBlock) = s.nodes.getOrNull(b.nodeIndex)
            ?.takeIf { it.isVisibleToUser && it.className == "android.widget.TextView" }?.boundsInScreen?.let(ReadingViewportLayout::box)
            ?.takeIf { it.left >= view.left && it.right <= view.right && it.top > view.top + 8 && it.bottom < view.bottom - 8 }
        val distances = read.mapNotNull { old ->
            if (old.key == null || read.count { it.key == old.key && it.kind == old.kind } != 1) return@mapNotNull null
            val match = next.filter { it.key == old.key && it.kind == old.kind }.singleOrNull() ?: return@mapNotNull null
            if (old.text != match.text && (old.continuityText == null || old.continuityText != match.continuityText)) return@mapNotNull null
            val a = body(before, old) ?: return@mapNotNull null
            val b = body(after, match) ?: return@mapNotNull null
            if (abs(a.left - b.left) > 8 || abs(a.width - b.width) > 8 || abs(a.height - b.height) > 8) return@mapNotNull null
            (a.top - b.top).toFloat().takeIf { it > 40 && it < view.height * 1.3f }
        }.sorted()
        if (distances.size < 2) return null
        val median = distances[distances.size / 2]
        if (distances.any { abs(it - median) > maxOf(32f, median * .1f) }) return null
        val ratio = median / gesturePixels
        if (ratio !in .65f..1.8f) return null
        val history = gains.getOrPut(key(before, view)) { ArrayDeque() }
        history.addLast(ratio); while (history.size > 5) history.removeFirst()
        while (gains.size > 8) gains.remove(gains.keys.first())
        return gain(before, view)
    }
}
