package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class SnapshotTreeTest {
    private fun node(depth: Int, parent: Int?, slot: Int, children: Int = 0, text: String = "") =
        AccessibilityNodeSnapshot(depth, text, "", "com.twitter.android", "android.view.View", "",
            "[0,0][100,100]", false, children, false, parent, slot)

    @Test fun nestedQuoteStaysWithinItsPostSubtree() {
        val tree = SnapshotTree(listOf(
            node(0, null, 0, 2), node(1, 0, 0, 2), node(2, 1, 0, text = "post"),
            node(2, 1, 1, 1), node(3, 3, 0, text = "quote"), node(1, 0, 1, text = "next post")
        ))
        assertEquals(listOf(1, 5), tree.childrenOf(0))
        assertEquals(listOf(1, 2, 3, 4), tree.subtreeIndices(1))
        assertEquals(listOf(3, 4), tree.subtreeIndices(3))
    }
    @Test fun missingChildSlotDoesNotShiftSourceIndices() {
        val nodes = listOf(node(0, null, 0, 3), node(1, 0, 0), node(1, 0, 2))
        val tree = SnapshotTree(nodes)
        assertEquals(listOf(1, 2), tree.childrenOf(0))
        assertEquals(2, nodes[2].indexInParent)
    }
    @Test fun identicalTextInDifferentPostsIsPreserved() {
        val tree = SnapshotTree(listOf(node(0, null, 0, 2),
            node(1, 0, 0, text = "same"), node(1, 0, 1, text = "same")))
        assertEquals(listOf(0, 1, 2), tree.subtreeIndices(0))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidParent() {
        SnapshotTree(listOf(node(0, null, 0), node(1, 4, 0)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateChildSlot() {
        SnapshotTree(listOf(node(0, null, 0, 2), node(1, 0, 0), node(1, 0, 0)))
    }
    @Test fun summaryReportsIncompleteCaptureWithoutExposingText() {
        val root = node(0, null, 0, 2).copy(isScrollable = true)
        val post = node(1, 0, 0, 1, "private content").copy(uniqueId = "node-only-id")
        val summary = ScreenSnapshot("com.twitter.android", "test", 0, listOf(root, post), "子节点读取失败=1")
            .formatStructureSummary()
        assertTrue(summary.contains("未采子节点=1"))
        assertTrue(summary.contains("提供uniqueId的节点=1"))
        assertFalse(summary.contains("private content"))
        assertFalse(summary.contains("node-only-id"))
    }
}
