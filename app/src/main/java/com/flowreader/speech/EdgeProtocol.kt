package com.flowreader.speech

import java.security.MessageDigest
import kotlin.math.roundToInt

object EdgeProtocol {
    const val DEFAULT_URL = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1"
    const val DEFAULT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val VERSION = "143.0.3650.75"
    fun gec(token: String, unixSeconds: Long): String {
        val seconds = unixSeconds + 11644473600L
        val ticks = (seconds - seconds % 300) * 10_000_000L
        return MessageDigest.getInstance("SHA-256").digest("$ticks$token".toByteArray())
            .joinToString("") { "%02X".format(it.toInt() and 255) }
    }
    private fun xml(text: String): String = text.filter { it >= ' ' || it in "\n\r\t" }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
    fun ssml(text: String, voice: String, rate: Float): String {
        require(voice.matches(Regex("[A-Za-z0-9-]{1,120}"))) { "音色名称无效" }
        val percent = ((rate.coerceIn(0.5f, 3f) - 1f) * 100).roundToInt()
        val speed = if (percent >= 0) "+$percent%" else "$percent%"
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${voice.take(5)}'>" +
            "<voice name='${xml(voice)}'><prosody rate='$speed'>${xml(text)}</prosody></voice></speak>"
    }
    fun audioPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val size = ((frame[0].toInt() and 255) shl 8) or (frame[1].toInt() and 255)
        if (size > frame.size - 2) return null
        val header = String(frame, 2, size, Charsets.UTF_8)
        if (!header.lineSequence().any { it.trim().equals("Path:audio", true) || it.trim().equals("Path: audio", true) })
            return null
        return frame.copyOfRange(size + 2, frame.size)
    }
}
