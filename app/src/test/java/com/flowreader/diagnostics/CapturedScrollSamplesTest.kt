package com.flowreader.diagnostics

import java.util.Base64
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

internal object CapturedScrollSamples {
    data class Frame(val snapshot: ScreenSnapshot, val phase: String, val operation: String, val accepted: Boolean)
    fun read(name: String): List<Frame> {
        val frames = mutableListOf<Frame>()
        var header: List<String>? = null
        var nodes = mutableListOf<AccessibilityNodeSnapshot>()
        fun finish() {
            val h = header ?: return
            frames += Frame(ScreenSnapshot(h[1], "sample", h[2].toLong(), nodes.toList(), "captured", h[3].toInt(), h[4].toBoolean()), h[5], h[6], h[7].toBoolean())
        }
        GZIPInputStream(javaClass.getResourceAsStream("/captured/$name.tsv.gz")!!).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val f = line.split('\t')
                if (f[0] == "F") { finish(); header = f; nodes = mutableListOf() }
                else {
                    fun text(i: Int) = String(Base64.getDecoder().decode(f[i]), Charsets.UTF_8)
                    nodes += AccessibilityNodeSnapshot(f[1].toInt(), text(11), text(12), header!![1], f[8], f[9], f[10],
                        f[7].toBoolean(), f[4].toInt(), false, f[2].toIntOrNull(), f[3].toInt(),
                        isVisibleToUser = f[5].toBoolean(), isClickable = f[6].toBoolean())
                }
            }
        }
        finish(); return frames
    }
}

class CapturedScrollSamplesTest {
    private fun replay(name: String) {
        SnapshotStore.clear()
        val failures = mutableListOf<String>()
        try {
            CapturedScrollSamples.read(name).forEachIndexed { index, frame ->
                SnapshotStore.publish(frame.snapshot)
                if (frame.snapshot.isTraversalComplete && SnapshotStore.readingCache.pending.isNotEmpty()) {
                    failures += "$index:${frame.phase}:${frame.operation}"
                    // Diagnose independent later failures instead of cascading the first failure.
                    SnapshotStore.adoptReadingView()
                }
            }
            assertTrue("$name rejected=$failures", failures.isEmpty())
        } finally { SnapshotStore.clear() }
    }
    @Test fun filteredAdOnlyViewportStillHasVerifiedScrollTarget() {
        SnapshotStore.clear()
        try {
            val frames = CapturedScrollSamples.read("x-timeline")
            frames.take(55).forEach { SnapshotStore.publish(it.snapshot) }
            assertTrue(SnapshotStore.latestDisplay.readingBlocks.isEmpty())
            assertNotNull(com.flowreader.scroll.ScrollTargets.select(frames[54].snapshot, emptyList()))
            SnapshotStore.publish(frames[55].snapshot)
            assertTrue(SnapshotStore.readingCache.pending.isEmpty())
            assertTrue(SnapshotStore.latestDisplay.readingBlocks.any { it.text == "成堆的摄像头" })
        } finally { SnapshotStore.clear() }
    }
    @Test fun xTimeline() = replay("x-timeline")
    @Test fun xTimelineLong() = replay("x-timeline-long")
    @Test fun xProfilePosts() = replay("x-profile-posts")
    @Test fun xProfileReplies() = replay("x-profile-replies")
    @Test fun redditBottom() = replay("reddit-bottom")
}
