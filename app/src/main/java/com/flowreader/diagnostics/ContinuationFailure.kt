package com.flowreader.diagnostics

/** One immutable in-memory failure, independent of the rolling capture history. */
data class ContinuationFailure(
    val before: ScreenSnapshot?, val read: List<ReadingBlock>,
    val after: ScreenSnapshot?, val next: List<ReadingBlock>, val reason: String
) {
    fun summary(): String = "最近续读失败：$reason\n" +
        "前帧=${before?.capturedAtMillis}，已读块=${read.size}；后帧=${after?.capturedAtMillis}，当前块=${next.size}"
    fun format(): String = buildString {
        appendLine(summary())
        appendLine("播放队列独立于视口；跨帧缓存块的node为最近采集索引，不保证属于同一张前帧。")
        fun blocks(label: String, values: List<ReadingBlock>) {
            appendLine(label)
            values.forEachIndexed { index, block ->
                appendLine("[$index] node=${block.nodeIndex} key=${block.key} section=${block.continuitySection}\n${block.text}")
            }
        }
        blocks("已读队列", read); blocks("后屏正文", next)
        appendLine("滚动前节点\n${before?.formatForDisplay() ?: "无"}")
        appendLine("滚动后节点\n${after?.formatForDisplay() ?: "无"}")
    }
}
