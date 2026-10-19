package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class XDetailTextExtractorTest {
    private fun detail(body: String?, translated: Boolean = true, includeHeader: Boolean = true): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun add(parent: Int?, text: String = "", desc: String = "", id: String = "", cls: String = "android.view.View",
            clickable: Boolean = false, scroll: Boolean = false): Int {
            val index = nodes.size
            val depth = parent?.let { nodes[it].depth + 1 } ?: 0
            val slot = nodes.count { it.parentIndex == parent }
            nodes += AccessibilityNodeSnapshot(depth, text, desc, "com.twitter.android", cls, id,
                "[0,0][100,100]", scroll, 0, false, parent, slot, isClickable = clickable)
            if (parent != null) nodes[parent] = nodes[parent].copy(childCount = nodes[parent].childCount + 1)
            return index
        }
        val root = add(null, id = "PostDetail")
        val list = add(root, scroll = true)
        if (includeHeader) {
            val header = add(list)
            add(header, desc = "Example Author", id = "timeline_post_avatar", cls = "android.widget.ImageView")
            add(header, text = "Example Author", cls = "android.widget.TextView")
            add(header, text = "@example", cls = "android.widget.TextView")
        }
        val message = add(list, clickable = true)
        add(message, text = "私信", cls = "android.widget.TextView")
        add(list, desc = "发帖选项", cls = "android.widget.ImageView", clickable = true)
        if (translated) {
            add(list, text = "翻译自英语", cls = "android.widget.TextView")
            add(list, text = "显示原文", cls = "android.widget.TextView", clickable = true)
            add(list, desc = "设置", cls = "android.widget.ImageView", clickable = true)
        }
        if (body != null) add(list, text = body, cls = "android.widget.TextView")
        val actions = add(list, clickable = true)
        add(actions, desc = "回复")
        add(actions, text = "46", cls = "android.widget.TextView")
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "")
    }
    @Test fun directDetailBodyIsReadWithoutMergedDescription() {
        val body = "详情页正文。".repeat(50)
        assertEquals(body, SnapshotTextFormatter.format(detail(body)))
    }
    @Test fun plainBodyWithoutTranslationIsRead() {
        assertEquals("Original English body.", SnapshotTextFormatter.format(detail("Original English body.", translated = false)))
    }
    @Test fun authorTranslationAndActionNumbersAreNotIncluded() {
        assertEquals("正文123", SnapshotTextFormatter.format(detail("正文123")))
    }
    @Test fun missingBodyDoesNotTurnReplyCountIntoBody() {
        assertEquals("", SnapshotTextFormatter.format(detail(null)))
    }
    @Test fun missingAuthorHeaderDoesNotGuessOtherTextAsBody() {
        assertEquals("", SnapshotTextFormatter.format(detail("uncertain", includeHeader = false)))
    }
    @Test fun summaryAndDirectBodyAreNotReadTwice() {
        val frame = detail("正文123")
        val nodes = frame.nodes.toMutableList()
        val list = 1
        nodes += AccessibilityNodeSnapshot(2, "", "Author. @example. 正文123. 5小时前. 1 个喜欢. 10 认证查看次数",
            "com.twitter.android", "android.view.View", "", "[0,0][100,100]", false, 0, false,
            list, nodes[list].childCount)
        nodes[list] = nodes[list].copy(childCount = nodes[list].childCount + 1)
        assertEquals("正文123", SnapshotTextFormatter.format(frame.copy(nodes = nodes)))
    }
    @Test fun discoverySectionIsExcludedButCommentIsKept() {
        val frame = detail("原帖正文")
        val nodes = frame.nodes.toMutableList()
        fun add(parent: Int, text: String = "", desc: String = "", clickable: Boolean = false): Int {
            val index = nodes.size
            nodes += AccessibilityNodeSnapshot(nodes[parent].depth + 1, text, desc, "com.twitter.android",
                if (text.isBlank()) "android.view.View" else "android.widget.TextView", "", "[0,0][100,100]",
                false, 0, false, parent, nodes[parent].childCount, isClickable = clickable)
            nodes[parent] = nodes[parent].copy(childCount = nodes[parent].childCount + 1)
            return index
        }
        val comment = add(1, clickable = true)
        add(comment, desc = "Commenter. @comment. 评论正文. 5分钟前. 1 个喜欢. 10 认证查看次数")
        val heading = add(1)
        add(heading, text = "发现更多")
        val recommendation = add(1, clickable = true)
        add(recommendation, desc = "Other. @other. 推荐内容. 5分钟前. 1 个喜欢. 10 认证查看次数")
        assertEquals("原帖正文\n\n评论正文", SnapshotTextFormatter.format(frame.copy(nodes = nodes)))
    }
    @Test fun literalDiscoveryWordsInBodyAreNotASectionHeading() {
        assertEquals("发现更多", SnapshotTextFormatter.format(detail("发现更多")))
    }
    @Test fun numericBodyIsNotRemovedAsAStatistic() {
        assertEquals("12345", SnapshotTextFormatter.format(detail("12345")))
    }
}
