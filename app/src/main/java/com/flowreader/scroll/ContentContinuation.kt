package com.flowreader.scroll

import com.flowreader.diagnostics.ReadingBlock

/** Adjacent screens only. Uncertain ownership/truncation never causes text to be discarded. */
internal object ContentContinuation {
    data class Result(val startIndex: Int? = null, val reason: String, val allRead: Boolean = false)
    fun match(before: List<ReadingBlock>, after: List<ReadingBlock>): Result {
        if (before.isEmpty() || after.isEmpty()) return Result(reason = "正文为空，停止自动续读")
        fun same(a: ReadingBlock, b: ReadingBlock): Boolean =
            (a.text == b.text || (a.continuityText != null && a.continuityText == b.continuityText)) &&
                a.kind == b.kind && a.key != null && a.key == b.key
        fun structural(length: Int): Boolean {
            if (length < 2) return false
            val pairs = before.takeLast(length).zip(after.take(length))
            return pairs.any { (a, b) -> a.key == null || b.key == null } && pairs.all { (a, b) ->
                a.text == b.text && a.text.length >= 12 && a.kind == b.kind &&
                    a.continuitySection == "reddit:comment" && a.continuitySection == b.continuitySection &&
                    (a.key == null || b.key == null || a.key == b.key) &&
                    before.count { it.text == a.text } == 1 && after.count { it.text == b.text } == 1
            }
        }
        val overlaps = (1..minOf(before.size, after.size)).filter { length ->
            before.takeLast(length).zip(after.take(length)).all { (a, b) -> same(a, b) } || structural(length)
        }
        val length = overlaps.maxOrNull() ?: return Result(reason = "缺少可靠跨屏锚点，请手动选择正文")
        val anchors = after.take(length)
        if (!structural(length) && anchors.any { anchor -> before.count { same(it, anchor) } != 1 ||
                after.count { same(it, anchor) } != 1 }) return Result(reason = "跨屏锚点重复，请手动选择正文")
        if (length == after.size) return Result(reason = "当前屏正文均已读", allRead = true)
        return Result(length, "已确认跨屏衔接")
    }
}

/** Cancellation tickets also reject callbacks after a later session has started. */
internal class ContinuousReading {
    private var generation = 0L
    var active = false
        private set
    private var waiting = false
    fun start(): Long { active = true; waiting = false; return ++generation }
    fun cancel() { active = false; waiting = false; generation++ }
    fun ticket(): Long? = generation.takeIf { active }
    fun accepts(ticket: Long): Boolean = active && ticket == generation
    fun beginWait(ticket: Long): Boolean {
        if (!accepts(ticket) || waiting) return false
        waiting = true; return true
    }
    fun completeWait(ticket: Long): Boolean {
        if (!accepts(ticket) || !waiting) return false
        waiting = false; return true
    }
}
