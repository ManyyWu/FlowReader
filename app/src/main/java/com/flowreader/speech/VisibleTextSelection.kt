package com.flowreader.speech

/** Local matching hints may retain selection; equal body text alone never restores selection. */
class VisibleTextSelection {
    private var source: String? = null
    private var tappedIndex = -1
    private var tappedAt = Long.MIN_VALUE
    private var playbackSource: String? = null
    private var keys = emptyList<String?>()
    private var context: String? = null
    private var playbackKeys = emptyList<String?>()
    private var playbackTexts = emptyList<String>()
    private var playbackIndex = -1
    private var playbackContext: String? = null
    private var preferredKey: String? = null
    var blocks: List<String> = emptyList()
        private set
    var index = 0
        private set
    val text: String get() = blocks.getOrNull(index).orEmpty()

    fun update(packageName: String?, values: List<String>, matchKeys: List<String?> = List(values.size) { null },
        selectionContext: String? = null): Boolean {
        require(matchKeys.size == values.size)
        if (source == packageName && blocks == values && keys == matchKeys && context == selectionContext) return false
        val sameContext = source == packageName && context == selectionContext
        val selectedKey = if (sameContext) preferredKey else null
        val preserved = if (source == packageName && context == selectionContext && selectedKey != null)
            matchKeys.indices.filter { matchKeys[it] == selectedKey }.singleOrNull() else null
        source = packageName
        blocks = values.toList()
        keys = matchKeys.toList()
        context = selectionContext
        index = preserved ?: 0
        if (!sameContext || preferredKey == null)
            preferredKey = keys.getOrNull(index)?.takeIf { key -> keys.count { it == key } == 1 }
        tappedIndex = -1
        return true
    }

    fun select(value: Int): Boolean {
        if (value !in blocks.indices) return false
        preferredKey = keys.getOrNull(value)?.takeIf { key -> keys.count { it == key } == 1 }
        if (value == index) return false
        index = value
        return true
    }

    fun tap(value: Int, now: Long, doubleTapMillis: Long): Boolean {
        if (value !in blocks.indices) return false
        val doubleTap = tappedIndex == value && now >= tappedAt && now - tappedAt <= doubleTapMillis
        select(value)
        tappedIndex = if (doubleTap) -1 else value
        tappedAt = now
        return doubleTap
    }

    fun started() {
        preferredKey = keys.getOrNull(index)?.takeIf { key -> keys.count { it == key } == 1 }
        playbackTexts = blocks.toList(); playbackKeys = keys.toList(); playbackIndex = index
        playbackSource = source; playbackContext = context
    }
    fun advancePlaybackTo(value: Int?) { if (value != null && value in playbackTexts.indices) playbackIndex = value }
    fun stopped() {
        playbackTexts = emptyList(); playbackKeys = emptyList(); playbackIndex = -1
        playbackSource = null; playbackContext = null
    }
    fun highlightedIndex(playing: Boolean): Int? {
        if (!playing) return null
        if (source != playbackSource || playbackIndex !in playbackTexts.indices) return null
        if (context != playbackContext) return null
        val playbackText = playbackTexts[playbackIndex]
        val playbackKey = playbackKeys.getOrNull(playbackIndex)
        if (playbackKey != null) return keys.indices.filter {
            keys[it] == playbackKey && blocks[it] == playbackText
        }.singleOrNull()
        // Equal text is only a local display hint; duplicate candidates get no highlight.
        val candidate = blocks.indices.filter { blocks[it] == playbackText }.singleOrNull() ?: return null
        if (keys[candidate] != null || playbackTexts.count { it == playbackText } != 1) return null
        return candidate
    }
}
