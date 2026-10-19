package com.flowreader.scroll

import org.junit.Assert.*
import org.junit.Test

class ScrollMovementEvidenceTest {
    @Test fun longUnchangedBodyCanAdvanceAfterMeasuredScroll() {
        val evidence = ScrollMovementEvidence()
        assertFalse(evidence.changed(false))
        evidence.observe(0, 700)
        assertTrue(evidence.changed(false))
        evidence.reset()
        assertFalse(evidence.changed(false))
    }
    @Test fun stationaryOrUndefinedEventsDoNotPretendToScroll() {
        val evidence = ScrollMovementEvidence()
        evidence.observe(0, 0); evidence.observe(-1, -1)
        assertFalse(evidence.changed(false))
        assertTrue(evidence.changed(true))
    }
    @Test fun scrollEventWithoutOptionalDeltasStillShowsMovement() {
        val evidence = ScrollMovementEvidence()
        assertTrue(evidence.observeEvent(-1, -1))
        assertTrue(evidence.changed(false))
        evidence.reset()
        assertFalse(evidence.observeEvent(0, 0))
        assertFalse(evidence.changed(false))
    }
    @Test fun reverseMovementIsAlsoEvidence() {
        val evidence = ScrollMovementEvidence()
        evidence.observe(0, -500)
        assertTrue(evidence.changed(false))
    }
}
