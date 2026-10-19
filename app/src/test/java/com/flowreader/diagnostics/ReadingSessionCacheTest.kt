package com.flowreader.diagnostics

import org.junit.Assert.*
import org.junit.Test

class ReadingSessionCacheTest {
    @Test fun sameKeyWithEditedCanonicalBodyCannotReuseId() {
        val cache = ReadingSessionCache()
        val original = ReadingBlock("one. two", 0, key = "quote", continuityText = "one. two")
        assertTrue(cache.ingest(listOf(original), listOf(null)))
        val id = cache.entries.single().id
        assertFalse(cache.ingest(listOf(original.copy(text = "edited", continuityText = "edited")), listOf(null)))
        assertEquals(id, cache.entries.single().id)
        assertEquals("one. two", cache.entries.single().block.text)
        assertEquals(1, cache.pending.size)
    }
    @Test fun cachedUnreadBoundaryOutsideViewportDoesNotProhibitForwardScroll() {
        val cache = ReadingSessionCache()
        put(cache, "title", "body", "短句")
        val tail = cache.entries.last().id
        put(cache, "title", "body")
        assertFalse(cache.atTail)
        assertTrue(cache.canAdvanceForward(browsingBack = false))
        assertFalse(cache.canAdvanceForward(browsingBack = true))
        assertEquals(tail, cache.entries.last().id)
        assertEquals("短句", cache.entries.last().block.text)
        assertFalse(put(cache, "unconfirmed"))
        assertFalse(cache.canAdvanceForward(browsingBack = false))
    }
    @Test fun stationaryAnonymousBodyKeepsItsIdAcrossRepeatedCompleteCaptures() {
        SnapshotStore.clear()
        val nodes = listOf(
            AccessibilityNodeSnapshot(0, "", "", "com.reddit.frontpage", "android.view.View", "comment_layout",
                "[0,0][100,100]", false, 1, false),
            AccessibilityNodeSnapshot(1, "one anonymous comment paragraph", "", "com.reddit.frontpage", "android.widget.TextView", "comment_body_tag",
                "[0,0][100,100]", false, 0, false, parentIndex = 0))
        val frame = ScreenSnapshot("com.reddit.frontpage", "test", 1, nodes, "", windowId = 1)
        SnapshotStore.publish(frame); val id = SnapshotStore.readingCache.visibleIds.single()
        repeat(4) { SnapshotStore.publish(frame.copy(capturedAtMillis = 2L + it)) }
        assertEquals(id, SnapshotStore.readingCache.visibleIds.single())
        assertTrue(SnapshotStore.readingCache.pending.isEmpty())
        SnapshotStore.clear()
    }
    @Test fun authorAppearingOnClippedCommentKeepsIdentityWithTwoOrderedAnchors() {
        val cache = ReadingSessionCache()
        fun comment(text: String, key: String?) = b(text, key).copy(continuitySection = "reddit:comment")
        val before = listOf(comment("first", "a"), comment("second", "b"), comment("edge", null))
        cache.adopt(before, List(3) { null })
        val ids = cache.visibleIds.toList()
        assertTrue(cache.ingest(before.take(2) + comment("edge", "c") + comment("next", "d"), List(4) { null }))
        assertEquals(ids, cache.visibleIds.take(3))
        assertEquals("c", cache.entries[2].block.key)
    }
    @Test fun missingAuthorDoesNotMergeRepeatedOrConflictingComments() {
        fun comment(text: String, key: String?) = b(text, key).copy(continuitySection = "reddit:comment")
        val before = listOf(comment("first", "a"), comment("second", "b"), comment("edge", null))
        for (tail in listOf(listOf(comment("edge", "c"), comment("edge", "d")),
            listOf(comment("different", "c")))) {
            val cache = ReadingSessionCache(); cache.adopt(before, List(3) { null })
            assertFalse(cache.ingest(before.take(2) + tail, List(2 + tail.size) { null }))
            assertEquals(before, cache.entries.map { it.block })
        }
        val cache = ReadingSessionCache()
        cache.adopt(before.dropLast(1) + comment("edge", "old-author"), List(3) { null })
        assertFalse(cache.ingest(before.take(2) + comment("edge", "new-author"), List(3) { null }))
    }
    @Test fun oneConfirmedAnchorCanExtendOnlyItsUniqueImmediateCommentNeighbour() {
        fun comment(text: String, key: String?) = b(text, key).copy(continuitySection = "reddit:comment")
        val cache = ReadingSessionCache()
        val body = b("original body", "original").copy(continuitySection = "reddit:original")
        cache.adopt(listOf(body, comment("short reply", null)), listOf(null, null))
        val id = cache.visibleIds.last()
        assertTrue(cache.ingest(listOf(body, comment("short reply", "author")), listOf(null, null)))
        assertEquals(id, cache.visibleIds.last())
        val isolated = ReadingSessionCache()
        isolated.adopt(listOf(body, comment("short reply", null)), listOf(null, null))
        assertFalse(isolated.ingest(listOf(comment("short reply", "author")), listOf(null)))
    }
    @Test fun repeatedOriginalCellsNeedDistinctSurroundingAnchors() {
        fun cell(text: String) = b(text).copy(continuitySection = "reddit:original")
        val before = listOf("left", "repeat", "middle", "repeat", "right").map(::cell)
        val cache = ReadingSessionCache(); cache.adopt(before, before.map { null })
        val ids = cache.visibleIds.toList()
        assertTrue(cache.ingest(before, before.map { null }))
        assertEquals(ids, cache.visibleIds)
        assertFalse(cache.ingest(listOf(cell("repeat")), listOf(null)))
        val ambiguous = listOf("left", "repeat", "repeat", "right").map(::cell)
        val other = ReadingSessionCache(); other.adopt(ambiguous, ambiguous.map { null })
        assertFalse(other.ingest(listOf("left", "repeat", "right").map(::cell), List(3) { null }))
    }
    @Test fun confirmedPromotedEntriesAndOwnedQuotesAreRemovedWithoutErasingNormalBody() {
        val cache = ReadingSessionCache()
        cache.adopt(listOf(b("normal"), b("ad", "promoted"),
            ReadingBlock("ad quote", 0, ReadingBlockKind.QUOTE, "quote", "promoted")), List(3) { null })
        val removed = cache.entries.drop(1).map { it.id }.toSet()
        cache.excludeKeys(setOf("promoted"))
        assertEquals(listOf("normal"), cache.entries.map { it.block.text })
        assertEquals(removed, cache.excludedIds)
        cache.clear()
        assertTrue(cache.excludedIds.isEmpty())
    }
    private fun b(text: String, key: String? = text) = ReadingBlock(text, 0, key = key)
    private fun put(cache: ReadingSessionCache, vararg text: String) = cache.ingest(text.map { b(it) }, text.map { "[0,0][100,100]" })
    @Test fun downUpAndBackDownPreserveOrderIdsAndShortBoundaryText() {
        val cache = ReadingSessionCache()
        put(cache, "a", "短句"); val ids = cache.visibleIds.toList()
        put(cache, "短句", "c"); val c = cache.visibleIds.last()
        assertEquals(listOf("a", "短句", "c"), cache.entries.map { it.block.text })
        assertEquals(ids.last(), cache.visibleIds.first())
        put(cache, "a", "短句")
        assertEquals(ids, cache.visibleIds); assertFalse(cache.atTail)
        assertEquals(ReadingSessionCache.Direction.BACKWARD, cache.direction)
        put(cache, "短句", "c")
        assertEquals(c, cache.visibleIds.last()); assertTrue(cache.atTail)
        assertEquals(3, cache.entries.size)
    }
    @Test fun prependNewEarlierContentDoesNotChangeExistingEntryIdentity() {
        val cache = ReadingSessionCache(); put(cache, "c", "d")
        val c = cache.visibleIds.first(); put(cache, "a", "b", "c")
        assertEquals(listOf("a", "b", "c", "d"), cache.entries.map { it.block.text })
        assertEquals(c, cache.visibleIds.last())
    }
    @Test fun uncertainIslandDoesNotReplaceConfirmedContentAndConflictingOrderIsRejected() {
        val cache = ReadingSessionCache(); put(cache, "a", "b", "c")
        val ids = cache.entries.map { it.id }
        assertFalse(put(cache, "x", "y"))
        assertEquals(ids, cache.entries.map { it.id }); assertEquals(2, cache.pending.size)
        assertFalse(put(cache, "c", "b"))
        assertEquals(ids, cache.entries.map { it.id })
        put(cache, "b", "c"); assertTrue(cache.pending.isEmpty())
    }
    @Test fun rejectedTransientViewDoesNotEraseLastConfirmedAppendBoundary() {
        val cache = ReadingSessionCache(); put(cache, "a", "b")
        assertFalse(put(cache, "unknown"))
        assertTrue(cache.ingest(listOf(b("c")), listOf(null), appendWithoutOverlap = true))
        assertEquals(listOf("a", "b", "c"), cache.entries.map { it.block.text })
    }
    @Test fun equalTextFromDifferentAuthorsRemainsSeparateAndMissingKeysNeedEvidence() {
        val cache = ReadingSessionCache()
        cache.ingest(listOf(b("same", "one"), b("same", "two")), listOf(null, null))
        assertEquals(2, cache.entries.size)
        assertFalse(cache.ingest(listOf(b("same", null)), listOf(null)))
        val id = cache.entries.last().id
        assertTrue(cache.ingest(listOf(b("same", null)), listOf(null), mapOf(0 to id)))
        assertEquals(id, cache.visibleIds.single())
        assertEquals("two", cache.entries.last().block.key)
    }
    @Test fun cacheIsBoundedAndProtectsActiveAndQueuedTail() {
        val cache = ReadingSessionCache(maxEntries = 3)
        put(cache, "a", "b"); val a = cache.visibleIds.first()!!
        cache.protect(setOf(a))
        put(cache, "b", "c"); put(cache, "c", "d"); put(cache, "d", "e")
        assertTrue(cache.entries.size <= 3)
        assertTrue(cache.entries.any { it.id == a })
        assertEquals("e", cache.entries.last().block.text)
    }
    @Test fun temporaryMissingIdentityDoesNotClearButDifferentPageDoes() {
        val cache = ReadingSessionCache()
        cache.observePage("page", "one"); put(cache, "a", "b")
        assertFalse(cache.observePage("page", null)); assertEquals(2, cache.entries.size)
        assertTrue(cache.observePage("page", "two")); assertTrue(cache.entries.isEmpty())
    }
    @Test fun savedCoordinatesAreLastObservationAndOffscreenEntriesAreNotVisible() {
        val cache = ReadingSessionCache(); put(cache, "a", "b")
        cache.ingest(listOf(b("b"), b("c")), listOf("[0,80][100,180]", "[0,180][100,280]"))
        assertFalse(cache.entries.first().visible)
        assertEquals("[0,0][100,100]", cache.entries.first().lastBounds)
        assertEquals("[0,80][100,180]", cache.entries[1].lastBounds)
    }
}
