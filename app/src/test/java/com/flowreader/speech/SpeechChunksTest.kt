package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechChunksTest {
    @Test fun longMixedTextPreservesContentAndEngineLimit() {
        val input = "中文句子。Hello world! 😀 More text; ".repeat(500)
        val parts = SpeechChunks.split(input, 99)
        assertEquals(input.replace("😀", ""), parts.joinToString(""))
        assertTrue(parts.all { it.length <= 99 })
    }
    @Test fun surrogatePairAtLimitIsNotSplit() {
        val parts = SpeechChunks.split("a".repeat(8) + "𐐀" + "b".repeat(12), 9)
        assertTrue(parts.all { !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
        assertEquals("a".repeat(8) + "𐐀" + "b".repeat(12), parts.joinToString(""))
    }
    @Test fun blankInputProducesNoUtterances() {
        assertTrue(SpeechChunks.split(" \n\t", 10).isEmpty())
    }
}
