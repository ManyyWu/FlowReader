package com.flowreader.scroll

import com.flowreader.diagnostics.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Sanitized complete node trees from the reported empty trailing comment failure. */
class RedditCapturedContinuationTest {
    @Test fun capturedDownUpDownCycleCachesUnreadReplyOnceAndKeepsOldCursorIdentity() {
        SnapshotStore.clear()
        val before = frame("empty-tail-before"); val after = frame("empty-tail-after")
        SnapshotStore.publish(before)
        val ids = SnapshotStore.readingCache.visibleIds.toList()
        SnapshotStore.publish(after.copy(capturedAtMillis = 2))
        assertEquals(11, SnapshotStore.readingCache.entries.size)
        assertEquals(ids.last(), SnapshotStore.readingCache.visibleIds.first())
        val nextIds = SnapshotStore.readingCache.visibleIds.toList()
        SnapshotStore.publish(before.copy(capturedAtMillis = 3))
        assertEquals(ids, SnapshotStore.readingCache.visibleIds)
        assertFalse(SnapshotStore.readingCache.atTail)
        SnapshotStore.publish(after.copy(capturedAtMillis = 4))
        assertEquals(nextIds, SnapshotStore.readingCache.visibleIds)
        assertEquals(11, SnapshotStore.readingCache.entries.size)
        SnapshotStore.clear()
    }
    @Test fun capturedKeyAppearingOnBoundaryCommentPreservesQueueIds() {
        SnapshotStore.clear()
        try {
            SnapshotStore.publish(frame("profile-overlap-before"))
            SnapshotStore.adoptReadingView() // Explicit play can begin while the entry animation is still running.
            val original = SnapshotStore.readingCache.visibleIds.toList()
            assertEquals(6, original.size)
            SnapshotStore.publish(frame("profile-overlap-after").copy(capturedAtMillis = 2))
            assertTrue(SnapshotStore.readingCache.summary(), SnapshotStore.readingCache.pending.isEmpty())
            assertEquals(original.drop(2), SnapshotStore.readingCache.visibleIds.take(4))
            assertEquals(10, SnapshotStore.readingCache.entries.size)
        } finally { SnapshotStore.clear() }
    }
    @Test fun capturedSingleAnchorTransitionKeepsShortBoundaryComment() {
        SnapshotStore.clear()
        try {
            SnapshotStore.publish(frame("single-anchor-0"))
            SnapshotStore.adoptReadingView()
            val commentId = SnapshotStore.readingCache.visibleIds.last()
            for (index in 1..4) {
                SnapshotStore.publish(frame("single-anchor-$index").copy(capturedAtMillis = index + 1L))
                assertTrue("frame=$index ${SnapshotStore.readingCache.summary()}", SnapshotStore.readingCache.pending.isEmpty())
            }
            assertEquals(commentId, SnapshotStore.readingCache.visibleIds.first())
            assertEquals(6, SnapshotStore.readingCache.entries.size)
        } finally { SnapshotStore.clear() }
    }
    @Test fun capturedTableRepeatedCellsKeepSeparateIdsAndInsertNewRowsInOrder() {
        SnapshotStore.clear()
        try {
            SnapshotStore.publish(frame("table-repeat-0"))
            SnapshotStore.adoptReadingView()
            var queue = SnapshotStore.readingCache.entries
            val oldIds = queue.filter { it.block.text == "SpaceX" }.map { it.id }
            assertEquals(2, oldIds.size)
            for (index in 1..4) {
                SnapshotStore.publish(frame("table-repeat-$index").copy(capturedAtMillis = index + 1L))
                assertTrue("frame=$index ${SnapshotStore.readingCache.summary()}", SnapshotStore.readingCache.pending.isEmpty())
                queue = com.flowreader.speech.ReadingQueuePlan.merge(queue, SnapshotStore.readingCache.entries, 0)
            }
            val entries = SnapshotStore.readingCache.entries
            assertEquals(oldIds, entries.filter { it.block.text == "SpaceX" }.map { it.id })
            assertEquals(49, entries.size)
            val label = entries.indexOfFirst { it.block.text == "数据统计" }
            assertEquals("SpaceX", entries[label - 1].block.text)
            assertEquals("☑️ 第740次 SpaceX 发射", entries[label + 1].block.text)
            assertEquals(entries.map { it.id }, queue.map { it.id })
            assertEquals("数据统计", queue[label].block.text)
        } finally { SnapshotStore.clear() }
    }
    @Test fun capturedLongTableDistinguishesSectionTitleAndColumnHeader() {
        SnapshotStore.clear()
        try {
            SnapshotStore.publish(frame("table-section-0"))
            SnapshotStore.adoptReadingView()
            var queue = SnapshotStore.readingCache.entries
            for (index in 1..14) {
                SnapshotStore.publish(frame("table-section-$index").copy(capturedAtMillis = index + 1L))
                assertTrue("frame=$index ${SnapshotStore.readingCache.summary()}", SnapshotStore.readingCache.pending.isEmpty())
                queue = com.flowreader.speech.ReadingQueuePlan.merge(queue, SnapshotStore.readingCache.entries, 0)
            }
            val updates = SnapshotStore.readingCache.entries.filter { it.block.text == "更新" }
            assertEquals(2, updates.size)
            assertEquals(2, updates.map { it.block.key }.distinct().size)
            assertEquals(SnapshotStore.readingCache.entries.map { it.id }, queue.map { it.id })
        } finally { SnapshotStore.clear() }
    }
    private fun frame(name: String): ScreenSnapshot {
        val nodes = javaClass.getResourceAsStream("/reddit/$name.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { line ->
                val f = line.split('\t', limit = 11)
                fun text(index: Int) = String(Base64.getDecoder().decode(f[index]), Charsets.UTF_8)
                AccessibilityNodeSnapshot(f[0].toInt(), text(9), text(10), "com.reddit.frontpage", f[6], f[7],
                    f[8], f[7] == "feed_lazy_column", f[3].toInt(), false, f[1].toIntOrNull(), f[2].toInt(),
                    isVisibleToUser = f[4].toBoolean(), isClickable = f[5].toBoolean())
            }.toList()
        }
        return ScreenSnapshot("com.reddit.frontpage", "test", 1, nodes, "sanitized captured tree", windowId = 1)
    }
    @Test fun capturedSingleCommentWithTrailingEmptyPlaceholderSkipsExactlyOneReadBlock() {
        val before = frame("empty-tail-before"); val after = frame("empty-tail-after")
        val read = RedditTextExtractor.readingBlocks(before); val next = RedditTextExtractor.readingBlocks(after)
        assertEquals(7, read.size); assertEquals(5, next.size)
        assertNull(ContentContinuation.match(read, next).startIndex)
        val match = RedditCommentBoundary.knownBodyContinuation(before, read, after, next)!!
        assertEquals(1, match.startIndex)
        assertEquals(4, next.drop(match.startIndex!!).size)
        assertEquals(read.last().text, next.first().text)
    }
    @Test fun nonemptyOrUnknownTrailingItemStillPreventsStructuralShortcut() {
        val before = frame("empty-tail-before"); val after = frame("empty-tail-after")
        val placeholder = before.nodes.indexOfLast { it.viewId == "comment_layout" }
        listOf(before.nodes[placeholder].copy(text = "unknown unread item"),
            before.nodes[placeholder].copy(isClickable = true),
            before.nodes[placeholder].copy(viewId = "unknown_layout")).forEach { changed ->
            val modified = before.copy(nodes = before.nodes.mapIndexed { index, node ->
                if (index == placeholder) changed else node
            })
            assertNull(RedditCommentBoundary.knownBodyContinuation(modified,
                RedditTextExtractor.readingBlocks(modified), after, RedditTextExtractor.readingBlocks(after)))
        }
    }
}
