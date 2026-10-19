package com.flowreader.scroll

import android.os.Handler
import android.os.SystemClock
import com.flowreader.diagnostics.ScreenSnapshot
import com.flowreader.diagnostics.SnapshotStore

data class ScrollResult(val message: String, val stable: Boolean = false)

/** Main-thread bounded wait. A successful action alone never completes this wait. */
internal class ScrollSnapshotWait(private val handler: Handler, private val refresh: (String) -> Unit) {
    private var baseline: ScreenSnapshot? = null
    private var initial = emptyList<String>()
    private var requireChange = false
    fun requireContentChange() { requireChange = true }
    private var stability = StableEvidence()
    private val movement = ScrollMovementEvidence()
    fun noteMovement(packageName: String?, deltaX: Int, deltaY: Int) {
        if (active && packageName == this.packageName && movement.observeEvent(deltaX, deltaY))
            stability = StableEvidence()
    }
    private var nextRefresh = 0L
    private var deadline = 0L
    private var stabilityMs = 500L
    private var refreshIntervalMs = 350L
    private var pollIntervalMs = 100L
    private var detail = false
    private var identity: String? = null
    private var callback: ((ScrollResult) -> Unit)? = null
    val active: Boolean get() = callback != null
    val packageName: String? get() = baseline?.packageName
    private fun evidence(snapshot: ScreenSnapshot): List<String> =
        SnapshotStore.latestDisplay.readingBlocks.map {
            "${it.text}\n${snapshot.nodes.getOrNull(it.nodeIndex)?.boundsInScreen}"
        }
    fun start(before: ScreenSnapshot, result: (ScrollResult) -> Unit,
        minimumStableMs: Long = 500, refreshEveryMs: Long = 350, pollEveryMs: Long = 100) {
        cancel()
        movement.reset(); requireChange = false
        baseline = before; initial = evidence(before); stability = StableEvidence()
        detail = isDetail(before); identity = SnapshotStore.latestDisplay.detailIdentity
        stabilityMs = minimumStableMs.coerceAtLeast(0)
        refreshIntervalMs = refreshEveryMs.coerceAtLeast(50)
        pollIntervalMs = pollEveryMs.coerceAtLeast(25)
        deadline = SystemClock.uptimeMillis() + 5_000
        nextRefresh = SystemClock.uptimeMillis() + minOf(300, refreshIntervalMs); callback = result
        handler.post(tick)
    }
    fun cancel(message: String = "已取消滚动等待") { finish(message) }
    private fun isDetail(snapshot: ScreenSnapshot) = snapshot.nodes.any {
        it.viewId == "PostDetail" || it.viewId == "post_detail_scaffold"
    }
    private fun finish(message: String, stable: Boolean = false) {
        handler.removeCallbacks(tick)
        val result = callback
        callback = null; baseline = null; initial = emptyList()
        result?.invoke(ScrollResult(message, stable))
    }
    private val tick = object : Runnable {
        override fun run() {
            val before = baseline ?: return
            val now = SystemClock.uptimeMillis()
            val current = SnapshotStore.latest
            if (current == null || current.packageName != before.packageName || current.windowId != before.windowId) {
                finish("页面已切换，取消等待"); return
            }
            val values = evidence(current)
            val currentIdentity = SnapshotStore.latestDisplay.detailIdentity
            if (current.isTraversalComplete && (isDetail(current) != detail ||
                    (identity != null && currentIdentity != null && identity != currentIdentity))) {
                finish("帖子已切换，取消等待"); return
            }
            val eligible = current.capturedAtMillis > before.capturedAtMillis &&
                current.isTraversalComplete && ReadingViewportLayout.settled(current, SnapshotStore.latestDisplay.readingBlocks) &&
                (values.isNotEmpty() || movement.changed(false))
            val settled = stability.observe(values, eligible, now, stabilityMs)
            if (eligible && (if (requireChange) values != initial else movement.changed(values != initial)) && settled) {
                finish("页面已稳定，可选择文本播放", stable = true); return
            }
            if (now >= deadline) {
                finish(if (eligible && values == initial) "未检测到正文变化，可能已到底"
                    else "等待页面稳定超时，请稍后重试")
                return
            }
            if (now >= nextRefresh) {
                nextRefresh = now + refreshIntervalMs
                refresh(before.packageName)
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }
}
