package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class PlaybackBoundaryTest {
    @Test fun scrollAndTemporarilyMissingIdentityKeepPlayback() {
        val boundary = PlaybackBoundary()
        boundary.start("x", 1, true, "post")
        assertFalse(boundary.changed("x", 1, true, null, true))
        assertFalse(boundary.changed("x", 1, true, "post", true))
    }
    @Test fun enteringOrLeavingDetailStopsEvenWithoutClickEvent() {
        val boundary = PlaybackBoundary()
        boundary.start("x", 1, false, null)
        assertTrue(boundary.changed("x", 1, true, "post", true))
        boundary.start("x", 1, true, "post")
        assertTrue(boundary.changed("x", 1, false, null, true))
    }
    @Test fun confirmedOtherPostStopsAndFirstIdentityOnlyEstablishesBaseline() {
        val boundary = PlaybackBoundary()
        boundary.start("x", 1, true, null)
        assertFalse(boundary.changed("x", 1, true, "one", true))
        assertTrue(boundary.changed("x", 1, true, "two", true))
    }
    @Test fun incompleteTreeCannotPretendToBeNavigation() {
        val boundary = PlaybackBoundary()
        boundary.start("x", 1, true, "post")
        assertFalse(boundary.changed("x", 2, false, null, false))
        assertTrue(boundary.changed("x", 2, true, "post", true))
        assertTrue(boundary.changed("other", 2, false, null, false))
    }
    @Test fun clearAndReplacementDiscardOldBoundary() {
        val boundary = PlaybackBoundary()
        boundary.start("x", 1, true, "one")
        boundary.clear()
        assertFalse(boundary.changed("x", 1, true, "two", true))
        boundary.start("x", 1, true, "two")
        assertFalse(boundary.changed("x", 1, true, "two", true))
    }
}
