package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class XExpandedTimelineTest {
    private fun frame(hidden: Boolean = false, excludedBody: Boolean = false): Pair<ScreenSnapshot, Set<Int>> {
        val nodes = listOf(
            node(0, null, 0, 5, clickable = true),
            node(1, 0, 0, 2, id = "timeline_post"),
            node(2, 1, 0, 0, "Author"),
            node(2, 1, 1, 0, "@author"),
            node(1, 0, 1, 0, "显示翻译"),
            node(1, 0, 2, 0, "Starbase, Louisiana 10/17/26", visible = !hidden),
            node(1, 0, 3, 2, clickable = true),
            node(2, 6, 0, 0, "回复"),
            node(2, 6, 1, 0, "869"),
            node(1, 0, 4, 2, clickable = true),
            node(2, 9, 0, 0, "喜欢"),
            node(2, 9, 1, 0, "31K")
        )
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "") to if (excludedBody) setOf(5) else emptySet()
    }
    private fun node(depth: Int, parent: Int?, slot: Int, children: Int, text: String = "", id: String = "",
        clickable: Boolean = false, visible: Boolean = true) = AccessibilityNodeSnapshot(depth, text, "",
        "com.twitter.android", if (text.isEmpty()) "android.view.View" else "android.widget.TextView", id,
        "[0,0][100,100]", false, children, false, parent, slot, isVisibleToUser = visible, isClickable = clickable)

    @Test fun extractsSeparatedBodyWithoutAuthorActionsOrCounts() {
        val (frame, excluded) = frame()
        val blocks = XPostTextExtractor.readingBlocks(frame, excluded)
        assertEquals(listOf("Starbase, Louisiana 10/17/26"), blocks.map { it.text })
        assertEquals(5, blocks.single().nodeIndex)
        assertNotNull(blocks.single().key)
    }
    @Test fun invisibleOrExcludedBodiesAreNotRead() {
        val (hidden, _) = frame(hidden = true)
        assertTrue(XPostTextExtractor.readingBlocks(hidden).isEmpty())
        val (frame, excluded) = frame(excludedBody = true)
        assertTrue(XPostTextExtractor.readingBlocks(frame, excluded).isEmpty())
    }
}
