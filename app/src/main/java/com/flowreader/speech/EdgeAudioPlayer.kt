package com.flowreader.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Cancellation invalidates downloads, file writes, preparation and completion from older chunks. */
internal class EdgeAudioPlayer(private val context: Context, private val attributes: AudioAttributes) {
    private val main = Handler(Looper.getMainLooper())
    private val client = EdgeSpeechClient()
    private val files = Executors.newSingleThreadExecutor()
    private var generation = 0L
    private val preloader = EdgePreloader({ config, text, done ->
        client.synthesize(config, text) { result -> main.post { done(result) } }
    })
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var preparationTimeout: Runnable? = null
    fun diagnostics(): String = preloader.summary()
    fun prepare(text: String, following: List<String>, config: EdgeConfig) {
        preloader.load(config, text, following) { /* Cache only; playback waits for viewport positioning. */ }
    }
    fun updateFollowing(following: List<String>) { preloader.updateFollowing(following) }
    fun speak(text: String, following: List<String>, config: EdgeConfig, done: (String?) -> Unit) {
        stopPlayback()
        val request = generation
        fun prepare(bytes: ByteArray) {
            if (request != generation) return
            val timer = Runnable { if (request == generation) { stop(); done("Edge 音频准备超时") } }
            preparationTimeout = timer; main.postDelayed(timer, 10_000)
            files.execute {
                val audio = File(context.cacheDir, "flowreader-edge-${UUID.randomUUID()}.mp3")
                val written = runCatching { audio.writeBytes(bytes) }.isSuccess
                main.post {
                    if (request != generation) { audio.delete(); return@post }
                    if (!written) { audio.delete(); stop(); done("Edge 音频文件写入失败"); return@post }
                    file = audio
                    try {
                        val media = MediaPlayer()
                        player = media; media.setAudioAttributes(attributes); media.setDataSource(audio.absolutePath)
                        media.setOnPreparedListener {
                            if (request == generation) {
                                main.removeCallbacks(timer); preparationTimeout = null
                                it.start()
                            }
                        }
                        media.setOnCompletionListener { if (request == generation) { stopPlayback(); done(null) } }
                        media.setOnErrorListener { _, _, _ ->
                            if (request == generation) { stop(); done("Edge 音频播放失败") }; true
                        }
                        media.prepareAsync()
                    } catch (_: Exception) { stop(); done("Edge 音频准备失败") }
                }
            }
        }
        preloader.load(config, text, following) { result ->
                if (request != generation) return@load
                result.fold(onSuccess = { bytes ->
                    prepare(bytes)
                }, onFailure = { done(it.message ?: "Edge 合成失败") })
        }
    }
    private fun stopPlayback() {
        generation++
        preparationTimeout?.let(main::removeCallbacks); preparationTimeout = null
        player?.release(); player = null
        file?.delete(); file = null
    }
    fun pause() { stopPlayback(); preloader.pause() }
    fun stop() { stopPlayback(); preloader.reset() }
    fun close() { stop(); client.close(); files.shutdown() }
}
