package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ReadingDiagnosticsTest {
    private fun page(): ScreenSnapshot = ScreenSnapshot("com.reddit.frontpage", "test", 1,
        listOf(
            node(0, null, "post_detail_scaffold"),
            node(1, 0, "comment_layout"),
            node(2, 1, "comment_header", "新增未识别的作者栏标签"),
            node(2, 1, "comment_body_tag"),
            node(3, 3, "", "正文中讨论图像以及1票"),
            node(1, 0, "", "有2人正在输入…"),
            node(1, 0, "post_title", "帖子标题"),
            node(1, 0, "post_self_content"),
            node(2, 7, "", "帖子正文")
        ), "", windowId = 1)
    private fun node(depth: Int, parent: Int?, id: String, text: String = "") =
        AccessibilityNodeSnapshot(depth, text, "", "com.reddit.frontpage", "android.view.View", id,
            "[0,0][100,100]", false, 0, false, parent)
    @Test fun detailOnlyExtractsKnownBodyContainersAndTitle() {
        assertEquals(listOf("正文中讨论图像以及1票", "帖子标题", "帖子正文"),
            RedditTextExtractor.readingBlocks(page()).map { it.text })
    }
    @Test fun failureSurvivesRollingHistoryAndClearRemovesIt() {
        SnapshotStore.clear()
        val before = page(); val after = before.copy(capturedAtMillis = 2)
        val queue = mutableListOf(ReadingBlock("read", 4))
        SnapshotStore.recordContinuationFailure(before, queue, after, listOf(ReadingBlock("next", 4)), "test reason")
        queue.clear()
        repeat(12) { SnapshotStore.publish(before.copy(capturedAtMillis = 10L + it)) }
        val saved = SnapshotStore.continuationFailure!!
        assertEquals(1L, saved.before?.capturedAtMillis)
        assertEquals("read", saved.read.single().text)
        assertTrue(saved.format().contains("test reason"))
        SnapshotStore.clear(); assertNull(SnapshotStore.continuationFailure)
    }
}
