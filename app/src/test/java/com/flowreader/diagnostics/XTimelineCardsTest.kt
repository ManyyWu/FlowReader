package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class XTimelineCardsTest {
    private fun post(body: String = "正文1.2", author: String = "alice", time: String = "5小时前", likes: Int = 46) =
        "作者. @$author. $body. $time. 3 条回复. $likes 个喜欢. 1269 认证查看次数"
    private fun frame(vararg summaries: String) = TimelineCardSample(42, 14,
        summaries.mapIndexed { i, summary -> TimelineCardCandidate(15 + i * 10, emptyList(), 16 + i * 10,
            summary, emptyList()) }, "test")

    @Test fun countersAndRelativeTimeCanChangeWithoutCreatingANewCard() {
        assertEquals(listOf(AdjacentCardMatch(15, 15)), XTimelineCards.matchAdjacent(
            frame(post()), frame(post(time = "6小时前", likes = 47))))
    }
    @Test fun sameTextByDifferentAuthorsDoesNotMatch() {
        assertTrue(XTimelineCards.matchAdjacent(frame(post()), frame(post(author = "bob"))).isEmpty())
    }
    @Test fun bodyNumbersAndMediaLabelsRemainPartOfMatchEvidence() {
        for (body in listOf("正文12", "正文1.2. 视频", "正文1.2. 引用. 另一作者. 引文")) {
            assertTrue(XTimelineCards.matchAdjacent(frame(post()), frame(post(body = body))).isEmpty())
        }
    }
    @Test fun duplicateStableKeysRemainAmbiguousEvenWhenCountersDiffer() {
        assertTrue(XTimelineCards.matchAdjacent(frame(post()), frame(post(), post(likes = 47))).isEmpty())
        assertTrue(XTimelineCards.matchAdjacent(frame(post(), post(likes = 47)), frame(post())).isEmpty())
    }
    @Test fun reorderedCardsAreNotMergedUsingStableKeys() {
        assertTrue(XTimelineCards.matchAdjacent(frame(post(author = "alice"), post(author = "bob")),
            frame(post(author = "bob", likes = 47), post(author = "alice", likes = 47))).isEmpty())
    }
    @Test fun unknownFooterChangesDoNotGetNormalized() {
        assertTrue(XTimelineCards.matchAdjacent(frame(post()), frame(post() + "未知字段")).isEmpty())
    }

    private fun sample(name: String): ScreenSnapshot {
        val nodes = javaClass.getResourceAsStream("/$name")!!.bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith("#") }.map { line ->
                val f = line.split('\t')
                AccessibilityNodeSnapshot(f[2].toInt(), f[6], f[7], "com.twitter.android", "android.view.View",
                    f[8], "[0,0][100,100]", f[5].toBoolean(), f[3].toInt(), false,
                    f[0].toInt().takeIf { it >= 0 }, f[1].toInt(), isClickable = f[4].toBoolean())
            }.toList()
        }
        return ScreenSnapshot("com.twitter.android", "test", 0, nodes, "", windowId = 42)
    }
    @Test fun realShapesGroupCardsWithoutTreatingTabsAsPosts() {
        val before = XTimelineCards.analyze(sample("timeline-before.tsv"))
        val after = XTimelineCards.analyze(sample("timeline-after.tsv"))
        assertEquals(14, before.containerIndex)
        assertEquals(listOf(15, 39, 45, 83), before.cards.map { it.rootIndex })
        assertEquals(listOf(15, 43, 77), after.cards.map { it.rootIndex })
        assertEquals(listOf(51), before.cards.single { it.rootIndex == 45 }.quoteRootIndices)
    }
    @Test fun retainedCardMatchesDespiteChangedPositionAndMissingBodyNodes() {
        val matches = XTimelineCards.matchAdjacent(XTimelineCards.analyze(sample("timeline-before.tsv")),
            XTimelineCards.analyze(sample("timeline-after.tsv")))
        assertEquals(listOf(AdjacentCardMatch(83, 15)), matches)
    }
    @Test fun incompleteCaptureDoesNotGenerateCandidates() {
        assertTrue(XTimelineCards.analyze(sample("timeline-before.tsv").copy(isTraversalComplete = false)).cards.isEmpty())
    }
    @Test fun differentWindowDoesNotMatch() {
        val before = XTimelineCards.analyze(sample("timeline-before.tsv"))
        val after = XTimelineCards.analyze(sample("timeline-after.tsv").copy(windowId = 99))
        assertTrue(XTimelineCards.matchAdjacent(before, after).isEmpty())
    }
    @Test fun identicalSummariesInMultipleCardsRemainAmbiguous() {
        val before = XTimelineCards.analyze(sample("timeline-before.tsv"))
        val after = XTimelineCards.analyze(sample("timeline-after.tsv"))
        val duplicate = after.cards.first().copy(rootIndex = 1000)
        assertTrue(XTimelineCards.matchAdjacent(before, after.copy(cards = after.cards + duplicate)).isEmpty())
    }
    @Test fun changedSummaryIsNotSilentlyMerged() {
        val before = XTimelineCards.analyze(sample("timeline-before.tsv"))
        val after = XTimelineCards.analyze(sample("timeline-after.tsv"))
        assertTrue(XTimelineCards.matchAdjacent(before,
            after.copy(cards = after.cards.map { it.copy(summary = it.summary + "changed") })).isEmpty())
    }
}
