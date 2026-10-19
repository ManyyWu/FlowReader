package com.flowreader.speech

import android.app.Service
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Main-thread state; engine callbacks are posted back and checked against the active utterance. */
class TtsSpeaker(context: Service, private val onChanged: () -> Unit,
    private val onBlockCompleted: (Int) -> Boolean = { true },
    private val onQueueCompleted: () -> Unit = {},
    private val keepAudioBetweenQueues: () -> Boolean = { false },
    private val onAudioInterrupted: (String) -> Unit = {},
    private val beforeParagraphStart: (Int) -> Boolean = { true }) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = com.flowreader.settings.AppSettings(context)
    fun refreshSpeechRate() {
        if (systemReady && !closed) engine.setSpeechRate(settings.speechRate)
        if ((playing || paused) && (simulatedSession != settings.simulatedPlayback ||
                (simulatedSession && simulationDelay != settings.simulationDelayMs) ||
                (!simulatedSession && (edgeSession != settings.useEdge ||
                    (edgeSession && sessionConfig != edgeConfig()))))) stop("播放配置已切换，请重新播放")
    }
    private var closed = false
    private var systemReady = false
    val ready: Boolean get() = !closed && (settings.simulatedPlayback || settings.useEdge || systemReady)
    private var simulatedSession = false
    private var simulationDelay = 500
    private val simulation = SimulationTimer { delay, completed ->
        val task = Runnable { completed() }
        main.postDelayed(task, delay)
        val cancel: () -> Unit = { main.removeCallbacks(task) }; cancel
    }
    private var edgeSession = false
    private var sessionConfig: EdgeConfig? = null
    private var preparedUtterance: String? = null
    private var prefetchDepth = 1
    private val events = ArrayDeque<String>()
    private fun record(message: String) {
        if (events.size >= 32) events.removeFirst()
        events.addLast("${android.os.SystemClock.uptimeMillis()} $message")
    }
    fun diagnostics(): String = "持有音频焦点=${audioSession.held}\n模拟播放=$simulatedSession，间隔=${simulationDelay}ms\n${edge.diagnostics()}\n播放事件（最近32条）：\n${events.joinToString("\n")}"
    private fun edgeConfig() = EdgeConfig(settings.edgeUrl, settings.edgeToken, settings.edgeVoice, settings.speechRate)
    var playing = false
        private set
    var paused = false
        private set
    var status = "语音引擎初始化中"
        private set
    private val sequence = SpeechSequence()
    private var chunkBlocks = emptyList<Int>()
    val currentBlockIndex: Int? get() = chunkBlocks.getOrNull(sequence.index)
    private val audioSession = PlaybackAudioSession(context) { message ->
        if (!closed) { stop(message); onAudioInterrupted(message) }
    }
    private val edge = EdgeAudioPlayer(context, audioSession.attributes)
    private val engine = TextToSpeech(context.applicationContext) { result ->
        main.post { initialized(result) }
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post {
                    chunkCompleted(utteranceId)
                }
            }
            @Deprecated("Legacy engine error callback")
            override fun onError(utteranceId: String?) { failed(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { failed(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                main.post {
                    if (!closed && playing && utteranceId == sequence.id) stop("朗读已中断")
                }
            }
        })
    }

    private fun initialized(result: Int) {
        if (closed) return
        val readyMessage = if (result != TextToSpeech.SUCCESS) {
             if (settings.useEdge) "Edge 在线语音已就绪" else "语音引擎初始化失败，请检查系统文字转语音设置"
        } else {
            engine.setSpeechRate(settings.speechRate)
            val language = engine.setLanguage(Locale.getDefault())
            systemReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
            engine.setAudioAttributes(audioSession.attributes)
            if (settings.useEdge) "Edge 在线语音已就绪" else if (systemReady) "准备就绪" else "系统语音引擎缺少当前语言，请检查文字转语音设置"
        }
        if (!playing && !paused) status = if (settings.simulatedPlayback) "模拟播放已就绪" else readyMessage
        onChanged()
    }

    fun play(text: String) {
        playBlocks(listOf(text), 0)
    }
    fun appendBlocks(blocks: List<String>, firstBlockIndex: Int): Boolean {
        if (closed || (!playing && !paused) || !sequence.active) return false
        val chunks = mutableListOf<String>(); val owners = mutableListOf<Int>()
        blocks.forEachIndexed { offset, text ->
            SpeechChunks.split(text, chunkLimit(text, edgeSession, simulatedSession)).forEach {
                chunks += it; owners += firstBlockIndex + offset
            }
        }
        if (!sequence.append(chunks)) return false
        record("追加 ${chunks.size}分段，首条=$firstBlockIndex")
        chunkBlocks = chunkBlocks + owners
        if (edgeSession) edge.updateFollowing(sequence.followingTexts(prefetchDepth))
        return true
    }

    /** Preserve every remaining chunk of the current paragraph; rebuild only later paragraphs. */
    fun replaceFollowingBlocks(blocks: List<String>, firstBlockIndex: Int): Boolean {
        val current = currentBlockIndex ?: return false
        if (closed || (!playing && !paused) || !sequence.active || firstBlockIndex != current + 1) return false
        val from = ((sequence.index + 1) until chunkBlocks.size)
            .firstOrNull { chunkBlocks[it] != current } ?: chunkBlocks.size
        val chunks = mutableListOf<String>(); val owners = mutableListOf<Int>()
        blocks.forEachIndexed { offset, text ->
            SpeechChunks.split(text, chunkLimit(text, edgeSession, simulatedSession)).forEach {
                chunks += it; owners += firstBlockIndex + offset
            }
        }
        if (!sequence.replaceTail(from, chunks)) return false
        chunkBlocks = chunkBlocks.take(from) + owners
        record("更新未读队列 ${blocks.size}条/${chunks.size}分段，首条=$firstBlockIndex")
        if (edgeSession) edge.updateFollowing(sequence.followingTexts(prefetchDepth))
        return true
    }

    /** Speak from the selected visible block through the remaining blocks in order. */
    fun playBlocks(blocks: List<String>, startIndex: Int) {
        if (!ready || closed) return
        val simulate = settings.simulatedPlayback
        val useEdge = settings.useEdge && !simulate
        val config = if (useEdge) edgeConfig() else null
        val chunks = mutableListOf<String>()
        val owners = mutableListOf<Int>()
        val firstBlock = startIndex.coerceIn(0, blocks.size)
        blocks.drop(firstBlock).forEachIndexed { offset, block ->
            val index = firstBlock + offset
            SpeechChunks.split(block, chunkLimit(block, useEdge, simulate)).forEach {
                chunks += it; owners += index
            }
        }
        val reuse = useEdge && edgeSession && sessionConfig == config && sequence.active &&
            chunks.firstOrNull() == sequence.text
        stopInternal("已停止", preserveEdge = reuse, preserveAudio = !simulate)
        simulatedSession = simulate; simulationDelay = settings.simulationDelayMs
        edgeSession = useEdge; sessionConfig = config
        prefetchDepth = settings.prefetchDepth
        chunkBlocks = owners
        sequence.start(chunks)
        record("新队列 ${blocks.size}条/${chunks.size}分段，起点=$startIndex")
        if (!sequence.active) { audioSession.release(); status = "没有可朗读文本"; onChanged(); return }
        if (!simulatedSession && !audioSession.acquire()) {
            stop("无法取得音频焦点或启动朗读通知，请返回 FlowReader 后重试")
            return
        }
        playing = true
        paused = false
        speakNext()
    }

    /** Stops the active utterance and keeps the current chunk for replay on resume. */
    fun pause() {
        if (!playing || closed) return
        playing = false
        paused = true
        record("暂停 block=$currentBlockIndex")
        simulation.cancel()
        engine.stop()
        edge.pause()
        status = if (simulatedSession) "模拟播放已暂停；继续时重新计时当前条" else "已暂停；继续时重读当前分段"
        onChanged()
    }

    fun resume() {
        if (!paused || !ready || closed) return
        if (!simulatedSession && !audioSession.acquire()) { stop("无法恢复朗读：无法取得音频焦点或启动朗读通知"); return }
        record("继续 block=$currentBlockIndex")
        sequence.restartCurrent()
        paused = false
        playing = true
        speakNext()
    }

    private fun speakNext() {
        val id = sequence.id ?: return
        if (edgeSession && !simulatedSession && preparedUtterance != id) {
            preparedUtterance = id
            edge.prepare(sequence.text, sequence.followingTexts(prefetchDepth), sessionConfig ?: edgeConfig())
            record("准备语音 $id")
        }
        val paragraph = currentBlockIndex
        val paragraphStart = sequence.index == 0 || chunkBlocks.getOrNull(sequence.index - 1) != paragraph
        if (paragraph != null && paragraphStart && !beforeParagraphStart(paragraph)) {
            if (status != "等待当前正文定位…") { status = "等待当前正文定位…"; onChanged() }
            main.postDelayed({ if (!closed && playing && id == sequence.id) speakNext() }, 100)
            return
        }
        record("开始 $id block=$currentBlockIndex textHash=${sequence.text.hashCode()}")
        if (simulatedSession) {
            status = "模拟播放 ${sequence.index + 1}/${sequence.size} · ${simulationDelay}ms/条"
            onChanged()
            if (playing && id == sequence.id) simulation.start(simulationDelay) { chunkCompleted(id) }
            return
        }
        status = "正在朗读 ${sequence.index + 1}/${sequence.size}"
        onChanged()
        if (edgeSession) {
            status = "Edge 合成/朗读 ${sequence.index + 1}/${sequence.size}"
            onChanged()
            edge.speak(sequence.text, sequence.followingTexts(prefetchDepth), sessionConfig ?: edgeConfig()) { error ->
                if (!closed && playing && id == sequence.id) {
                    if (error == null) chunkCompleted(id) else stop(error)
                }
            }
            return
        }
        if (engine.speak(sequence.text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
            stop("语音播放失败")
        }
    }

    private fun chunkCompleted(id: String?) {
        if (closed || !playing || !sequence.advance(id)) return
        record("完成 $id")
        val completed = chunkBlocks.getOrNull(sequence.index - 1)
        val next = chunkBlocks.getOrNull(sequence.index)
        if (completed != null && completed != next && !onBlockCompleted(completed)) {
            stop("已滚动，切换到最新位置"); return
        }
        if (!sequence.active) {
            stopInternal("朗读完成", preserveEdge = false, preserveAudio = keepAudioBetweenQueues())
            onQueueCompleted()
        } else speakNext()
    }

    private fun failed(id: String?) {
        main.post { if (!closed && playing && id == sequence.id) stop("语音播放失败") }
    }

    fun stop(message: String = "已停止") {
        stopInternal(message, preserveEdge = false)
    }

    fun releaseIdleAudioFocus() { if (!playing && !paused) audioSession.release() }

    private fun stopInternal(message: String, preserveEdge: Boolean, preserveAudio: Boolean = false) {
        record("停止 $message block=$currentBlockIndex")
        simulation.cancel()
        sequence.stop()
        chunkBlocks = emptyList()
        playing = false
        paused = false
        if (!preserveAudio) audioSession.release()
        if (preserveEdge) edge.pause() else edge.stop()
        sessionConfig = null
        if (!closed) engine.stop()
        if (ready) status = message
        onChanged()
    }

    fun close() {
        if (closed) return
        stop()
        closed = true
        systemReady = false
        edge.close()
        engine.shutdown()
    }
    private fun chunkLimit(text: String, edge: Boolean, simulated: Boolean): Int =
        if (simulated) maxOf(2, text.length) else if (edge) 1500 else TextToSpeech.getMaxSpeechInputLength() - 1
}
