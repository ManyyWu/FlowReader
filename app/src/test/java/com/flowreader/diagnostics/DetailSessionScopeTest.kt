package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DetailSessionScopeTest {
    private fun withoutAuthor(snapshot: ScreenSnapshot) = snapshot.copy(nodes = snapshot.nodes.map {
        if (it.viewId == "timeline_post_avatar" || it.text in setOf("Author", "@example"))
            it.copy(text = "", contentDescription = "", viewId = "", isVisibleToUser = false) else it
    })
    @Test fun exposedOriginalKeepsKeyWhenAuthorHeaderIsUnavailable() {
        val scope = DetailSessionScope()
        val first = frame("original")
        val firstBlocks = scope.readingBlocks(first, scope.process(first))
        val next = withoutAuthor(first)
        val nextBlocks = scope.readingBlocks(next, scope.process(next))
        assertEquals("original", nextBlocks.single().text)
        assertEquals(firstBlocks.single().key, nextBlocks.single().key)
    }
    @Test fun retainedOriginalIsNotInjectedWhenHiddenOrChanged() {
        val scope = DetailSessionScope()
        scope.process(frame("original"))
        val hidden = withoutAuthor(frame("original")).let { f -> f.copy(nodes = f.nodes.map {
            if (it.text == "original") it.copy(isVisibleToUser = false) else it
        }) }
        assertTrue(scope.readingBlocks(hidden, scope.process(hidden)).isEmpty())
        val changed = withoutAuthor(frame("different"))
        assertTrue(scope.readingBlocks(changed, scope.process(changed)).isEmpty())
    }
    @Test fun newWindowOrClearCannotInheritOriginalIdentity() {
        val scope = DetailSessionScope()
        scope.process(frame("original"))
        val next = withoutAuthor(frame("original", window = 2))
        assertTrue(scope.readingBlocks(next, scope.process(next)).isEmpty())
        scope.process(frame("original"))
        scope.clear()
        val cleared = withoutAuthor(frame("original"))
        assertTrue(scope.readingBlocks(cleared, scope.process(cleared)).isEmpty())
    }
    private fun frame(body: String? = null, comments: List<String> = emptyList(), heading: Boolean = false,
        recommendations: List<String> = emptyList(), window: Int = 1): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, text: String = "", desc: String = "", id: String = "", cls: String = "android.view.View",
            clickable: Boolean = false, scroll: Boolean = false): Int {
            val index = nodes.size
            nodes += AccessibilityNodeSnapshot(parent?.let { nodes[it].depth + 1 } ?: 0, text, desc,
                "com.twitter.android", cls, id, "[0,0][100,100]", scroll, 0, false, parent,
                if (parent == null) 0 else nodes[parent].childCount, isClickable = clickable)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = nodes[parent].childCount + 1)
            return index
        }
        val root = add(null, id = "PostDetail")
        val list = add(root, scroll = true)
        if (body != null) {
            val header = add(list)
            add(header, desc = "Author", id = "timeline_post_avatar", cls = "android.widget.ImageView")
            add(header, text = "Author", cls = "android.widget.TextView")
            add(header, text = "@example", cls = "android.widget.TextView")
            add(list, text = body, cls = "android.widget.TextView")
            val action = add(list, clickable = true)
            add(action, desc = "回复")
        }
        fun card(text: String) {
            val card = add(list, clickable = true)
            add(card, desc = "Someone. @someone. $text. 5分钟前. 1 个喜欢. 10 认证查看次数")
        }
        comments.forEach(::card)
        if (heading) {
            val header = add(list)
            add(header, text = "发现更多", cls = "android.widget.TextView")
        }
        recommendations.forEach(::card)
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "", windowId = window)
    }
    private fun displayed(scope: DetailSessionScope, frame: ScreenSnapshot): SnapshotDisplay {
        val decision = scope.process(frame)
        val text = if (decision.suppressAll) "" else SnapshotTextFormatter.format(frame, decision.excludedNodes)
        return SnapshotDisplay(text, if (text.isNotBlank()) DisplayStatus.NORMAL else decision.emptyStatus)
    }

    @Test fun originalAndCommentsDisplayNormallyBeforeBoundary() {
        assertEquals("original\n\ncomment", displayed(DetailSessionScope(), frame("original", listOf("comment"))).text)
    }
    @Test fun headingVisibleExcludesRecommendationsAndKeepsComments() {
        assertEquals("original\n\ncomment", displayed(DetailSessionScope(),
            frame("original", listOf("comment"), true, listOf("suggested"))).text)
    }
    @Test fun knownAndNewRecommendationsStayHiddenAfterHeadingLeavesScreen() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", listOf("comment"), true, listOf("suggested")))
        val result = displayed(scope, frame(recommendations = listOf("suggested", "new suggestion")))
        assertEquals("", result.text)
        assertEquals(DisplayStatus.RECOMMENDATIONS_HIDDEN, result.status)
    }
    @Test fun entirelyNewItemsAfterBoundaryAreNotGuessedAsComments() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", heading = true, recommendations = listOf("suggested")))
        val result = displayed(scope, frame(recommendations = listOf("new only")))
        assertEquals("", result.text)
        assertEquals(DisplayStatus.DETAIL_SCOPE_UNKNOWN, result.status)
    }
    @Test fun scrollingBackToKnownCommentsRestoresThemWithoutOriginalInjection() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", listOf("comment"), true, listOf("suggested")))
        displayed(scope, frame(recommendations = listOf("suggested")))
        assertEquals("comment", displayed(scope, frame(comments = listOf("comment"))).text)
    }
    @Test fun identicalCommentAndRecommendationAnchorIsNotTrusted() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", listOf("same"), true, listOf("same")))
        assertEquals("", displayed(scope, frame(comments = listOf("same"))).text)
    }
    @Test fun newOriginalResetsOldBoundary() {
        val scope = DetailSessionScope()
        displayed(scope, frame("first", heading = true, recommendations = listOf("suggested")))
        assertEquals("second\n\nnew comment", displayed(scope, frame("second", listOf("new comment"))).text)
        assertTrue(scope.summary().contains("已见推荐边界=false"))
    }
    @Test fun firstConfirmedOriginalResetsPreviouslyUnidentifiedBoundary() {
        val scope = DetailSessionScope()
        displayed(scope, frame(heading = true, recommendations = listOf("suggested")))
        assertEquals("original\n\ncomment", displayed(scope, frame("original", listOf("comment"))).text)
        assertTrue(scope.summary().contains("已见推荐边界=false"))
    }
    @Test fun publishedDisplayUsesSectionMemoryAndClearResetsIt() {
        SnapshotStore.clear()
        try {
            SnapshotStore.publish(frame("original", listOf("comment"), true, listOf("suggested")))
            assertEquals("original\n\ncomment", SnapshotStore.latestDisplay.text)
            SnapshotStore.publish(frame(recommendations = listOf("suggested")))
            assertEquals("", SnapshotStore.latestDisplay.text)
            assertEquals(DisplayStatus.RECOMMENDATIONS_HIDDEN, SnapshotStore.latestDisplay.status)
            SnapshotStore.clear()
            assertEquals(SnapshotDisplay(), SnapshotStore.latestDisplay)
        } finally { SnapshotStore.clear() }
    }
    @Test fun newWindowDoesNotInheritCommentAnchors() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", listOf("comment")))
        assertEquals("", displayed(scope, frame(comments = listOf("comment"), window = 2)).text)
    }
    @Test fun clearingSessionDropsAnchors() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", listOf("comment")))
        scope.clear()
        assertEquals("", displayed(scope, frame(comments = listOf("comment"))).text)
    }
    @Test fun anchorMemoryHasBoundedCapacity() {
        val scope = DetailSessionScope(maxAnchors = 2)
        displayed(scope, frame("original", listOf("a", "b", "c")))
        assertTrue(scope.summary().contains("评论锚点=2/2"))
    }
    @Test fun incompleteSnapshotDoesNotTeachUnknownRecommendationAsComment() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", heading = true, recommendations = listOf("suggested")))
        displayed(scope, frame("original", listOf("bad anchor")).copy(isTraversalComplete = false))
        assertEquals("", displayed(scope, frame(comments = listOf("bad anchor"))).text)
    }
    @Test fun leavingDetailPageResetsSession() {
        val scope = DetailSessionScope()
        displayed(scope, frame("original", listOf("comment")))
        val home = frame().copy(nodes = emptyList())
        displayed(scope, home)
        assertEquals("", displayed(scope, frame(comments = listOf("comment"))).text)
    }
}
