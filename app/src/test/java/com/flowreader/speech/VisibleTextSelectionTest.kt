package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class VisibleTextSelectionTest {
    @Test fun unkeyedBodyCanHighlightBesideUnrelatedKeyedBlocks() {
        val selection = VisibleTextSelection()
        selection.update("reddit", listOf("post", "comment"), listOf("post-key", null), "page")
        selection.select(1); selection.started()
        assertEquals(1, selection.highlightedIndex(true))
        selection.update("reddit", listOf("comment", "next"), listOf(null, "next-key"), "page")
        assertEquals(0, selection.highlightedIndex(true))
        selection.update("reddit", listOf("comment", "comment"), listOf(null, null), "page")
        assertNull(selection.highlightedIndex(true))
    }
    @Test fun markedSelectionSurvivesPositionChangeAndBriefAbsence() {
        val s = VisibleTextSelection()
        s.update("x", listOf("a", "b"), listOf("A", "B"), "page")
        s.select(1)
        s.update("x", listOf("c", "b"), listOf("C", "B"), "page")
        assertEquals(1, s.index)
        s.update("x", listOf("c"), listOf("C"), "page")
        s.update("x", listOf("b", "c"), listOf("B", "C"), "page")
        assertEquals(0, s.index)
    }
    @Test fun sameTextDifferentKeysCannotCarrySelectionOrPlaybackHighlight() {
        val s = VisibleTextSelection()
        s.update("x", listOf("other", "same"), listOf("O", "A"), "page")
        s.select(1); s.started()
        s.update("x", listOf("other", "same"), listOf("O", "B"), "page")
        assertEquals(0, s.index)
        assertNull(s.highlightedIndex(true))
    }
    @Test fun markedPlaybackDoesNotCrossNavigationContextOrDuplicateKeys() {
        val s = VisibleTextSelection()
        s.update("x", listOf("a", "b"), listOf("A", "B"), "one")
        s.select(1); s.started()
        s.update("x", listOf("b", "b"), listOf("B", "B"), "one")
        assertNull(s.highlightedIndex(true))
        s.update("x", listOf("a", "b"), listOf("A", "B"), "two")
        assertEquals(0, s.index)
        assertNull(s.highlightedIndex(true))
    }
    @Test fun doubleTapRequestsPlaybackAndThirdTapDoesNotRepeatIt() {
        val s = VisibleTextSelection()
        s.update("x", listOf("a", "b"))
        assertFalse(s.tap(1, 100, 300))
        assertTrue(s.tap(1, 200, 300))
        assertFalse(s.tap(1, 250, 300))
        assertEquals("b", s.text)
    }
    @Test fun slowTapsOrChangedScreenDoNotTriggerDoubleTap() {
        val s = VisibleTextSelection()
        s.update("x", listOf("a", "b"))
        s.tap(1, 100, 300)
        assertFalse(s.tap(1, 500, 300))
        s.update("x", listOf("c", "b"))
        assertFalse(s.tap(1, 600, 300))
    }
    @Test fun playingHighlightDisappearsOffscreenAndReturnsWithUniqueExactBody() {
        val s = VisibleTextSelection()
        s.update("x", listOf("a", "b"))
        s.select(1); s.started()
        s.update("x", listOf("c", "d"))
        assertNull(s.highlightedIndex(true))
        s.update("x", listOf("b", "c"))
        assertEquals(0, s.highlightedIndex(true))
        s.update("x", listOf("b", "b"))
        assertNull(s.highlightedIndex(true))
        s.update("other", listOf("b"))
        assertNull(s.highlightedIndex(true))
        s.stopped()
        assertNull(s.highlightedIndex(false))
    }
    @Test fun idleAndCompletedPlaybackHaveNoHighlightButKeepSelection() {
        val s = VisibleTextSelection()
        s.update("x", listOf("first", "second"))
        assertNull(s.highlightedIndex(false))
        s.select(1); s.started()
        assertEquals(1, s.highlightedIndex(true))
        s.stopped()
        assertNull(s.highlightedIndex(false))
        assertEquals("second", s.text)
    }
    @Test fun paragraphsRemainInsideOneSelectablePost() {
        val selection = VisibleTextSelection()
        selection.update("x", listOf("first\n\nparagraph", "second"))
        assertEquals("first\n\nparagraph", selection.text)
        selection.select(1)
        assertEquals("second", selection.text)
    }
    @Test fun duplicateBodiesAreSelectedByPositionWithoutGlobalDeduplication() {
        val selection = VisibleTextSelection()
        selection.update("x", listOf("same", "same"))
        assertTrue(selection.select(1))
        assertEquals(1, selection.index)
        assertFalse(selection.select(2))
    }
    @Test fun changedScreenOrPackageResetsSelectionButSameScreenKeepsIt() {
        val selection = VisibleTextSelection()
        selection.update("x", listOf("a", "b"))
        selection.select(1)
        assertFalse(selection.update("x", listOf("a", "b")))
        assertEquals(1, selection.index)
        selection.update("other", listOf("a", "b"))
        assertEquals(0, selection.index)
        selection.select(1)
        selection.update("other", listOf("b", "c"))
        assertEquals(0, selection.index)
    }
    @Test fun clearDoesNotRewriteAlreadyStartedSpeechQueue() {
        val selection = VisibleTextSelection()
        selection.update("x", listOf("a", "b"))
        selection.select(1)
        val speech = SpeechSequence()
        speech.start(listOf(selection.text))
        selection.update(null, emptyList())
        assertEquals("", selection.text)
        assertEquals("b", speech.text)
    }
}
