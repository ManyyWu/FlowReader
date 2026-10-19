package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class XExpandedQuoteTest {
    @Test fun quoteKeepsOwnerWhenAuthorLeavesButOriginalNodeRemainsExposed() {
        val scope = DetailSessionScope()
        val first = frame(true)
        val before = scope.readingBlocks(first, scope.process(first)).single { it.kind == ReadingBlockKind.QUOTE }
        val next = frame(false, false)
        val after = scope.readingBlocks(next, scope.process(next)).single()
        assertEquals("Quoted body", after.text)
        assertEquals(before.key, after.key)
        assertEquals(before.parentKey, after.parentKey)
        assertNotNull(after.parentKey)
    }
    private fun frame(header: Boolean, bodyVisible: Boolean = true): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, text: String = "", desc: String = "", id: String = "",
            click: Boolean = false, scroll: Boolean = false, y: Int = 0, visible: Boolean = true): Int {
            val i = nodes.size
            nodes += AccessibilityNodeSnapshot(parent?.let { nodes[it].depth + 1 } ?: 0, text, desc,
                "com.twitter.android", if (text.isNotEmpty()) "android.widget.TextView" else "android.view.View",
                id, "[0,$y][100,${y + 50}]", scroll, 0, false, parent,
                nodes.count { it.parentIndex == parent }, isVisibleToUser = visible, isClickable = click)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = nodes[parent].childCount + 1)
            return i
        }
        val root = add(null, id = "PostDetail")
        val list = add(root, scroll = true)
        // A preceding thread card has a summary; the focused post has a direct body.
        if (header) {
            val earlier = add(list, click = true, y = 100)
            add(earlier, desc = "Author. @earlier. Earlier post. 5小时前. 1 个喜欢. 10 认证查看次数", y = 100)
            val author = add(list)
            add(author, desc = "Author", id = "timeline_post_avatar")
            add(author, text = "Author")
            add(author, text = "@author")
        }
        add(list, text = "Focused post", y = 300, visible = bodyVisible)
        val wrapper = add(list, y = 500)
        val quote = add(wrapper, click = true, y = 500)
        add(quote, desc = "引用", y = 500)
        add(quote, text = "Quoted Author", y = 510)
        add(quote, text = "@quoted", y = 510)
        add(quote, text = "· 15小时", y = 510)
        add(quote, text = "翻译自英语", y = 530)
        add(quote, text = "Quoted body", y = 550)
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "", windowId = 1)
    }
    @Test fun threadOrderFollowsGeometryInsteadOfFocusedPostFirst() {
        assertEquals(listOf("Earlier post", "Focused post", "Quoted body"), XPostTextExtractor.extract(frame(true)))
    }
    @Test fun expandedQuoteWithoutAuthorHeaderIsStillExtracted() {
        assertEquals(listOf("Quoted body"), XPostTextExtractor.extract(frame(false, false)))
    }
    @Test fun quoteOnlySnapshotIsAllowedByDisplayScopeWithoutLeakingMetadata() {
        val snapshot = frame(false, false)
        val decision = DetailSessionScope().process(snapshot)
        assertFalse(decision.suppressAll)
        assertEquals("Quoted body", SnapshotTextFormatter.format(snapshot, decision.excludedNodes))
    }
    @Test fun explicitExcludedQuoteIsNotReintroduced() {
        val snapshot = frame(false, false)
        val body = snapshot.nodes.indexOfFirst { it.text == "Quoted body" }
        assertTrue(XPostTextExtractor.extract(snapshot, setOf(body)).isEmpty())
    }
}
