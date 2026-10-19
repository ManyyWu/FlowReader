package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class EdgePreloaderTest {
    @Test fun positioningPreparationSurvivesQueueRefreshAndPlaybackSharesItsRequest() {
        val f = Fixture(); var playback = false
        f.loader.load(config, "a", listOf("b")) { }
        f.finish(0) // a is playing, b is being prefetched
        f.loader.load(config, "b", listOf("c")) { } // prepare b before positioning
        f.loader.updateFollowing(listOf("c", "d")) // refresh during positioning
        assertFalse(f.requests[1].cancelled)
        f.loader.load(config, "b", listOf("c", "d")) { playback = it.isSuccess }
        assertFalse(playback)
        f.finish(1)
        assertTrue(playback)
        assertEquals(listOf("a", "b", "c"), f.requests.map { it.text })
    }
    @Test fun completedPositioningPreparationIsCachedUntilPlayback() {
        val f = Fixture(); var playback = false
        f.loader.load(config, "a", listOf("b")) { }
        f.finish(0)
        f.loader.load(config, "a", listOf("b")) { playback = it.isSuccess }
        assertTrue(playback)
        assertEquals(listOf("a", "b"), f.requests.map { it.text })
    }

    @Test fun newlyAppendedTextStartsPrefetchWithoutRestartingCurrentAudioRequest() {
        val f = Fixture(); var currentReady = 0
        f.loader.load(config, "a", emptyList()) { currentReady++ }
        f.finish(0)
        f.loader.updateFollowing(listOf("b", "c"))
        f.loader.updateFollowing(listOf("b", "c"))
        assertEquals(listOf("a", "b"), f.requests.map { it.text })
        assertEquals(1, currentReady)
        f.finish(1); assertEquals(listOf("a", "b", "c"), f.requests.map { it.text })
    }
    private val config = EdgeConfig("wss://example.test/tts", "test", "zh-CN-YunjianNeural", 1.5f)
    private class Pending(val text: String, val done: (Result<ByteArray>) -> Unit) { var cancelled = false }
    private class Fixture(limit: Int = 16 * 1024 * 1024) {
        val requests = mutableListOf<Pending>()
        val loader = EdgePreloader({ _, text, done ->
            val p = Pending(text, done); requests += p
            val cancel: () -> Unit = { p.cancelled = true }; cancel
        }, limit)
        fun finish(index: Int, bytes: Int = 3) { requests[index].done(Result.success(ByteArray(bytes))) }
        fun fail(index: Int) { requests[index].done(Result.failure(Exception("network"))) }
    }
    @Test fun duplicateDemandSharesRequestAndPrefetchIsPromotedWithoutNewRequest() {
        val f = Fixture(); var result = 0
        f.loader.load(config, "a", "b") { result++ }
        f.loader.load(config, "a", "b") { result++ }
        assertEquals(listOf("a"), f.requests.map { it.text })
        f.finish(0)
        assertEquals(1, result)
        assertEquals(listOf("a", "b"), f.requests.map { it.text })
        f.loader.load(config, "b", "c") { result++ }
        assertEquals(2, f.requests.size)
        f.finish(1)
        assertEquals(2, result)
        assertEquals(listOf("a", "b", "c"), f.requests.map { it.text })
    }
    @Test fun pauseLetsCurrentFinishWithoutPlaybackOrFurtherPrefetchAndResumeUsesCache() {
        val f = Fixture(); var called = false
        f.loader.load(config, "a", "b") { called = true }
        f.loader.pause(); f.finish(0)
        assertFalse(called); assertEquals(1, f.requests.size)
        f.loader.load(config, "a", "b") { called = true }
        assertTrue(called); assertEquals(listOf("a", "b"), f.requests.map { it.text })
    }
    @Test fun resetAndNewSelectionRejectLateResults() {
        val f = Fixture(); var old = false; var fresh = false
        f.loader.load(config, "a", "b") { old = true }
        f.loader.reset()
        assertTrue(f.requests[0].cancelled)
        f.loader.load(config, "c", null) { fresh = true }
        f.finish(0); assertFalse(old); assertFalse(fresh)
        f.finish(1); assertTrue(fresh)
    }
    @Test fun failedPrefetchDoesNotInterruptAndRetriesOnlyOnceWhenNeeded() {
        val f = Fixture(); var first = false; var failed = false
        f.loader.load(config, "a", "b") { first = true }
        f.finish(0); f.fail(1)
        assertTrue(first); assertEquals(2, f.requests.size)
        f.loader.load(config, "b", null) { failed = it.isFailure }
        assertEquals(3, f.requests.size)
        f.fail(2); assertTrue(failed); assertEquals(3, f.requests.size)
        f.loader.load(config, "b", null) { failed = it.isFailure }
        assertEquals(3, f.requests.size)
    }
    @Test fun configChangeDoesNotReuseOldVoiceAndIrrelevantTaskIsCancelled() {
        val f = Fixture(); var stale = false
        f.loader.load(config, "a", "b") { stale = true }
        f.loader.load(config.copy(voice = "zh-CN-XiaoxiaoNeural"), "a", null) {}
        assertTrue(f.requests[0].cancelled)
        f.finish(0); assertFalse(stale)
        assertEquals(2, f.requests.size)
    }
    @Test fun cacheLimitDoesNotTriggerEndlessSpeculativeDownloads() {
        val f = Fixture(4)
        f.loader.load(config, "a", "b") {}
        f.finish(0); f.finish(1)
        assertEquals(2, f.requests.size)
        var ready = false
        f.loader.load(config, "b", null) { ready = it.isSuccess }
        f.finish(2); assertTrue(ready)
        assertEquals(3, f.requests.size)
    }
    @Test fun disabledPrefetchOnlyLoadsDemandedChunk() {
        val f = Fixture()
        f.loader.load(config, "a", emptyList()) {}
        f.finish(0)
        assertEquals(listOf("a"), f.requests.map { it.text })
        assertEquals(0L, f.loader.cacheHits)
        f.loader.load(config, "a", emptyList()) {}
        assertEquals(1L, f.loader.cacheHits)
        assertEquals(1L, f.loader.requestCount)
    }
    @Test fun twoChunksPrefetchInOrderAndNearestSurvivesMemoryPressure() {
        val f = Fixture(6)
        f.loader.load(config, "a", listOf("b", "c")) {}
        f.finish(0); assertEquals(listOf("a", "b"), f.requests.map { it.text })
        f.finish(1); assertEquals(listOf("a", "b", "c"), f.requests.map { it.text })
        f.finish(2)
        assertEquals(6L, f.loader.cachedBytes)
        var ready = false
        f.loader.load(config, "b", emptyList()) { ready = it.isSuccess }
        assertTrue(ready); assertEquals(3, f.requests.size)
        assertEquals(1L, f.loader.cacheHits)
    }
    @Test fun repeatedFollowingChunksAreSynthesizedOnlyOnceAndDepthIsBounded() {
        val f = Fixture()
        f.loader.load(config, "a", listOf("b", "b", "c")) {}
        f.finish(0); f.finish(1)
        assertEquals(listOf("a", "b"), f.requests.map { it.text })
        assertFalse(f.loader.inFlight)
    }
}
