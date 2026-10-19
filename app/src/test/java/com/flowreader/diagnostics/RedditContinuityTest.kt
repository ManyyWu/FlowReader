package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class RedditContinuityTest {
    @Test fun originalParagraphAnchorSurvivesAuthorHeaderRecycling() {
        fun page(header: Boolean, text: String): ScreenSnapshot {
            fun node(depth: Int, parent: Int?, id: String = "", body: String = "", desc: String = "") =
                AccessibilityNodeSnapshot(depth, body, desc, "com.reddit.frontpage", "android.view.View",
                    id, "[0,0][100,100]", false, 0, false, parent)
            return ScreenSnapshot("com.reddit.frontpage", "test", 1, listOf(
                node(0, null, "post_detail_section"),
                node(1, 0, desc = if (header) "发帖者：u/example, 2 票" else ""),
                node(1, 0, "post_self_content"),
                node(2, 2, body = text)
            ), "")
        }
        val first = RedditTextExtractor.readingBlocks(page(true, "上段\n\n重叠段一\n\n重叠段二"))
        val next = RedditTextExtractor.readingBlocks(page(false, "重叠段一\n\n重叠段二\n\n新增段"))
        assertNotNull(first.last().key)
        assertEquals(first.takeLast(2).map { it.key }, next.take(2).map { it.key })
        assertEquals(2, com.flowreader.scroll.ContentContinuation.match(first, next).startIndex)
    }
    @Test fun galleryDescriptionIsRemovedButSameWordsInBodyRemain() {
        val frame = snapshot(null, 0).let { s -> s.copy(nodes = s.nodes.mapIndexed { index, node ->
            when (index) {
                0 -> node.copy(viewId = "post_content")
                1 -> node.copy(contentDescription = "1/3, 图像, 在图像库中", isClickable = true)
                else -> node.copy(text = "正文提到 1/3, 图像, 在图像库中")
            }
        }) }
        assertEquals(listOf("正文提到 1/3, 图像, 在图像库中"), RedditTextExtractor.readingBlocks(frame).map { it.text })
        val literal = frame.copy(nodes = frame.nodes.mapIndexed { index, node ->
            if (index == 1) node.copy(text = "1/3, 图像, 在图像库中", contentDescription = "") else node
        })
        assertTrue(RedditTextExtractor.readingBlocks(literal).any { it.text == "1/3, 图像, 在图像库中" })
    }
    private fun snapshot(author: String?, y: Int, comment: Boolean = true): ScreenSnapshot {
        fun node(depth: Int, parent: Int?, id: String = "", text: String = "", desc: String = "", children: Int = 0) =
            AccessibilityNodeSnapshot(depth, text, desc, "com.reddit.frontpage", "android.view.View",
                id, "[0,$y][100,300]", false, children, false, parent)
        return ScreenSnapshot("com.reddit.frontpage", "test", 1, listOf(
            node(0, null, if (comment) "comment_layout" else "post_detail_section", children = 2),
            node(1, 0, desc = author?.let {
                if (comment) "$it 的 1 级评论, 1 票" else "发帖者：u/$it, 2 票"
            }.orEmpty()),
            node(1, 0, text = "正文内容")
        ), "", windowId = 1)
    }
    @Test fun commentOwnerSurvivesCoordinateChangesButNotAuthorChanges() {
        fun body(author: String, y: Int) = RedditTextExtractor.readingBlocks(snapshot(author, y)).last()
        assertNotNull(body("one", 0).key)
        assertEquals(body("one", 0).key, body("one", 100).key)
        assertNotEquals(body("one", 0).key, body("two", 0).key)
    }
    @Test fun postOwnerIsExtractedFromHeader() {
        assertNotNull(RedditTextExtractor.readingBlocks(snapshot("one", 0, false)).last().key)
    }
    @Test fun missingOwnerDoesNotInventIdentity() {
        assertNull(RedditTextExtractor.readingBlocks(snapshot(null, 0)).last().key)
    }
    @Test fun recycledCommentHeaderKeepsStructuralEvidenceWithoutInventingAuthor() {
        fun content(frame: ScreenSnapshot, text: String) = frame.copy(nodes = frame.nodes.mapIndexed { index, node ->
            if (index == 2) node.copy(text = text) else node
        })
        val a = "first substantial comment paragraph"
        val b = "second substantial comment paragraph"
        val first = RedditTextExtractor.readingBlocks(content(snapshot("one", 0), "earlier\n\n$a\n\n$b"))
        val next = RedditTextExtractor.readingBlocks(content(snapshot(null, 100), "$a\n\n$b\n\nnew paragraph"))
        assertNull(next.first().key)
        assertEquals("reddit:comment", next.first().continuitySection)
        assertEquals(2, com.flowreader.scroll.ContentContinuation.match(first, next).startIndex)
    }
    @Test fun invisibleAdjacentPostTextIsNotExtracted() {
        val frame = snapshot("one", 0)
        val hidden = frame.copy(nodes = frame.nodes.map { it.copy(isVisibleToUser = false) })
        assertTrue(RedditTextExtractor.readingBlocks(hidden).isEmpty())
    }
    @Test fun promotedPostUnitTextIsNotExtractedEvenWhenVisible() {
        val frame = snapshot(null, 0).let { s -> s.copy(nodes = s.nodes.mapIndexed { index, node ->
            if (index == 0) node.copy(viewId = "promoted_post_unit") else node
        }) }
        assertTrue(RedditTextExtractor.readingBlocks(frame).isEmpty())
    }
}
