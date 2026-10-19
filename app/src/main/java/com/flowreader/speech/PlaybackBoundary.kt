package com.flowreader.speech

/** Page evidence is independent of changing visible text or scroll position. */
class PlaybackBoundary {
    private data class Page(val packageName: String, val windowId: Int, val detail: Boolean, val identity: String?)
    private var page: Page? = null

    fun start(packageName: String, windowId: Int, detail: Boolean, identity: String?) {
        page = Page(packageName, windowId, detail, identity)
    }
    fun clear() { page = null }
    fun changed(packageName: String, windowId: Int, detail: Boolean, identity: String?, complete: Boolean): Boolean {
        val old = page ?: return false
        if (old.packageName != packageName) return true
        if (!complete) return false
        if (old.windowId >= 0 && windowId >= 0 && old.windowId != windowId) return true
        if (old.detail != detail) return true
        if (old.detail && old.identity != null && identity != null && old.identity != identity) return true
        if (old.detail && old.identity == null && identity != null) page = old.copy(identity = identity)
        return false
    }
}
