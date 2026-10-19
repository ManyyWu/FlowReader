package com.flowreader.scroll

import com.flowreader.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class ScrollTargetsTest {
    private fun frame(secondVisible: Boolean = false): ScreenSnapshot {
        fun node(parent: Int?, depth: Int, id: String = "", scroll: Boolean = false,
            visible: Boolean = true, cls: String = "android.view.View") = AccessibilityNodeSnapshot(
            depth, "", "", "com.reddit.frontpage", cls, id, "[0,0][100,100]", scroll, 0, false,
            parentIndex = parent, isVisibleToUser = visible)
        return ScreenSnapshot("com.reddit.frontpage", "test", 1, listOf(
            node(null, 0, scroll = true, cls = "androidx.viewpager.widget.ViewPager"),
            node(0, 1, "feed_lazy_column", scroll = true),
            node(1, 2), // body
            node(0, 1), // next-comment button outside the body list
            node(0, 1, "feed_lazy_column", scroll = true, visible = secondVisible)
        ), "")
    }
    @Test fun profileScrollUsesSharedHeaderViewportInsteadOfTinyTimeline() {
        fun node(parent: Int?, id: String = "", text: String = "", scroll: Boolean = false) =
            AccessibilityNodeSnapshot(0, text, "", "com.twitter.android", "android.view.View", id,
                "[0,0][1440,3088]", scroll, 0, false, parentIndex = parent)
        val raw = listOf(
            node(null, "UserProfileScreen"), node(0),
            node(1, scroll = true), node(2, text = "帖子"), node(2, text = "回复"),
            node(1, text = "profile biography"), node(1, scroll = true), node(6, scroll = true), node(7, text = "body")
        )
        val depths = mutableListOf<Int>()
        val valid = raw.mapIndexed { index, item ->
            val depth = item.parentIndex?.let { depths[it] + 1 } ?: 0
            depths += depth
            item.copy(depth = depth, childCount = raw.count { it.parentIndex == index },
                indexInParent = raw.take(index).count { it.parentIndex == item.parentIndex })
        }
        val profile = ScreenSnapshot("com.twitter.android", "test", 1, valid, "")
        assertEquals(7, ScrollTargets.select(profile, listOf(ReadingBlock("body", 8))))
        assertEquals(1, ScrollTargets.profileViewport(profile, 7))
        assertNull(ScrollTargets.profileViewport(profile.copy(nodes = profile.nodes.map {
            if (it.viewId == "UserProfileScreen") it.copy(viewId = "PostDetail") else it
        }), 7))
    }
    @Test fun redditIgnoresPagerAndOutsideButton() {
        assertEquals(1, ScrollTargets.select(frame(), listOf(ReadingBlock("body", 2), ReadingBlock("next", 3))))
    }
    @Test fun twoVisibleBodyListsAreAmbiguous() {
        assertNull(ScrollTargets.select(frame(true), listOf(ReadingBlock("body", 2))))
    }
    @Test fun redditCanSelectBodyListWithoutReadableText() {
        assertEquals(1, ScrollTargets.select(frame(), emptyList()))
    }
    @Test fun xStillUsesNearestBodyAncestorAndRejectsPager() {
        val x = frame().copy(packageName = "com.twitter.android")
        assertEquals(1, ScrollTargets.select(x, listOf(ReadingBlock("body", 2))))
        assertNull(ScrollTargets.select(x, listOf(ReadingBlock("outside", 3))))
    }
}
