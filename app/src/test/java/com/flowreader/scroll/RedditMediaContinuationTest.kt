package com.flowreader.scroll

import com.flowreader.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class RedditMediaContinuationTest {
    private fun frame(after: Boolean): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, id: String = "", text: String = "", desc: String = ""): Int {
            val index = nodes.size
            val slot = parent?.let { nodes[it].childCount } ?: 0
            nodes += AccessibilityNodeSnapshot(parent?.let { nodes[it].depth + 1 } ?: 0, text, desc,
                "com.reddit.frontpage", "android.view.View", id, "[0,0][100,100]", false, 0, false, parent, slot)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = slot + 1)
            return index
        }
        add(null, "feed_lazy_column")
        if (!after) {
            val earlier = add(0, "comment_layout")
            add(earlier, desc = "earlier 的 1 级评论, 1 小时前, 2 票")
            add(earlier, text = "previous readable comment")
        }
        val media = add(0, "comment_layout")
        // The header has scrolled away, but the image still occupies the first comment.
        if (!after) add(media, desc = "imageauthor 的 1 级评论, 1 小时前, 1 票")
        add(media, "image_content_tag", desc = "图像")
        val comment = add(0, "comment_layout")
        add(comment, desc = "sameauthor 的 1 级评论, 1 小时前, 1 票")
        val body = add(comment, "comment_body_tag")
        add(body, text = "first overlapping comment paragraph")
        add(body, text = "second overlapping comment paragraph")
        if (after) add(body, text = "newly visible paragraph after scroll")
        return ScreenSnapshot("com.reddit.frontpage", "test", 1, nodes, "", windowId = 1)
    }
    @Test fun imageOnlyLeadingCommentDoesNotObstructParagraphOverlap() {
        val before = RedditTextExtractor.readingBlocks(frame(false))
        val after = RedditTextExtractor.readingBlocks(frame(true))
        assertFalse(after.any { it.text == "图像" })
        val result = ContentContinuation.match(before, after)
        assertEquals(2, result.startIndex)
        assertEquals(listOf("newly visible paragraph after scroll"), after.drop(result.startIndex!!).map { it.text })
    }
    @Test fun ordinaryBodyMentioningImageIsPreserved() {
        val source = frame(true)
        val image = source.nodes.indexOfFirst { it.viewId == "image_content_tag" }
        val changed = source.copy(nodes = source.nodes.mapIndexed { index, node ->
            if (index == image) node.copy(viewId = "", text = "正文讨论图像处理", contentDescription = "") else node
        })
        assertTrue(RedditTextExtractor.readingBlocks(changed).any { it.text == "正文讨论图像处理" })
    }
    @Test fun gifMediaDescriptionDoesNotBecomeSpokenAnchor() {
        val source = frame(true)
        val changed = source.copy(nodes = source.nodes.map { node ->
            if (node.viewId == "image_content_tag") node.copy(contentDescription = "GIF") else node
        })
        assertFalse(RedditTextExtractor.readingBlocks(changed).any { it.text == "GIF" })
    }
}
