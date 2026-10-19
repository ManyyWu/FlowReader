package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class EdgeProtocolTest {
    @Test fun v2SignatureMatchesUpstreamIndependentVector() {
        assertEquals("2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeGecV2.generate(EdgeProtocol.DEFAULT_TOKEN, 1700000000))
        assertEquals(EdgeGecV2.generate(EdgeProtocol.DEFAULT_TOKEN, 1700000000),
            EdgeGecV2.generate(EdgeProtocol.DEFAULT_TOKEN, 1700000001))
        assertNotEquals(EdgeGecV2.generate("test-token", 1700000000),
            EdgeGecV2.generate(EdgeProtocol.DEFAULT_TOKEN, 1700000000))
    }
    @Test fun gecMatchesKnownHashAndFiveMinuteWindow() {
        assertEquals("059DDF878CFDDD1942932AC3EA8A81AEB02B82189E1D4D685076FB4A94313A7D",
            EdgeProtocol.gec("test-token", 1700000000))
        assertEquals(EdgeProtocol.gec("test-token", 1700000000), EdgeProtocol.gec("test-token", 1700000001))
        assertNotEquals(EdgeProtocol.gec("test-token", 1700000000), EdgeProtocol.gec("different-token", 1700000000))
    }
    @Test fun ssmlEscapesTextAndMapsSpeed() {
        val ssml = EdgeProtocol.ssml("a<&\"'\u0001", "zh-CN-XiaoxiaoNeural", 1.5f)
        assertTrue(ssml.contains("rate='+50%'"))
        assertTrue(ssml.contains("a&lt;&amp;&quot;&apos;"))
        assertFalse(ssml.contains('\u0001'))
        assertTrue(EdgeProtocol.ssml("hello", "en-US-JennyNeural", 0.5f).contains("rate='-50%'"))
    }
    @Test(expected = IllegalArgumentException::class) fun voiceCannotInjectMarkup() {
        EdgeProtocol.ssml("text", "voice'><extra>", 1f)
    }
    @Test fun binaryAudioIsSeparatedFromHeaders() {
        val header = "Content-Type:audio/mpeg\r\nPath:audio\r\n".toByteArray()
        val frame = byteArrayOf((header.size shr 8).toByte(), header.size.toByte()) + header + byteArrayOf(1, 2, 3)
        assertArrayEquals(byteArrayOf(1, 2, 3), EdgeProtocol.audioPayload(frame))
        assertNull(EdgeProtocol.audioPayload(byteArrayOf(0, 40, 1)))
        assertNull(EdgeProtocol.audioPayload(byteArrayOf(0)))
        assertNull(EdgeProtocol.audioPayload(byteArrayOf(0, 4) + "test".toByteArray()))
    }
}
