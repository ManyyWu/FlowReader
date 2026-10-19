package com.flowreader.scroll

/** Ineligible observations break stability, even when the next valid values are unchanged. */
internal class StableEvidence {
    private var last = emptyList<String>()
    private var since: Long? = null
    fun observe(values: List<String>, eligible: Boolean, now: Long, minimumStableMs: Long = 500): Boolean {
        if (!eligible) { since = null; last = values; return false }
        if (since == null || values != last) since = now
        last = values
        return now - (since ?: now) >= minimumStableMs
    }
}
