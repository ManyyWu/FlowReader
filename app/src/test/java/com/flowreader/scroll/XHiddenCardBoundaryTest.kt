package com.flowreader.scroll

import com.flowreader.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class XHiddenCardBoundaryTest {
    private fun frame(cards: List<Pair<String, Boolean>>): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, id: String = "", desc: String = "", scroll: Boolean = false,
            clickable: Boolean = false, visible: Boolean = true): Int {
            val index = nodes.size
            val slot = parent?.let { nodes[it].childCount } ?: 0
            nodes += AccessibilityNodeSnapshot(parent?.let { nodes[it].depth + 1 } ?: 0, "", desc,
                "com.twitter.android", "android.view.View", id, "[0,0][100,100]", scroll, 0, false,
                parent, slot, isVisibleToUser = visible, isClickable = clickable)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = slot + 1)
            return index
        }
        add(null, "scaffold_home_tabbed")
        val list = add(0, scroll = true)
        cards.forEach { (body, visible) ->
            val card = add(list, clickable = true, visible = visible)
            add(card, desc = "Author. @example. 已认证. $body. 1小时前. 3 个喜欢. 100 认证查看次数", visible = visible)
        }
        return ScreenSnapshot("com.twitter.android", "test", 1, nodes, "", windowId = 1)
    }
    @Test fun hiddenAlreadyReadCardCanBridgeToImmediatelyFollowingBody() {
        val before = frame(listOf("previous body" to true))
        val after = frame(listOf("previous body" to false, "next body" to true))
        val read = XPostTextExtractor.readingBlocks(before)
        val next = XPostTextExtractor.readingBlocks(after)
        assertNull(ContentContinuation.match(read, next).startIndex)
        assertTrue(XHiddenCardBoundary.bridges(before, read, after, next))
        assertEquals(listOf("next body"), next.map { it.text })
    }
    @Test fun personalProfileSupportsHiddenCardBridgeWithoutReplayingOldBody() {
        fun profile(value: ScreenSnapshot) = value.copy(nodes = value.nodes.map {
            if (it.viewId == "scaffold_home_tabbed") it.copy(viewId = "UserProfileScreen") else it
        })
        val before = profile(frame(listOf("previous body" to true)))
        val after = profile(frame(listOf("previous body" to false, "next body" to true)))
        val read = XPostTextExtractor.readingBlocks(before)
        val next = XPostTextExtractor.readingBlocks(after)
        assertTrue(XHiddenCardBoundary.bridges(before, read, after, next))
        val cache = ReadingSessionCache()
        assertTrue(cache.ingest(read, read.map { null }))
        val originalId = cache.entries.single().id
        assertTrue(cache.ingest(next, next.map { null }, appendWithoutOverlap = true))
        assertEquals(listOf("previous body", "next body"), cache.entries.map { it.block.text })
        assertEquals(originalId, cache.entries.first().id)
        assertTrue(cache.pending.isEmpty())
        val ambiguous = profile(frame(listOf("previous body" to false, "intermediate body" to true, "next body" to true)))
        assertFalse(XHiddenCardBoundary.bridges(before, read, ambiguous,
            XPostTextExtractor.readingBlocks(ambiguous).drop(1)))
    }
    @Test fun changedOrRepeatedHiddenCardIsNotEvidence() {
        val before = frame(listOf("previous body" to true))
        listOf(listOf("edited body" to false, "next body" to true),
            listOf("previous body" to false, "previous body" to false, "next body" to true)).forEach { cards ->
            val after = frame(cards)
            assertFalse(XHiddenCardBoundary.bridges(before, XPostTextExtractor.readingBlocks(before),
                after, XPostTextExtractor.readingBlocks(after)))
        }
    }
    @Test fun cannotSkipAnInterveningCard() {
        val before = frame(listOf("previous body" to true))
        val after = frame(listOf("previous body" to false, "intermediate body" to true, "next body" to true))
        assertFalse(XHiddenCardBoundary.bridges(before, XPostTextExtractor.readingBlocks(before),
            after, XPostTextExtractor.readingBlocks(after).drop(1)))
        val unknownMiddle = after.copy(nodes = after.nodes.mapIndexed { index, node ->
            if (index == 5) node.copy(contentDescription = "unknown card format") else node
        })
        assertFalse(XHiddenCardBoundary.bridges(before, XPostTextExtractor.readingBlocks(before),
            unknownMiddle, XPostTextExtractor.readingBlocks(unknownMiddle)))
    }
    @Test fun incompleteOrDifferentWindowCannotBridge() {
        val before = frame(listOf("previous body" to true))
        val after = frame(listOf("previous body" to false, "next body" to true))
        assertFalse(XHiddenCardBoundary.bridges(before, XPostTextExtractor.readingBlocks(before),
            after.copy(windowId = 2), XPostTextExtractor.readingBlocks(after)))
        assertFalse(XHiddenCardBoundary.bridges(before.copy(isTraversalComplete = false),
            XPostTextExtractor.readingBlocks(before), after, XPostTextExtractor.readingBlocks(after)))
    }
}
