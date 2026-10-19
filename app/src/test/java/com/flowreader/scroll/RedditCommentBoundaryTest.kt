package com.flowreader.scroll

import com.flowreader.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class RedditCommentBoundaryTest {
    private fun frame(comments: List<Pair<String, String?>>, prefix: Boolean = false): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, id: String = "", text: String = "", desc: String = ""): Int {
            val index = nodes.size
            val slot = parent?.let { nodes[it].childCount } ?: 0
            nodes += AccessibilityNodeSnapshot(parent?.let { nodes[it].depth + 1 } ?: 0, text, desc,
                "com.reddit.frontpage", "android.view.View", id, "[0,0][100,100]", false, 0, false, parent, slot)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = slot + 1)
            return index
        }
        add(null)
        if (prefix) add(0, text = "previous paragraph")
        comments.forEach { (author, body) ->
            val root = add(0, "comment_layout")
            add(root, desc = "$author 的 1 级评论, 1 天前, 2 票")
            if (body != null) add(root, text = body)
        }
        return ScreenSnapshot("com.reddit.frontpage", "test", 1, nodes, "", windowId = 1)
    }
    private fun blocks(frame: ScreenSnapshot) = frame.nodes.mapIndexedNotNull { index, node ->
        node.text.takeIf { it.isNotEmpty() }?.let { ReadingBlock(it, index) }
    }
    @Test fun headerOnlyCommentBridgesToFirstNewBody() {
        val before = frame(listOf("earlier" to "already read", "next" to null), prefix = true)
        val after = frame(listOf("next" to "new body", "later" to "later body"))
        assertTrue(RedditCommentBoundary.bridges(before, blocks(before), after, blocks(after)))
    }
    @Test fun alreadyReadableCommentMustNotBeReplayed() {
        val before = frame(listOf("next" to "already read"), prefix = true)
        val after = frame(listOf("next" to "already read"))
        assertFalse(RedditCommentBoundary.bridges(before, blocks(before), after, blocks(after)))
    }
    @Test fun clippedHeaderCanIdentifyVisibleTrailingCommentWithoutBeingSpoken() {
        val before = frame(listOf("earlier" to "read body", "next" to null)).let { source ->
            source.copy(nodes = source.nodes.map { node ->
                if (node.contentDescription.startsWith("next 的")) node.copy(isVisibleToUser = false) else node
            })
        }
        val after = frame(listOf("next" to "new body"))
        assertTrue(RedditCommentBoundary.bridges(before, blocks(before), after, blocks(after)))
        val hiddenRoot = before.copy(nodes = before.nodes.map { node ->
            if (node.viewId == "comment_layout") node.copy(isVisibleToUser = false) else node
        })
        assertFalse(RedditCommentBoundary.bridges(hiddenRoot, blocks(before), after, blocks(after)))
    }
    @Test fun differentFirstCommentOrRepeatedAuthorCannotBridge() {
        val before = frame(listOf("next" to null), prefix = true)
        val different = frame(listOf("other" to "body", "next" to "later"))
        assertFalse(RedditCommentBoundary.bridges(before, blocks(before), different, blocks(different)))
        val repeated = frame(listOf("next" to "one", "next" to "two"))
        assertFalse(RedditCommentBoundary.bridges(before, blocks(before), repeated, blocks(repeated)))
    }
    @Test fun incompleteOrDifferentWindowCannotBridge() {
        val before = frame(listOf("next" to null), prefix = true)
        val after = frame(listOf("next" to "body"))
        assertFalse(RedditCommentBoundary.bridges(before, blocks(before), after.copy(windowId = 2), blocks(after)))
        assertFalse(RedditCommentBoundary.bridges(before.copy(isTraversalComplete = false), blocks(before), after, blocks(after)))
    }
}
