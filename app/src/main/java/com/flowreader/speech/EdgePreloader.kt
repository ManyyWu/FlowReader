package com.flowreader.speech

/** All methods and completion callbacks run on the same owner thread. One synthesis at a time. */
internal class EdgePreloader(
    private val synthesize: (EdgeConfig, String, (Result<ByteArray>) -> Unit) -> (() -> Unit),
    private val maxBytes: Int = 16 * 1024 * 1024
) {
    private data class Key(val config: EdgeConfig, val text: String)
    private class Task(val key: Key, val epoch: Long) { var cancel: (() -> Unit)? = null }
    private var epoch = 0L
    private var task: Task? = null
    private var current: Key? = null
    private var next = emptyList<Key>()
    var requestCount = 0L
        private set
    var cacheHits = 0L
        private set
    val cachedBytes: Long get() = cache.values.sumOf { it.size.toLong() }
    val inFlight: Boolean get() = task != null
    fun summary(): String = "Edge预加载：请求=$requestCount，缓存命中=$cacheHits，缓存=${cachedBytes / 1024}KB，合成中=$inFlight"
    private var callback: ((Result<ByteArray>) -> Unit)? = null
    private var paused = false
    private val cache = linkedMapOf<Key, ByteArray>()
    private val failures = mutableMapOf<Key, Throwable>()
    private val attempts = mutableMapOf<Key, Int>()
    fun load(config: EdgeConfig, text: String, following: String?, ready: (Result<ByteArray>) -> Unit) {
        load(config, text, listOfNotNull(following), ready)
    }
    fun load(config: EdgeConfig, text: String, following: List<String>, ready: (Result<ByteArray>) -> Unit) {
        paused = false
        current = Key(config, text)
        next = following.take(2).map { Key(config, it) }.distinct().filter { it != current }
        if (cache.containsKey(current)) cacheHits++
        callback = ready
        // A new selected text must never wait behind unrelated speculative synthesis.
        if (task?.key != current && task?.key !in next) {
            val old = task; task = null; old?.cancel?.invoke()
        }
        trim()
        pump()
    }
    fun pause() { paused = true; callback = null }
    fun updateFollowing(following: List<String>) {
        val key = current ?: return
        next = following.take(2).map { Key(key.config, it) }.distinct().filter { it != current }
        val old = task
        if (old != null && old.key != current && old.key !in next) { task = null; old.cancel?.invoke() }
        trim(); pump()
    }
    fun reset() {
        epoch++; val old = task; task = null; old?.cancel?.invoke()
        current = null; next = emptyList(); callback = null; paused = false
        cache.clear(); failures.clear(); attempts.clear()
    }
    private fun trim() {
        val window = next.toSet() + listOfNotNull(current)
        cache.keys.retainAll(window)
        failures.keys.retainAll(window)
        attempts.keys.retainAll(window)
        while (cache.values.sumOf { it.size.toLong() } > maxBytes) {
            val key = next.asReversed().firstOrNull { cache.containsKey(it) } ?: break
            cache.remove(key)
            failures[key] = IllegalStateException("预加载缓存空间不足")
        }
    }
    private fun pump() {
        if (paused) return
        val wanted = current ?: return
        val notify = callback
        if (notify != null) {
            cache[wanted]?.let { callback = null; notify(Result.success(it)); pump(); return }
            val failure = failures[wanted]
            if (failure != null && (attempts[wanted] ?: 0) >= 2) {
                callback = null; notify(Result.failure(failure)); return
            }
        }
        if (task != null) return
        val key = when {
            callback != null && !cache.containsKey(wanted) -> wanted
            else -> next.firstOrNull { !cache.containsKey(it) && !failures.containsKey(it) } ?: return
        }
        val request = Task(key, epoch)
        task = request; requestCount++; attempts[key] = (attempts[key] ?: 0) + 1
        request.cancel = synthesize(key.config, key.text) { result ->
            if (task !== request || epoch != request.epoch) return@synthesize
            task = null
            result.fold({ bytes ->
                if (bytes.size <= maxBytes) { cache[key] = bytes; failures.remove(key); trim() }
                else failures[key] = IllegalStateException("Edge 音频超过缓存上限")
            }, { failures[key] = it })
            pump()
        }
    }
}
