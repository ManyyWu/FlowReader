package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EdgeSpeechClientTest {
    @Test fun invalidConfigurationFailsWithoutSendingText() {
        val client = EdgeSpeechClient()
        try {
            var error: Throwable? = null
            client.synthesize(EdgeConfig("http://invalid", "token", "zh-CN-XiaoxiaoNeural", 1.5f), "fixed sample") {
                error = it.exceptionOrNull()
            }
            assertNotNull(error)
            assertFalse(error!!.message.orEmpty().contains("token"))
        } finally { client.close() }
    }
    @Test fun liveDefaultEndpointReturnsMp3WhenExplicitlyEnabled() {
        assumeTrue(System.getenv("FLOWREADER_EDGE_NETWORK_TEST") == "1")
        val client = EdgeSpeechClient()
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<Result<ByteArray>>()
        try {
            client.synthesize(EdgeConfig(EdgeProtocol.DEFAULT_URL, EdgeProtocol.DEFAULT_TOKEN,
                "zh-CN-XiaoxiaoNeural", 1.5f), "你好，这是 FlowReader 连接测试。") {
                outcome.set(it); completed.countDown()
            }
            assertTrue("synthesis must finish within its timeout", completed.await(35, TimeUnit.SECONDS))
            assertTrue(outcome.get().exceptionOrNull()?.message, outcome.get().isSuccess)
            assertTrue(outcome.get().getOrThrow().size > 1000)
        } finally { client.close() }
    }
}
