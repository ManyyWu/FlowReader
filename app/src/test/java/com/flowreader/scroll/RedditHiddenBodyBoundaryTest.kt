package com.flowreader.scroll

import com.flowreader.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class RedditHiddenBodyBoundaryTest {
    private fun page(after: Boolean, text: String = "是这样的", repeated: Boolean = false,
        shownBefore: Boolean = false, author: String? = if (after) null else "author",
        following: String? = null, media: Boolean = true): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, id: String = "", text: String = "", desc: String = "", visible: Boolean = true): Int {
            val index = nodes.size
            val slot = parent?.let { nodes[it].childCount } ?: 0
            nodes += AccessibilityNodeSnapshot(parent?.let { nodes[it].depth + 1 } ?: 0, text, desc,
                "com.reddit.frontpage", "android.view.View", id,
                if (after) "[0,0][100,100]" else "[0,100][100,200]", false, 0, false, parent, slot,
                isVisibleToUser = visible)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = slot + 1)
            return index
        }
        add(null, "feed_lazy_column")
        if (!after) add(0, "post_self_content", text = "already read original text")
        fun comment() {
            val root = add(0, "comment_layout")
            if (author != null) add(root, desc = "$author 的 1 级评论, 1 小时前, 2 票")
            val body = add(root, "comment_body_tag")
            if (media) {
                val image = add(body)
                add(image, "image_content_tag", desc = "图像")
            }
            add(body, text = text, desc = text, visible = after || shownBefore)
        }
        comment()
        if (repeated) comment()
        if (following != null) {
            val root = add(0, "comment_layout")
            add(root, desc = "another 的 2 级评论, 1 小时前, 1 票")
            val body = add(root, "comment_body_tag")
            add(body, text = following)
        }
        return ScreenSnapshot("com.reddit.frontpage", "test", if (after) 2 else 1, nodes, "", windowId = 1)
    }
    private fun bridge(before: ScreenSnapshot, after: ScreenSnapshot): Boolean = RedditCommentBoundary.bridges(
        before, RedditTextExtractor.readingBlocks(before), after, RedditTextExtractor.readingBlocks(after))
    @Test fun hiddenUnreadCaptionWithIdenticalBodyTreeCanBridgeWithoutNewAuthorHeader() {
        val before = page(false); val after = page(true)
        assertEquals(listOf("already read original text"), RedditTextExtractor.readingBlocks(before).map { it.text })
        assertNull(ContentContinuation.match(RedditTextExtractor.readingBlocks(before),
            RedditTextExtractor.readingBlocks(after)).startIndex)
        assertTrue(bridge(before, after))
        assertEquals("是这样的", RedditTextExtractor.readingBlocks(after).first().text)
    }
    @Test fun changedBodyRepeatedCandidatesAndAlreadyReadCaptionCannotBridge() {
        assertFalse(bridge(page(false), page(true, text = "另外一条评论")))
        assertFalse(bridge(page(false), page(true, repeated = true)))
        assertFalse(bridge(page(false, repeated = true), page(true)))
        assertFalse(bridge(page(false, shownBefore = true), page(true)))
        assertFalse(bridge(page(false), page(true, author = "different author")))
    }
    @Test fun missingOldIdentityIncompleteTreeOrDifferentWindowCannotBridge() {
        assertFalse(bridge(page(false, author = null), page(true)))
        assertFalse(bridge(page(false), page(true).copy(windowId = 2)))
        assertFalse(bridge(page(false).copy(isTraversalComplete = false), page(true)))
    }
    @Test fun singleAlreadyReadLongCommentSurvivesLostHeaderAndSkipsOnlyThatComment() {
        val text = "a single long comment whose author header scrolls off screen"
        val before = page(false, text, shownBefore = true, media = false)
        val after = page(true, text, following = "new unread reply", media = false)
        val read = RedditTextExtractor.readingBlocks(before)
        val next = RedditTextExtractor.readingBlocks(after)
        assertNull(ContentContinuation.match(read, next).startIndex)
        val result = RedditCommentBoundary.knownBodyContinuation(before, read, after, next)!!
        assertEquals(1, result.startIndex)
        assertEquals(listOf("new unread reply"), next.drop(result.startIndex!!).map { it.text })
        val only = page(true, text, media = false)
        assertTrue(RedditCommentBoundary.knownBodyContinuation(before, read, only,
            RedditTextExtractor.readingBlocks(only))!!.allRead)
    }
    @Test fun alreadyReadBridgeRejectsChangedBodyDuplicatesAndMissingOldAuthor() {
        val text = "a uniquely matching long comment text"
        val before = page(false, text, shownBefore = true, media = false)
        val read = RedditTextExtractor.readingBlocks(before)
        val candidates = listOf(page(true, "$text changed", media = false),
            page(true, text, repeated = true, media = false),
            page(true, text, author = "other", media = false),
            page(true, text, media = false).copy(isTraversalComplete = false))
        candidates.forEach { after -> assertNull(RedditCommentBoundary.knownBodyContinuation(
            before, read, after, RedditTextExtractor.readingBlocks(after))) }
        val unknown = page(false, text, shownBefore = true, author = null, media = false)
        val after = page(true, text, media = false)
        assertNull(RedditCommentBoundary.knownBodyContinuation(unknown, RedditTextExtractor.readingBlocks(unknown),
            after, RedditTextExtractor.readingBlocks(after)))
    }
}
