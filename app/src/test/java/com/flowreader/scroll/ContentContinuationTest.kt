package com.flowreader.scroll

import com.flowreader.diagnostics.ReadingBlock
import org.junit.Assert.*
import org.junit.Test

class ContentContinuationTest {
    @Test fun twoUniqueLongCommentParagraphsSurviveMissingAuthorHeader() {
        val a = ReadingBlock("first long comment paragraph", 1, key = "author:a", continuitySection = "reddit:comment")
        val b = ReadingBlock("second long comment paragraph", 2, key = "author:b", continuitySection = "reddit:comment")
        val after = listOf(a.copy(key = null), b.copy(key = null), block("new"))
        assertEquals(2, ContentContinuation.match(listOf(a, b), after).startIndex)
        assertNull(ContentContinuation.match(listOf(a), listOf(a.copy(key = null), block("new"))).startIndex)
        assertNull(ContentContinuation.match(listOf(a, b), listOf(a.copy(key = "other"), b.copy(key = null), block("new"))).startIndex)
        assertNull(ContentContinuation.match(listOf(a, b), listOf(a.copy(key = null, continuitySection = "reddit:original"), b.copy(key = null), block("new"))).startIndex)
        assertNull(ContentContinuation.match(listOf(a, b), listOf(a.copy(key = null), b.copy(key = null), a.copy(key = null))).startIndex)
    }
    private fun block(text: String, owner: String = text) = ReadingBlock(text, 0, key = owner)
    @Test fun overlappingScreensOnlyReturnUnreadTail() {
        val a = block("a"); val b = block("b"); val c = block("c"); val d = block("d")
        assertEquals(1, ContentContinuation.match(listOf(a, b), listOf(b, c)).startIndex)
        assertEquals(1, ContentContinuation.match(listOf(b, c), listOf(c, d)).startIndex)
    }
    @Test fun multipleAnchorsAndNodeReuseDoNotDependOnIndices() {
        val a = block("a"); val b = block("b"); val c = block("c"); val d = block("d")
        assertEquals(2, ContentContinuation.match(listOf(a, b, c),
            listOf(b.copy(nodeIndex = 42), c.copy(nodeIndex = 43), d)).startIndex)
    }
    @Test fun sameTextFromDifferentPostsIsNotAnAnchor() {
        assertNull(ContentContinuation.match(listOf(block("same", "author1")),
            listOf(block("same", "author2"), block("new"))).startIndex)
    }
    @Test fun ambiguousRepeatedAnchorsStop() {
        val a = block("a")
        assertNull(ContentContinuation.match(listOf(a, a), listOf(a, block("b"))).startIndex)
        assertNull(ContentContinuation.match(listOf(a), listOf(a, a, block("b"))).startIndex)
    }
    @Test fun truncatedOrEditedTextStopsInsteadOfSkipping() {
        assertNull(ContentContinuation.match(listOf(block("beginning", "owner")),
            listOf(block("beginning and more", "owner"), block("next"))).startIndex)
    }
    @Test fun missingAnchorEmptyAndNoNewContentStop() {
        val a = block("a")
        assertNull(ContentContinuation.match(listOf(a), listOf(block("b"))).startIndex)
        assertNull(ContentContinuation.match(emptyList(), listOf(a)).startIndex)
        assertNull(ContentContinuation.match(listOf(a), emptyList()).startIndex)
        assertNull(ContentContinuation.match(listOf(a), listOf(a)).startIndex)
        assertTrue(ContentContinuation.match(listOf(a), listOf(a)).allRead)
        assertFalse(ContentContinuation.match(listOf(a), listOf(block("b"))).allRead)
        assertNull(ContentContinuation.match(listOf(ReadingBlock("a", 1)),
            listOf(ReadingBlock("a", 9), block("b"))).startIndex)
    }
    @Test fun cancellationAndReplacementRejectLateCallbacks() {
        val session = ContinuousReading()
        val first = session.start()
        assertTrue(session.accepts(first))
        session.cancel()
        assertFalse(session.accepts(first))
        val second = session.start()
        assertFalse(session.accepts(first))
        assertTrue(session.accepts(second))
        val third = session.start()
        assertFalse(session.accepts(second))
        assertTrue(session.accepts(third))
    }
    @Test fun threeScreenCycleOnlyQueuesNewTextAndRejectsDuplicateResults() {
        val session = ContinuousReading()
        val screens = listOf(listOf(block("a"), block("b")), listOf(block("b"), block("c")),
            listOf(block("c"), block("d")))
        val spoken = screens.first().map { it.text }.toMutableList()
        var ticket = session.start()
        screens.zipWithNext().forEach { (before, after) ->
            assertTrue(session.beginWait(ticket))
            assertFalse(session.beginWait(ticket))
            assertTrue(session.completeWait(ticket))
            assertFalse(session.completeWait(ticket))
            val result = ContentContinuation.match(before, after)
            spoken += after.drop(result.startIndex!!).map { it.text }
            val previous = ticket
            ticket = session.start()
            assertFalse(session.completeWait(previous))
        }
        assertEquals(listOf("a", "b", "c", "d"), spoken)
        assertTrue(session.beginWait(ticket))
        session.cancel() // Pause, stop, navigation and shutdown all invalidate this pending operation.
        assertFalse(session.completeWait(ticket))
    }
}
