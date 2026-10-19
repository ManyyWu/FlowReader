package com.flowreader.speech

/** Invalidates callbacks from stopped/replaced sessions and already completed chunks. */
internal class SpeechSequence {
    private var generation = 0L
    private var chunks = emptyList<String>()
    var index = 0
        private set
    val size: Int get() = chunks.size
    val active: Boolean get() = index < size
    val id: String? get() = if (active) "flowreader-$generation-$index" else null
    val text: String get() = chunks[index]
    val followingText: String? get() = chunks.getOrNull(index + 1)
    fun followingTexts(count: Int): List<String> = chunks.drop(index + 1).take(count.coerceIn(0, 2))

    fun start(values: List<String>) {
        stop()
        chunks = values.toList()
    }
    fun append(values: List<String>): Boolean {
        if (!active) return false
        chunks = chunks + values
        return true
    }
    /** Future chunks only: the active utterance ID, cursor and text stay unchanged. */
    fun replaceTail(from: Int, values: List<String>): Boolean {
        if (!active || from <= index || from > chunks.size) return false
        chunks = chunks.take(from) + values
        return true
    }
    fun stop() { generation++; chunks = emptyList(); index = 0 }
    fun restartCurrent() { generation++ }
    fun advance(completedId: String?): Boolean {
        if (!active || completedId != id) return false
        index++
        return true
    }
}
