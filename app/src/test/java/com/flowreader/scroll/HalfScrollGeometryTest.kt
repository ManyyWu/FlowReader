package com.flowreader.scroll

import org.junit.Assert.*
import org.junit.Test

class HalfScrollGeometryTest {
    private val view = HalfScrollGeometry.Box(0, 100, 1000, 2100)
    @Test fun swipeDistanceIsHalfViewportAndRemainsInsideIt() {
        val swipe = HalfScrollGeometry.plan(view, null)!!
        assertEquals(1000f, swipe.fromY - swipe.toY, .01f)
        assertTrue(swipe.toY > view.top && swipe.fromY < view.bottom)
    }
    @Test fun reportedVideoViewportUsesLeftGutterWithoutChangingDistance() {
        val screen = HalfScrollGeometry.Box(0, 0, 1440, 3088)
        val videos = listOf(HalfScrollGeometry.Box(224, 485, 1398, 1145), HalfScrollGeometry.Box(224, 1666, 1398, 2326))
        val original = HalfScrollGeometry.plan(screen, null, 31f)!!
        val fixed = HalfScrollGeometry.plan(screen, null, 31f, videos)!!
        assertEquals(1296f, original.x, .01f)
        assertEquals(144f, fixed.x, .01f)
        assertEquals(original.fromY, fixed.fromY, .01f)
        assertEquals(original.toY, fixed.toY, .01f)
        assertNotNull(HalfScrollGeometry.plan(screen, null, 31f, listOf(screen)))
        assertNull(HalfScrollGeometry.plan(screen, screen, 31f, videos))
    }
    @Test fun pathAvoidsOverlayOrRefusesFullyCoveredArea() {
        val right = HalfScrollGeometry.Box(800, 0, 1000, 2100)
        assertTrue(HalfScrollGeometry.plan(view, right)!!.x < 800)
        assertNull(HalfScrollGeometry.plan(view, view))
        assertNull(HalfScrollGeometry.plan(HalfScrollGeometry.Box(0, 0, 10, 10), null))
    }
}
