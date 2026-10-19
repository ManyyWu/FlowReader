package com.flowreader.scroll

import com.flowreader.diagnostics.ReadingBlock
import com.flowreader.diagnostics.ScreenSnapshot

/** Reject horizontal page-entry animation; ordinary vertical clipping remains valid. */
internal object ReadingViewportLayout {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    private val rect = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
    fun box(value: String?): Box? = value?.let(rect::matchEntire)?.groupValues?.drop(1)?.map(String::toIntOrNull)
        ?.takeIf { it.all { n -> n != null } }?.let { Box(it[0]!!, it[1]!!, it[2]!!, it[3]!!) }
        ?.takeIf { it.width > 0 && it.height > 0 }
    fun settled(snapshot: ScreenSnapshot, blocks: List<ReadingBlock>): Boolean {
        val target = ScrollTargets.select(snapshot, blocks) ?: return true
        val viewport = box(snapshot.nodes[target].boundsInScreen) ?: return false
        val bodies = blocks.mapNotNull { block -> snapshot.nodes.getOrNull(block.nodeIndex)?.takeIf { it.isVisibleToUser }?.boundsInScreen?.let(::box) }
        if (bodies.isEmpty()) return true
        val margin = maxOf(16, viewport.width / 10)
        val shifted = bodies.count { it.left < viewport.left - margin || it.right > viewport.right + margin }
        return shifted * 2 < bodies.size
    }
}
