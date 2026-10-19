package com.flowreader.speech

import com.flowreader.diagnostics.ReadingBlock
import com.flowreader.diagnostics.ReadingSessionCache.Entry
import org.junit.Assert.*
import org.junit.Test

class ReadingQueuePlanTest {
    private fun e(id: Long, text: String = "text$id") = Entry(id, ReadingBlock(text, 0), null, true)
    @Test fun discoveredTableHeadingIsReadBeforeAlreadyQueuedStatistic() {
        val queue = listOf(e(1), e(2, "SpaceX"), e(3, "statistic"))
        val cache = listOf(queue[0], queue[1], e(4, "heading"), queue[2], e(5))
        val merged = ReadingQueuePlan.merge(queue, cache, 0)
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), merged.map { it.id })
        assertEquals(merged, ReadingQueuePlan.merge(merged, cache, 0))
    }
    @Test fun currentAndCompletedEntriesStayFixedAndEqualTextIdsRemainDistinct() {
        val queue = listOf(e(1), e(2, "SpaceX"), e(3, "SpaceX"))
        val cache = listOf(e(9), queue[0], queue[1], e(4), queue[2])
        val merged = ReadingQueuePlan.merge(queue, cache, 1)
        assertEquals(queue.take(2), merged.take(2))
        assertEquals(listOf(1L, 2L, 4L, 3L), merged.map { it.id })
    }
    @Test fun cacheEvictionDoesNotDiscardQueuedUnreadParagraphs() {
        val queue = listOf(e(1), e(2), e(3), e(4))
        assertEquals(listOf(1L, 2L, 3L, 5L, 4L),
            ReadingQueuePlan.merge(queue, listOf(e(1), e(3), e(5), e(4)), 0).map { it.id })
        assertEquals(queue, ReadingQueuePlan.merge(queue, listOf(e(3)), 0))
        assertEquals(queue, ReadingQueuePlan.merge(queue, listOf(e(1), e(4), e(2)), 0))
    }
    @Test fun newlyExposedEarlierParagraphIsReadOnceAfterCurrentWithoutCrossingSelectionStart() {
        val queue = listOf(e(1), e(2), e(3))
        val cache = listOf(e(9), e(1), e(4), e(2), e(3))
        val merged = ReadingQueuePlan.merge(queue, cache, 1)
        assertEquals(listOf(1L, 2L, 4L, 3L), merged.map { it.id })
        assertEquals(merged, ReadingQueuePlan.merge(merged, cache, 2))
        assertFalse(merged.any { it.id == 9L })
    }
    @Test fun confirmedFilteredFutureEntryIsRemovedWhileActiveUtteranceIsPreserved() {
        val queue = listOf(e(1), e(2), e(3))
        assertEquals(listOf(1L, 3L), ReadingQueuePlan.merge(queue, listOf(e(1), e(3)), 0, excluded = setOf(2L)).map { it.id })
        assertEquals(listOf(1L, 3L), ReadingQueuePlan.merge(queue, listOf(e(3)), 0, excluded = setOf(1L, 2L)).map { it.id })
    }
    @Test fun boundedQueueKeepsCurrentPrefixAndDelaysExcessTail() {
        val queue = listOf(e(1), e(2), e(3))
        assertEquals(listOf(1L, 2L, 4L),
            ReadingQueuePlan.merge(queue, listOf(e(1), e(2), e(4), e(3)), 1, 3).map { it.id })
    }
}
