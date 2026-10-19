package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class XPostTextExtractorTest {
    @Test fun clippedQuoteStaysAfterItsParentAndDoesNotBreakCacheOrder() {
        fun node(top: Int) = AccessibilityNodeSnapshot(0, "", "", "com.twitter.android",
            "android.view.View", "", "[0,$top][100,900]", false, 0, false)
        val frame = ScreenSnapshot("com.twitter.android", "test", 1,
            listOf(node(517), node(433), node(822)), "")
        val post = ReadingBlock("own body", 0, key = "post")
        val quote = ReadingBlock("quoted body", 1, ReadingBlockKind.QUOTE, "quote", "post")
        val next = ReadingBlock("next body", 2, key = "next")
        val sorted = XPostTextExtractor.sortBlocks(frame, listOf(quote, post, next))
        assertEquals(listOf(post, quote, next), sorted)
        val cache = ReadingSessionCache()
        assertTrue(cache.ingest(listOf(post, quote), listOf(null, null)))
        val ids = cache.visibleIds.toList()
        assertTrue(cache.ingest(sorted, sorted.map { null }))
        assertEquals(ids, cache.visibleIds.take(2))
        assertTrue(cache.pending.isEmpty())
    }

    private fun summary(body: String, prefix: String = "Author. @example. 已认证. ") =
        "$prefix$body. 图像. 5小时前. 3 条回复. 46 个喜欢. 1269 认证查看次数"

    private fun snapshot(description: String, visibleBody: String = "", duplicate: Boolean = false): ScreenSnapshot {
        fun node(depth: Int, parent: Int?, slot: Int, children: Int, text: String = "", desc: String = "",
            cls: String = "android.view.View", click: Boolean = false) =
            AccessibilityNodeSnapshot(depth, text, desc, "com.twitter.android", cls, "", "[0,0][100,100]",
                false, children, false, parent, slot, isClickable = click)
        val nodes = mutableListOf(node(0, null, 0, if (duplicate) 2 else 1))
        repeat(if (duplicate) 2 else 1) { slot ->
            val root = nodes.size
            nodes += node(1, 0, slot, 3, click = true)
            nodes += node(2, root, 0, 0, desc = description)
            nodes += node(2, root, 1, 0, text = visibleBody, cls = "android.widget.TextView")
            nodes += node(2, root, 2, 1, click = true)
            nodes += node(3, root + 3, 0, 0, text = "46", cls = "android.widget.TextView")
        }
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "")
    }
    @Test fun summaryUsesBodyCoordinatesInsteadOfWholeCard() {
        val blocks = XPostTextExtractor.readingBlocks(snapshot(summary("正文"), visibleBody = "正文"))
        assertEquals(3, blocks.single().nodeIndex)
    }
    @Test fun realBodySeparatesAffiliationAndReplyHeaderWithoutDeletingLiteralBodyWords() {
        XPostTextExtractor.clearEvidence()
        try {
            val raw = summary("X. 回复给 \uFEFF@person\uFEFF. 正文里的 X 和 SpaceX 应保留")
            val first = snapshot(raw, "正文里的 X 和 SpaceX 应保留")
            assertEquals(listOf("正文里的 X 和 SpaceX 应保留"), XPostTextExtractor.extract(first))
            assertEquals(listOf("正文里的 X 和 SpaceX 应保留"), XPostTextExtractor.extract(snapshot(raw)))
            assertEquals(listOf("X. 正文"), XPostTextExtractor.extract(snapshot(summary("X. 正文"), "X. 正文")))
        } finally { XPostTextExtractor.clearEvidence() }
    }
    @Test fun nativePromotedFieldIsExcludedButLiteralRecommendationBodyIsRetained() {
        XPostTextExtractor.clearEvidence()
        try {
            val ad = summary("Buy this. 推荐")
            assertTrue(XPostTextExtractor.extract(snapshot(ad, "Buy this")).isEmpty())
            assertTrue(XPostTextExtractor.extract(snapshot(ad)).isEmpty())
            assertEquals(listOf("正文. 推荐"), XPostTextExtractor.extract(snapshot(summary("正文. 推荐"), "正文. 推荐")))
        } finally { XPostTextExtractor.clearEvidence() }
    }
    @Test fun dedicatedGifDescriptionIsNotSpokenAndItsEvidenceSurvivesClipping() {
        XPostTextExtractor.clearEvidence()
        try {
            val raw = summary("正文. GIF：Sample GIF")
            val base = snapshot(raw)
            val label = AccessibilityNodeSnapshot(2, "", "GIF：Sample GIF", "com.twitter.android", "android.view.View", "",
                "[0,0][100,100]", false, 0, false, 1, 3)
            assertEquals(listOf("正文"), XPostTextExtractor.extract(base.copy(nodes = base.nodes.mapIndexed { i, n -> if (i == 1) n.copy(childCount = 4) else n } + label)))
            assertEquals(listOf("正文"), XPostTextExtractor.extract(base))
        } finally { XPostTextExtractor.clearEvidence() }
    }
    @Test fun historicalTwoDigitYearKeepsBodyAndMediaLabelsOut() {
        for (date in listOf("25年10月4日", "2025年10月4日", "10月4日")) {
            val raw = summary("Historical body").replace("5小时前", date)
            assertEquals(listOf("Historical body"), XPostTextExtractor.extract(snapshot(raw)))
            assertNotNull(XPostTextExtractor.readingBlocks(snapshot(raw)).single().key)
        }
    }
    @Test fun dateInsideBodyIsPreserved() {
        val body = "25年10月4日. 这里是正文，不是统计尾部"
        assertEquals(body, XPostTextExtractor.summaryBody(summary(body).replace("5小时前", "25年10月4日")))
    }
    @Test fun removesAuthorTimestampAndCountsFromSummary() {
        assertEquals("这是一段正文", XPostTextExtractor.summaryBody(summary("这是一段正文")))
    }
    @Test fun inlineQuoteLabelsAreRemovedWhenQuoteSubtreeIsOffscreen() {
        val description = summary("正文. 图像. 引用. Other Author. 被引用正文")
        assertEquals(listOf("正文", "被引用正文"),
            XPostTextExtractor.extract(snapshot(description)))
    }
    @Test fun mediaAndQuoteWordsInsideSentencesRemain() {
        val body = "这是图像. 可以引用. 后续正文"
        assertEquals(listOf(body), XPostTextExtractor.extract(snapshot(summary(body))))
    }
    @Test fun removesBusinessBadgeAtMetadataPrefix() {
        assertEquals("正文", XPostTextExtractor.summaryBody(summary("正文", "Company. @example. 认证企业. ")))
        assertEquals("正文", XPostTextExtractor.summaryBody(summary("正文", "Company. @example. 已认证. 认证企业. ")))
    }
    @Test fun editedPostFooterKeepsBodyAndSameLocalKey() {
        val normal = summary("正文. 引用. Other. 引用正文")
        val edited = normal.replace("5小时前. ", "5小时前. 这是该帖子的最新版本. ")
        val a = XPostTextExtractor.readingBlocks(snapshot(normal))
        val b = XPostTextExtractor.readingBlocks(snapshot(edited))
        assertEquals(listOf("正文", "引用正文"), b.map { it.text })
        assertEquals(a.map { it.key }, b.map { it.key })
    }
    @Test fun businessBadgeWordsInsideBodyArePreserved() {
        val body = "这是一家认证企业. 正文仍需保留。"
        assertEquals(body, XPostTextExtractor.summaryBody(summary(body)))
    }
    @Test fun bodyMentioningTimeAndCountsIsPreserved() {
        val body = "5小时前，我收到3条回复和46个喜欢，数字12345也属于正文。"
        assertEquals(body, XPostTextExtractor.summaryBody(summary(body)))
    }
    @Test fun topClippedCardStillGetsBodyFromDescription() {
        assertEquals(listOf("正文在上方"), XPostTextExtractor.extract(snapshot(summary("正文在上方"))))
    }
    @Test fun summaryAndEquivalentTextViewProduceOneBody() {
        val frame = snapshot(summary("第一段。. 第二段。"), "第一段。\n\n第二段。")
        assertEquals(listOf("第一段。\n\n第二段。"), XPostTextExtractor.extract(frame))
    }
    @Test fun fullSummaryWinsOverClippedTextView() {
        val body = "这是一篇比较长的正文，后半部分仍在汇总描述中。"
        assertEquals(listOf(body), XPostTextExtractor.extract(snapshot(summary(body), "这是一篇 显示更多")))
    }
    @Test fun repostHeaderDoesNotLeakIntoBody() {
        val description = summary("正文", "被 Someone 转帖. Author. @example. 已认证. ")
        assertEquals(listOf("正文"), XPostTextExtractor.extract(snapshot(description)))
    }
    @Test fun identicalBodiesFromDifferentCardsAreNotDeduplicated() {
        assertEquals(listOf("正文", "正文"), XPostTextExtractor.extract(snapshot(summary("正文"), duplicate = true)))
    }
    @Test fun unfamiliarFormatDoesNotFallBackToCounters() {
        assertEquals("", SnapshotTextFormatter.format(snapshot("unknown format", "46")))
    }
    @Test fun handleWithoutMetadataFooterIsNotEnough() {
        assertNull(XPostTextExtractor.summaryBody("Author. @example. body mentions 5小时前"))
    }
    @Test fun supportsFooterWithoutRepliesOrReposts() {
        assertEquals("正文", XPostTextExtractor.summaryBody("Author. @example. 正文. 12分钟前. 1 个喜欢. 13 认证查看次数"))
    }
    @Test fun decimalPunctuationIsNotDiscardedByEquivalentTextChoice() {
        assertEquals(listOf("1.2"), XPostTextExtractor.extract(snapshot(summary("1.2"), "12")))
    }
    @Test fun extractsQuoteBodyWithoutQuoteAuthorOrDuplicateText() {
        fun n(d: Int, p: Int?, slot: Int, c: Int, t: String = "", desc: String = "", click: Boolean = false) =
            AccessibilityNodeSnapshot(d, t, desc, "com.twitter.android", if (t.isEmpty()) "android.view.View" else
                "android.widget.TextView", "", "[0,0][100,100]", false, c, false, p, slot, isClickable = click)
        val desc = "Author. @example. 已认证. 自己的正文. 引用. Quoted Author. 引用正文。. 1小时前. 1 条回复. 4 个喜欢. 385 认证查看次数"
        val nodes = listOf(n(0, null, 0, 1), n(1, 0, 0, 2, click = true), n(2, 1, 0, 0, desc = desc),
            n(2, 1, 1, 3, click = true), n(3, 3, 0, 0, t = "引用"), n(3, 3, 1, 0, t = "Quoted Author"),
            n(3, 3, 2, 0, t = "引用正文。"))
        val frame = ScreenSnapshot("com.twitter.android", "test", 0, nodes, "")
        assertEquals(listOf("自己的正文", "引用正文。"), XPostTextExtractor.extract(frame))
        val blocks = XPostTextExtractor.readingBlocks(frame)
        assertEquals(ReadingBlockKind.QUOTE, blocks[1].kind)
        assertEquals(blocks[0].key, blocks[1].parentKey)
        val offscreenQuote = XPostTextExtractor.readingBlocks(snapshot(desc))
        assertEquals(blocks.map { it.text }, offscreenQuote.map { it.text })
        assertEquals(blocks.map { it.key }, offscreenQuote.map { it.key })
        assertEquals(2, com.flowreader.scroll.ContentContinuation.match(blocks,
            offscreenQuote + ReadingBlock("new post", 0, key = "new")).startIndex)
        val formattedDescription = desc.replace("引用正文。", "引用段一。. 引用段二。")
        val formattedFrame = frame.copy(nodes = frame.nodes.map { node -> node.copy(
            contentDescription = node.contentDescription.replace("引用正文。", "引用段一。. 引用段二。"),
            text = if (node.text == "引用正文。") "引用段一。\n\n引用段二。" else node.text
        ) })
        val visible = XPostTextExtractor.readingBlocks(formattedFrame)
        val hidden = XPostTextExtractor.readingBlocks(snapshot(formattedDescription))
        assertNotEquals(hidden.last().text, visible.last().text)
        assertEquals(hidden.last().key, visible.last().key)
        assertEquals(hidden.last().continuityText, visible.last().continuityText)
        val cache = ReadingSessionCache()
        assertTrue(cache.ingest(hidden, hidden.map { null }))
        val originalIds = cache.visibleIds.toList()
        repeat(3) {
            assertTrue(cache.ingest(visible, visible.map { null }))
            assertEquals(originalIds, cache.visibleIds)
            assertTrue(cache.ingest(hidden, hidden.map { null }))
            assertEquals(originalIds, cache.visibleIds)
        }
        assertEquals(2, cache.entries.size)
        assertTrue(cache.pending.isEmpty())
        val next = visible + ReadingBlock("new post", 0, key = "new")
        assertTrue(cache.ingest(next, next.map { null }))
        assertEquals(originalIds, cache.visibleIds.take(2))
        assertEquals(3, cache.entries.size)
        assertEquals(2, com.flowreader.scroll.ContentContinuation.match(hidden,
            visible + ReadingBlock("new post", 0, key = "new")).startIndex)
    }
}
