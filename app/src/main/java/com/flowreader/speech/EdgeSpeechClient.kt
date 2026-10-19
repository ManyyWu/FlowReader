package com.flowreader.speech

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicBoolean

data class EdgeConfig(val url: String, val token: String, val voice: String, val rate: Float)

/** One bounded synthesis per socket. Tokens/URLs/text never enter logs or error messages. */
class EdgeSpeechClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    fun synthesize(config: EdgeConfig, text: String, result: (Result<ByteArray>) -> Unit): () -> Unit {
        val settled = AtomicBoolean(false)
        var socket: WebSocket? = null
        var timeoutTask: ScheduledFuture<*>? = null
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                socket?.cancel(); result(Result.failure(Exception("Edge 合成超时（30秒）")))
            }
        }
        fun finish(value: Result<ByteArray>) {
            if (settled.compareAndSet(false, true)) {
                timeoutTask?.cancel(false); socket?.close(1000, null); result(value)
            }
        }
        try {
            require(config.url.startsWith("wss://")) { "接口必须以 wss:// 开头" }
            require(config.token.isNotBlank()) { "Token 不能为空" }
            val ssml = EdgeProtocol.ssml(text, config.voice, config.rate)
            val requestId = UUID.randomUUID().toString().replace("-", "")
            val url = config.url.replaceFirst("wss://", "https://").toHttpUrl().newBuilder()
                .setQueryParameter("ConnectionId", requestId)
                .setQueryParameter("Ocp-Apim-Subscription-Key", config.token)
                .setQueryParameter("Sec-MS-GEC", EdgeGecV2.generate(config.token, System.currentTimeMillis() / 1000))
                .setQueryParameter("Sec-MS-GEC-Version", "2-${EdgeProtocol.VERSION}").build()
            val request = Request.Builder().url(url)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
                .header("Accept-Language", "en-US,en;q=0.9").header("Cache-Control", "no-cache").build()
            val output = ByteArrayOutputStream()
            timeoutTask = timers.schedule(timeout, 30, TimeUnit.SECONDS)
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (settled.get()) { webSocket.cancel(); return }
                    val stamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    val configBody = """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":false},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    val a = webSocket.send("X-Timestamp:$stamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n$configBody")
                    val b = webSocket.send("X-RequestId:$requestId\r\nX-Timestamp:$stamp\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml")
                    if (!a || !b) finish(Result.failure(Exception("Edge 请求发送失败")))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (settled.get()) return
                    val payload = EdgeProtocol.audioPayload(bytes.toByteArray()) ?: return
                    if (output.size() + payload.size > 8 * 1024 * 1024) {
                        finish(Result.failure(Exception("Edge 音频超过大小上限"))); return
                    }
                    output.write(payload)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.lineSequence().any { it.trim().equals("Path:turn.end", true) || it.trim().equals("Path: turn.end", true) }) {
                        if (output.size() == 0) finish(Result.failure(Exception("Edge 未返回音频")))
                        else finish(Result.success(output.toByteArray()))
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(Result.failure(Exception(response?.let { "Edge 连接失败：HTTP ${it.code}" } ?: "Edge 网络连接失败")))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    finish(Result.failure(Exception("Edge 连接提前关闭（$code）"))); webSocket.close(code, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(Result.failure(Exception("Edge 连接已关闭（$code）")))
                }
            })
        } catch (e: IllegalArgumentException) {
            finish(Result.failure(Exception(if (config.url.startsWith("wss://")) "Edge 配置无效，请检查接口、Token 和音色" else "接口必须以 wss:// 开头")))
        }
        return {
            settled.set(true); timeoutTask?.cancel(false); socket?.cancel()
        }
    }
    fun close() { timers.shutdownNow(); client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
