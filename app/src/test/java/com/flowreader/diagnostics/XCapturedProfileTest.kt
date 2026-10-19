package com.flowreader.diagnostics

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class XCapturedProfileTest {
    @Test fun historicalProfileSummariesRemainReadableWhenNoTextViewsExposeBody() {
        val nodes = javaClass.getResourceAsStream("/x/profile-historical.tsv")!!.bufferedReader().useLines { lines ->
            lines.map { line ->
                val f = line.split('\t', limit = 12)
                fun text(index: Int) = String(Base64.getDecoder().decode(f[index]), Charsets.UTF_8)
                AccessibilityNodeSnapshot(f[0].toInt(), text(9), text(10), "com.twitter.android", f[6], f[7],
                    f[8], f[11].toBoolean(), f[3].toInt(), false,
                    f[1].toIntOrNull(), f[2].toInt(), isVisibleToUser = f[4].toBoolean(), isClickable = f[5].toBoolean())
            }.toList()
        }
        val snapshot = ScreenSnapshot("com.twitter.android", "test", 1, nodes, "captured profile", windowId = 1)
        SnapshotStore.clear()
        try {
            SnapshotStore.publish(snapshot)
            assertEquals(4, SnapshotStore.latestDisplay.readingBlocks.size)
        } finally { SnapshotStore.clear() }
        val blocks = XPostTextExtractor.readingBlocks(snapshot)
        assertEquals(4, blocks.size)
        assertTrue(blocks[0].text.contains("Starlink Mini is portable"))
        assertTrue(blocks[1].text.contains("*Alpaca not included"))
        assertTrue(blocks[2].text.contains("most rugged kit"))
        assertTrue(blocks[3].text.contains("270+ kph"))
        assertTrue(blocks.all { it.key != null && !it.text.contains("认证查看次数") && !it.text.contains("25年") })
    }
}
