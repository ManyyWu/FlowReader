package com.flowreader.scroll

import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

/** Bounded in-memory record of requested scrolls and the source app's reported movement. */
object ScrollDiagnostics {
    private data class Request(val id: Long, val packageName: String, val windowId: Int, val at: Long)
    private val events = ArrayDeque<String>()
    private var request: Request? = null

    @Synchronized fun begin(id: Long, packageName: String, windowId: Int, reason: String, baseline: String) {
        request = Request(id, packageName, windowId, android.os.SystemClock.uptimeMillis())
        add("请求#$id 来源=$reason 包=$packageName 窗口=$windowId 基线={$baseline}")
    }
    @Synchronized fun plan(id: Long, detail: String) { add("请求#$id 执行计划 $detail") }
    @Synchronized fun result(id: Long, message: String, stable: Boolean, after: String) {
        add("请求#$id 结束 stable=$stable 结果=$message 后帧={$after}")
    }
    @Synchronized fun cancel(id: Long, message: String) { add("请求#$id 取消 $message") }
    @Synchronized fun onScroll(event: AccessibilityEvent, packageName: String, source: String) {
        val active = request?.takeIf {
            it.packageName == packageName && it.windowId == event.windowId &&
                android.os.SystemClock.uptimeMillis() - it.at <= 15_000
        }
        val delta = if (android.os.Build.VERSION.SDK_INT >= 28) "${event.scrollDeltaX},${event.scrollDeltaY}" else "不支持"
        add("${if (active == null) "外部" else "请求#${active.id}"} TYPE_VIEW_SCROLLED +${event.eventTime}ms " +
            "包=$packageName 窗口=${event.windowId} 节点=$source delta=$delta " +
            "offset=${event.scrollX},${event.scrollY} max=${event.maxScrollX},${event.maxScrollY} " +
            "items=${event.fromIndex}..${event.toIndex}/${event.itemCount}")
    }
    @Synchronized fun format(): String = if (events.isEmpty()) "暂无滚动诊断记录" else events.joinToString("\n")
    private fun add(value: String) {
        com.flowreader.diagnostics.ScrollSampling.event(value)
        if (events.size >= 120) events.removeFirst()
        events.addLast("${android.os.SystemClock.uptimeMillis()} $value")
    }
}
