package com.flowreader.scroll

import org.junit.Assert.*
import org.junit.Test

class PlaybackViewportFollowTest {
    @Test fun shortTopPostDoesNotScrollAwayWhileReading() {
        assertFalse(PlaybackViewportFollow.shouldAdvance("[20,100][800,160]", "[0,80][900,1080]", true))
    }
    @Test fun bottomBodyCanMoveUpHalfViewportAndStayVisible() {
        assertTrue(PlaybackViewportFollow.shouldAdvance("[20,700][800,760]", "[0,80][900,1080]", true))
    }
    @Test fun middleAndBottomClippedParagraphsMoveBeforeTheyReachLowerHalf() {
        assertTrue(PlaybackViewportFollow.shouldAdvance("[20,430][800,490]", "[0,80][900,1080]", true))
        assertTrue(PlaybackViewportFollow.shouldAdvance("[20,900][800,1080]", "[0,80][900,1080]", true))
        assertFalse(PlaybackViewportFollow.shouldAdvance("[20,230][800,490]", "[0,80][900,1080]", true))
    }
    @Test fun longBodyOrMediaHeightDoesNotTriggerPrematureScrolling() {
        assertFalse(PlaybackViewportFollow.shouldAdvance("[20,100][800,2000]", "[0,80][900,1080]", true))
    }
    @Test fun hiddenOffscreenOrInvalidPositionsNeverGuess() {
        assertFalse(PlaybackViewportFollow.shouldAdvance("[20,700][800,760]", "[0,80][900,1080]", false))
        assertFalse(PlaybackViewportFollow.shouldAdvance("[20,1200][800,1300]", "[0,80][900,1080]", true))
        assertFalse(PlaybackViewportFollow.shouldAdvance(null, "[0,80][900,1080]", true))
    }
}
