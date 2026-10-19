package com.flowreader.scroll

import com.flowreader.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class ReadingScrollPlannerTest {
    private fun frame(tops: List<Int>, shifted: Int = 0, summary: Boolean = false): ScreenSnapshot {
        val nodes = listOf(AccessibilityNodeSnapshot(0, "", "", "com.reddit.frontpage", "android.view.View", "feed_lazy_column",
            "[0,0][1000,2000]", true, tops.size, false)) + tops.mapIndexed { index, y ->
            AccessibilityNodeSnapshot(1, "body$index", "", "com.reddit.frontpage", if (summary) "android.view.View" else "android.widget.TextView", "",
                "[$shifted,$y][${900 + shifted},${y + 100}]", false, 0, false, 0, index)
        }
        return ScreenSnapshot("com.reddit.frontpage", "test", 1, nodes, "", 1)
    }
    private fun blocks(s: ScreenSnapshot) = s.nodes.drop(1).mapIndexed { i, n -> ReadingBlock(n.text, i + 1, key = "k$i") }
    @Test fun activeBodyMovesToUpperQuarterAndCalibrationCompensatesRealDisplacement() {
        val planner = ReadingScrollPlanner()
        val before = frame(listOf(1200, 1600)); val after = frame(listOf(600, 1000))
        val initial = planner.plan(before, blocks(before), 1)!!
        assertEquals(900, initial.desiredPixels)
        assertEquals(45, initial.percent)
        assertEquals(1.2f, planner.observe(before, blocks(before), after, blocks(after), 500f)!!, .001f)
        val calibrated = planner.plan(before, blocks(before), 1)!!
        assertEquals(38, calibrated.percent)
        assertEquals(900, calibrated.desiredPixels)
    }
    @Test fun incompleteClippedSummaryOrAnimationDoesNotTrainCalibration() {
        val planner = ReadingScrollPlanner()
        val before = frame(listOf(1200,1600)); val after = frame(listOf(600,1000))
        assertNull(planner.observe(before.copy(isTraversalComplete = false), blocks(before), after, blocks(after), 500f))
        val clipped = frame(listOf(0, 1000))
        assertNull(planner.observe(before, blocks(before), clipped, blocks(clipped), 500f))
        val summary = frame(listOf(600,1000), summary = true)
        assertNull(planner.observe(before, blocks(before), summary, blocks(summary), 500f))
        assertFalse(ReadingViewportLayout.settled(frame(listOf(1200,1600), shifted = 600), blocks(before)))
        assertNull(planner.plan(frame(listOf(1200,1600), shifted = 600), blocks(before), 1))
        assertTrue(ReadingViewportLayout.settled(clipped, blocks(clipped)))
    }
    @Test fun bottomClippedParagraphStillUsesItsOpeningInsteadOfHalfScreenFallback() {
        val planner = ReadingScrollPlanner()
        val source = frame(listOf(1000, 1600))
        val clipped = source.copy(nodes = source.nodes.mapIndexed { index, n ->
            if (index == 1) n.copy(boundsInScreen = "[0,1000][900,2000]") else n
        })
        val plan = planner.plan(clipped, blocks(clipped), 1)!!
        assertEquals("当前正文定位", plan.reason)
        assertEquals(700, plan.desiredPixels)
        assertEquals(35, plan.percent)
    }
    @Test fun inconsistentMovementAndOtherWindowDoNotLeakCalibration() {
        val planner = ReadingScrollPlanner()
        val before = frame(listOf(1200,1600)); val skewed = frame(listOf(1000,1000))
        assertNull(planner.observe(before, blocks(before), skewed, blocks(skewed), 500f))
        val after = frame(listOf(600,1000))
        planner.observe(before, blocks(before), after, blocks(after), 500f)
        assertEquals(45, planner.plan(before.copy(windowId = 2), blocks(before), 1)!!.percent)
        assertEquals(42, planner.plan(before.copy(nodes = before.nodes.map { it.copy(className = "android.view.View") }), blocks(before), null)!!.percent)
        assertEquals(50, planner.plan(before.copy(windowId = 2, nodes = before.nodes.map { it.copy(className = "android.view.View") }), blocks(before), null)!!.percent)
    }
}
