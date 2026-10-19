package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechSequenceTest {
    @Test fun appendingWhileReadingKeepsUtteranceAndCursorAndRejectsOldCompletions() {
        val sequence = SpeechSequence(); sequence.start(listOf("a", "b"))
        val id = sequence.id
        assertTrue(sequence.append(listOf("短句", "d")))
        assertEquals(id, sequence.id); assertEquals("a", sequence.text)
        assertEquals(4, sequence.size)
        assertTrue(sequence.advance(id)); assertFalse(sequence.advance(id))
        assertTrue(sequence.advance(sequence.id)); assertEquals("短句", sequence.text)
        sequence.stop(); assertFalse(sequence.append(listOf("late")))
    }
    @Test fun replacingUnreadTailPreservesCurrentParagraphChunksAndCallback() {
        val sequence = SpeechSequence()
        sequence.start(listOf("current part one", "current part two", "old next"))
        val id = sequence.id
        assertTrue(sequence.replaceTail(2, listOf("new heading", "old next", "later")))
        assertEquals(id, sequence.id)
        assertEquals("current part one", sequence.text)
        assertFalse(sequence.replaceTail(0, listOf("invalid")))
        assertTrue(sequence.advance(id))
        assertEquals("current part two", sequence.text)
        assertFalse(sequence.advance(id))
        assertTrue(sequence.advance(sequence.id))
        assertEquals("new heading", sequence.text)
        sequence.stop()
        assertFalse(sequence.replaceTail(1, listOf("late")))
    }
    @Test fun stopRejectsLateCompletion() {
        val sequence = SpeechSequence()
        sequence.start(listOf("first", "second"))
        val old = sequence.id
        sequence.stop()
        assertFalse(sequence.advance(old))
        assertFalse(sequence.active)
    }
    @Test fun replacedSessionRejectsOldCallback() {
        val sequence = SpeechSequence()
        sequence.start(listOf("old"))
        val old = sequence.id
        sequence.start(listOf("new", "next"))
        assertFalse(sequence.advance(old))
        assertEquals("new", sequence.text)
        assertEquals(0, sequence.index)
    }
    @Test fun duplicateCompletionDoesNotSkipNextChunk() {
        val sequence = SpeechSequence()
        sequence.start(listOf("first", "second"))
        val first = sequence.id
        assertTrue(sequence.advance(first))
        assertFalse(sequence.advance(first))
        assertEquals("second", sequence.text)
        assertTrue(sequence.advance(sequence.id))
        assertFalse(sequence.active)
    }
}
