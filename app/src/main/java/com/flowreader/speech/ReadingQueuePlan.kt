package com.flowreader.speech

import com.flowreader.diagnostics.ReadingSessionCache.Entry

/** Keeps the spoken prefix immutable and merges newly observed entries into the unread suffix. */
internal object ReadingQueuePlan {
    fun merge(queued: List<Entry>, cached: List<Entry>, current: Int, limit: Int = 200, excluded: Set<Long> = emptySet()): List<Entry> {
        val active = queued.getOrNull(current) ?: return queued
        val anchor = cached.indexOfFirst { it.id == active.id }
        if (anchor < 0) return queued.filterIndexed { index, entry -> index <= current || entry.id !in excluded }
        val prefix = queued.take(current + 1)
        val spoken = prefix.map { it.id }.toSet()
        val future = queued.drop(current + 1).filter { it.id !in excluded }.toMutableList()
        // Catch up newly exposed paragraphs behind the cursor, but never read before
        // the user's selected starting entry or replay any already queued prefix ID.
        val start = prefix.mapNotNull { entry -> cached.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 } }.minOrNull() ?: anchor
        val observed = cached.drop(start).filter { it.id !in spoken && it.id !in excluded }
        val positions = observed.mapNotNull { entry -> future.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 } }
        if (positions.zipWithNext().any { (a, b) -> a >= b }) return queued
        observed.forEachIndexed { index, entry ->
            if (future.none { it.id == entry.id }) {
                val next = observed.drop(index + 1).firstOrNull { candidate -> future.any { it.id == candidate.id } }
                val slot = next?.let { n -> future.indexOfFirst { it.id == n.id } } ?: future.size
                future.add(slot, entry)
            }
        }
        return prefix + future.take((limit - prefix.size).coerceAtLeast(0))
    }
}
