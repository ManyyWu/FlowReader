package com.flowreader.scroll

import org.junit.Assert.*
import org.junit.Test

class StableEvidenceTest {
    @Test fun incompleteFrameThenSameEvidenceRestartsTimer() {
        val state = StableEvidence()
        val values = listOf("body at position")
        assertFalse(state.observe(values, true, 0))
        assertFalse(state.observe(values, false, 400))
        assertFalse(state.observe(values, true, 500))
        assertFalse(state.observe(values, true, 999))
        assertTrue(state.observe(values, true, 1000))
    }
    @Test fun movementRestartsTimerAndRepeatedFramesCanSettle() {
        val state = StableEvidence()
        assertFalse(state.observe(listOf("body at 10"), true, 0))
        assertFalse(state.observe(listOf("body at 20"), true, 400))
        assertFalse(state.observe(listOf("body at 20"), true, 899))
        assertTrue(state.observe(listOf("body at 20"), true, 900))
        assertFalse(state.observe(listOf("body at 20"), false, 901))
    }
}
