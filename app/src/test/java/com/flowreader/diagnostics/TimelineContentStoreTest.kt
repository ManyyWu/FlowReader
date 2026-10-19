package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class TimelineContentStoreTest {
    @Test fun repeatedGeometryCountersAndChildChangesDoNotExpireHistoricalPair() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val original = cache.visibleIds
        repeat(40) { i ->
            cache.ingest(snapshot(post(3).replace("46 个喜欢", "${47 + i} 个喜欢") to "body$i",
                post(4) to "four", offset = i))
        }
        assertTrue(cache.summary().contains("不同有序锚点）=2/10"))
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertEquals(original, cache.visibleIds)
        assertEquals(2, cache.historicalRecoveryCount)
        assertEquals(4, cache.cards().size)
    }
    @Test fun repeatedBackAndForthRefreshesFramesWithoutConsumingSlots() {
        val cache = TimelineContentStore(requirePostBody = true)
        val first = snapshot(post(1) to "one", post(2) to "two")
        val second = snapshot(post(3) to "three", post(4) to "four")
        cache.ingest(first)
        val ids = cache.visibleIds
        repeat(20) { cache.ingest(second); cache.ingest(first) }
        assertEquals(ids, cache.visibleIds)
        assertEquals(4, cache.cards().size)
        assertTrue(cache.summary().contains("不同有序锚点）=2/10"))
    }
    @Test fun historicalPairSurvivesBriefIncompleteScrollWithoutLearningPartialNodes() {
        var now = 0L
        val cache = TimelineContentStore(requirePostBody = true, monotonicMillis = { now })
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.ingest(snapshot(post(3) to "three", post(4) to "four"))
        val before = cache.cards()
        now = 100
        cache.ingest(snapshot(post(99) to "untrusted").copy(isTraversalComplete = false))
        assertEquals(before, cache.cards())
        assertTrue(cache.visibleIds.isEmpty())
        now = 200
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertEquals(ids, cache.visibleIds)
        assertEquals(2, cache.historicalRecoveryCount)
    }
    @Test fun repeatedIncompleteSamplesDoNotExtendDeadline() {
        var now = 0L
        val cache = TimelineContentStore(monotonicMillis = { now })
        val frame = snapshot("post" to "body")
        cache.ingest(frame)
        cache.ingest(frame.copy(isTraversalComplete = false))
        now = 1_500
        cache.ingest(frame.copy(isTraversalComplete = false))
        now = 2_001
        cache.ingest(frame)
        assertEquals(2, cache.cards().size)
        assertTrue(cache.summary().contains("不完整采样等待超时"))
    }
    @Test fun partialWrongWindowOrMissingTimelineCannotPreserveContinuity() {
        val frame = snapshot("post" to "body")
        val partials = listOf(frame.copy(windowId = 2), frame.copy(nodes = emptyList()),
            frame.copy(packageName = "other"), frame.copy(nodes = frame.nodes.map {
                if (it.viewId == "scaffold_home_tabbed") it.copy(viewId = "PostDetail") else it
            }))
        for (partial in partials) {
            val cache = TimelineContentStore(monotonicMillis = { 0L })
            cache.ingest(frame)
            cache.ingest(partial.copy(isTraversalComplete = false))
            cache.ingest(frame)
            assertEquals(2, cache.cards().size)
        }
    }
    @Test fun explicitNavigationDuringPartialGapStillBreaksContinuity() {
        val cache = TimelineContentStore(monotonicMillis = { 0L })
        val frame = snapshot("post" to "body")
        cache.ingest(frame)
        cache.ingest(frame.copy(isTraversalComplete = false))
        cache.breakContinuity()
        cache.ingest(frame)
        assertEquals(2, cache.cards().size)
    }
    @Test fun diagnosticsDistinguishExpiredFramesFromEvictedEntries() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        repeat(11) { cache.ingest(snapshot(post(3 + it * 2) to "three", post(4 + it * 2) to "four")) }
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertTrue(cache.summary().contains("缓存仍在但历史帧缺锚点"))
        assertEquals(0, cache.historicalRecoveryCount)
    }
    @Test fun diagnosticsPreserveBreakReasonAndClearRemovesIt() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        cache.breakContinuity("非X窗口事件")
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertTrue(cache.summary().contains("连续性中断/非X窗口事件"))
        assertTrue(cache.summary().contains("未恢复/无连续前帧"))
        cache.clear()
        assertFalse(cache.summary().contains("非X窗口事件"))
    }
    @Test fun diagnosticsExplainHistoricalAmbiguityWithoutLeakingText() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(1) to "one", post(2) to "two"))
        cache.ingest(snapshot(post(3) to "three"))
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertTrue(cache.summary().contains("未恢复/历史键对应多个ID"))
        assertFalse(cache.summary().contains("@user"))
        assertFalse(cache.summary().contains("完整正文"))
    }
    @Test fun returningAdjacentPairReusesHistoricalIdsDespiteCounterChanges() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.ingest(snapshot(post(3) to "three", post(4) to "four"))
        cache.ingest(snapshot(post(1).replace("46 个喜欢", "47 个喜欢") to "one", post(2) to "two"))
        assertEquals(ids, cache.visibleIds)
        assertEquals(4, cache.cards().size)
        assertTrue(cache.lastResult.contains("历史恢复2项"))
        assertEquals(2, cache.historicalRecoveryCount)
        cache.clear()
        assertEquals(0, cache.historicalRecoveryCount)
    }
    @Test fun isolatedReturningCardDoesNotUseHistoricalIdentity() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.ingest(snapshot(post(3) to "three"))
        cache.ingest(snapshot(post(1) to "one"))
        assertTrue(cache.visibleIds.intersect(ids).isEmpty())
    }
    @Test fun reversedHistoricalPairIsNotRecovered() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.ingest(snapshot(post(3) to "three"))
        cache.ingest(snapshot(post(2) to "two", post(1) to "one"))
        assertTrue(cache.visibleIds.intersect(ids).isEmpty())
    }
    @Test fun navigationBreakDiscardsHistoricalPairEvidence() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.breakContinuity()
        cache.ingest(snapshot(post(3) to "three"))
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertTrue(cache.visibleIds.intersect(ids).isEmpty())
    }
    @Test fun ambiguousHistoricalKeyIsNotRecovered() {
        val cache = TimelineContentStore(requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.ingest(snapshot(post(3) to "three"))
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertTrue(cache.visibleIds.intersect(ids).isEmpty())
    }
    @Test fun evictedHistoricalPairCannotResurrectOldIds() {
        val cache = TimelineContentStore(maxCards = 2, requirePostBody = true)
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        val ids = cache.visibleIds
        cache.ingest(snapshot(post(3) to "three", post(4) to "four"))
        cache.ingest(snapshot(post(1) to "one", post(2) to "two"))
        assertTrue(cache.visibleIds.intersect(ids).isEmpty())
        assertEquals(2, cache.cards().size)
    }
    private fun post(index: Int) = "作者. @user$index. 这是一段用于测试缓存保留与淘汰的完整正文内容$index. 5小时前. 3 条回复. 46 个喜欢. 1269 认证查看次数"

    @Test fun counterUpdatesReuseCachedIdAndRetainLatestNodes() {
        val cache = TimelineContentStore(maxCards = 10, requirePostBody = true)
        cache.ingest(snapshot(post(1) to "正文内容1"))
        val id = cache.cards().single().localId
        repeat(15) { count ->
            cache.ingest(snapshot(post(1).replace("46 个喜欢", "${47 + count} 个喜欢") to "正文内容1"))
        }
        assertEquals(id, cache.cards().single().localId)
        assertEquals(0, cache.evictedCount)
        assertTrue(cache.cards().single().latestObservation.nodes.any { "61 个喜欢" in it.contentDescription })
        assertTrue(cache.cards().single().observations.size <= 4)
    }

    @Test fun postWindowKeepsTenRecentObservationsAndEvictsOldest() {
        val cache = TimelineContentStore(maxCards = 10, requirePostBody = true)
        repeat(15) { index -> cache.ingest(snapshot(post(index) to "正文内容$index")) }
        assertEquals(10, cache.cards().size)
        assertEquals(5, cache.evictedCount)
        assertEquals((6L..15L).toList(), cache.cards().map { it.localId })
    }

    @Test fun postWindowRejectsUnrecognizedCardsWithoutEvictingHistory() {
        val cache = TimelineContentStore(maxCards = 10, requirePostBody = true)
        cache.ingest(snapshot(post(1) to "正文内容1", "推荐关注列表" to "关注"))
        assertEquals(1, cache.cards().size)
        val before = cache.cards()
        cache.ingest(snapshot("推荐关注列表" to "关注"))
        assertEquals(before, cache.cards())
        assertTrue(cache.visibleIds.isEmpty())
    }

    @Test fun postWindowRetainsOffscreenNodesAndClearRemovesThem() {
        val cache = TimelineContentStore(maxCards = 10, requirePostBody = true)
        cache.ingest(snapshot(post(1) to "正文内容1"))
        cache.ingest(snapshot(post(1) to "", post(2) to "正文内容2"))
        assertEquals(2, cache.cards().size)
        assertTrue(cache.cards().first().richestObservation.nodes.any { it.text == "正文内容1" })
        cache.clear()
        assertTrue(cache.cards().isEmpty())
    }

    private fun snapshot(vararg cards: Pair<String, String>, window: Int = 1, offset: Int = 0): ScreenSnapshot {
        fun node(depth: Int, parent: Int?, slot: Int, children: Int, text: String = "", desc: String = "",
            viewId: String = "", scroll: Boolean = false, click: Boolean = false) =
            AccessibilityNodeSnapshot(depth, text, desc, "com.twitter.android", "android.view.View", viewId,
                "[0,$offset][100,${100 + offset}]", scroll, children, false, parent, slot, isClickable = click)
        val nodes = mutableListOf(node(0, null, 0, 1, viewId = "scaffold_home_tabbed"),
            node(1, 0, 0, cards.size, scroll = true))
        cards.forEachIndexed { slot, (summary, body) ->
            val root = nodes.size
            nodes += node(2, 1, slot, 2, click = true)
            nodes += node(3, root, 0, 0, desc = if (XPostTextExtractor.summaryBody(summary) != null)
                "测试作者名称用于补足结构候选摘要长度. $summary" else summary.padEnd(70, '_'))
            nodes += node(3, root, 1, 0, text = body)
        }
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "", windowId = window)
    }

    @Test fun offscreenBodyIsRetainedAlongsideLatestObservation() {
        val cache = TimelineContentStore()
        cache.ingest(snapshot("post" to "complete body"))
        val id = cache.cards().single().localId
        cache.ingest(snapshot("post" to ""))
        val card = cache.cards().single()
        assertEquals(id, card.localId)
        assertTrue(card.richestObservation.nodes.any { it.text == "complete body" })
        assertFalse(card.latestObservation.nodes.any { it.text == "complete body" })
        SnapshotTree(card.richestObservation.nodes) // Local parent/depth remapping is valid.
    }
    @Test fun coordinateOnlyChangesDoNotAccumulateVersions() {
        val cache = TimelineContentStore()
        repeat(20) { cache.ingest(snapshot("post" to "body", offset = it * 10)) }
        assertEquals(1, cache.cards().size)
        assertEquals(1, cache.cards().single().observations.size)
    }
    @Test fun oldestNonVisibleCardIsEvicted() {
        val cache = TimelineContentStore(maxCards = 2)
        cache.ingest(snapshot("a" to "A", "b" to "B"))
        val b = cache.cards().last().localId
        cache.ingest(snapshot("b" to "B", "c" to "C"))
        assertEquals(2, cache.cards().size)
        assertTrue(cache.cards().any { it.localId == b })
        assertFalse(cache.cards().any { it.richestObservation.nodes.any { n -> n.text == "A" } })
        assertEquals(1, cache.evictedCount)
    }
    @Test fun protectedWorkingSetOverCapacityRejectsWholeUpdate() {
        val cache = TimelineContentStore(maxCards = 2)
        cache.ingest(snapshot("a" to "A", "b" to "B"))
        val before = cache.cards()
        cache.pin(setOf(before.first().localId))
        cache.ingest(snapshot("b" to "B", "c" to "C"))
        assertEquals(before, cache.cards())
        assertEquals(1, cache.rejectedCount)
        assertEquals(0, cache.evictedCount)
    }
    @Test fun identicalSummariesDoNotMergeDifferentCards() {
        val cache = TimelineContentStore()
        val frame = snapshot("same" to "one", "same" to "two")
        cache.ingest(frame)
        cache.ingest(snapshot("same" to "one", "same" to "two", offset = 10))
        assertEquals(4, cache.cards().size)
        assertEquals(2, cache.visibleIds.size)
    }
    @Test fun repeatedIdenticalFrameDoesNotCreateAmbiguousDuplicates() {
        val cache = TimelineContentStore()
        val frame = snapshot("same" to "one", "same" to "two")
        repeat(20) { cache.ingest(frame) }
        assertEquals(2, cache.cards().size)
        assertEquals(2, cache.visibleIds.size)
    }
    @Test fun characterBudgetIsEnforced() {
        val cache = TimelineContentStore(maxCharacters = 10)
        cache.ingest(snapshot("post" to "body"))
        assertTrue(cache.cards().isEmpty())
        assertEquals(1, cache.rejectedCount)
    }
    @Test fun nodeBudgetIsEnforced() {
        val cache = TimelineContentStore(maxNodes = 2)
        cache.ingest(snapshot("post" to "body"))
        assertTrue(cache.cards().isEmpty())
        assertEquals(1, cache.rejectedCount)
    }
    @Test fun changingWindowsDoesNotReuseLocalIdentity() {
        val cache = TimelineContentStore()
        cache.ingest(snapshot("post" to "body", window = 1))
        val id = cache.cards().single().localId
        cache.ingest(snapshot("post" to "body", window = 2))
        assertEquals(2, cache.cards().size)
        assertFalse(id in cache.visibleIds)
    }
    @Test fun briefIncompleteSnapshotSkipsLearningAndKeepsIdentity() {
        val cache = TimelineContentStore(monotonicMillis = { 100L })
        val frame = snapshot("post" to "body")
        cache.ingest(frame)
        cache.ingest(frame.copy(isTraversalComplete = false))
        assertEquals(1, cache.cards().size)
        cache.ingest(frame)
        assertEquals(1, cache.cards().size)
        assertTrue(cache.summary().contains("完整时间线恢复"))
    }
    @Test fun clearDropsAllContentWithoutReusingOldIdentity() {
        val cache = TimelineContentStore()
        cache.ingest(snapshot("post" to "body"))
        val oldId = cache.cards().single().localId
        cache.clear()
        assertTrue(cache.cards().isEmpty())
        cache.ingest(snapshot("post" to "body"))
        assertTrue(cache.cards().single().localId > oldId)
    }
    @Test fun observationLimitKeepsRichestAndLatest() {
        val cache = TimelineContentStore(maxObservationsPerCard = 3)
        val long = "original full body ".repeat(10)
        cache.ingest(snapshot("post" to long))
        repeat(10) { cache.ingest(snapshot("post" to "partial $it")) }
        val card = cache.cards().single()
        assertEquals(3, card.observations.size)
        assertTrue(card.richestObservation.nodes.any { it.text == long })
        assertTrue(card.latestObservation.nodes.any { it.text == "partial 9" })
    }
    @Test fun explicitNavigationBreakPreventsCrossPageMerge() {
        val cache = TimelineContentStore()
        val frame = snapshot("post" to "body")
        cache.ingest(frame); cache.breakContinuity(); cache.ingest(frame)
        assertEquals(2, cache.cards().size)
    }
}
