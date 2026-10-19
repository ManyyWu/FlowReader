package com.flowreader

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import com.flowreader.accessibility.AccessibilityServiceState
import com.flowreader.diagnostics.SnapshotStore
import com.flowreader.diagnostics.formatStructureSummary

class SnapshotActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var diagnostics: TextView
    private lateinit var capturedText: TextView
    private lateinit var raw: TextView
    private var showRaw = false
    private var showCache = false
    private lateinit var cachedText: TextView
    private val refresh = Runnable { render() }
    private val listener: () -> Unit = {
        handler.removeCallbacks(refresh); handler.postDelayed(refresh, 250)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * density).toInt(); setPadding(padding, padding, padding, padding)
        }
        fun label(value: String, size: Float = 15f) = TextView(this).apply {
            text = value; textSize = size; setTextIsSelectable(true)
            setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
        }.also { column.addView(it) }
        column.addView(Button(this).apply { text = "返回设置"; setOnClickListener { finish() } })
        label("快照与诊断", 26f)
        column.addView(Button(this).apply { text = "清除快照"; setOnClickListener { SnapshotStore.clear(); render() } })
        diagnostics = label("")
        label("当前提取文本", 20f)
        capturedText = label("")
        column.addView(CheckBox(this).apply {
            text = "显示会话阅读缓存"
            setOnCheckedChangeListener { _, checked -> showCache = checked; render() }
        })
        cachedText = label("")
        column.addView(CheckBox(this).apply {
            text = "显示原始节点"
            setOnCheckedChangeListener { _, checked -> showRaw = checked; render() }
        })
        raw = label("")
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true; isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
            addView(column)
        })
    }
    override fun onStart() {
        super.onStart(); SnapshotStore.addListener(listener); AccessibilityServiceState.addListener(listener); render()
    }
    override fun onStop() {
        SnapshotStore.removeListener(listener); AccessibilityServiceState.removeListener(listener)
        handler.removeCallbacks(refresh); super.onStop()
    }
    private fun render() {
        val snapshot = SnapshotStore.latest
        diagnostics.text = buildString {
            appendLine(if (snapshot == null) "暂无采集信息" else
                "应用：${snapshot.packageName}\n事件：${snapshot.eventType}\n节点：${snapshot.nodes.size} · 完整：${snapshot.isTraversalComplete}\n采集时间：${snapshot.capturedAtMillis}\n${snapshot.traversalDiagnostics}")
            appendLine("显示状态：${SnapshotStore.latestDisplay.status}")
            appendLine(AccessibilityServiceState.compatibilityStatus)
            appendLine(AccessibilityServiceState.playbackSummary())
            appendLine(SnapshotStore.readingCache.summary())
            appendLine(SnapshotStore.continuationFailure?.summary().orEmpty())
            appendLine(SnapshotStore.detailSummary())
            append(SnapshotStore.timelineSummary())
        }
        capturedText.text = SnapshotStore.latestDisplay.text.ifBlank { "暂无可读正文" }
        cachedText.visibility = if (showCache) android.view.View.VISIBLE else android.view.View.GONE
        cachedText.text = if (showCache) SnapshotStore.readingCache.format() else ""
        raw.visibility = if (showRaw) android.view.View.VISIBLE else android.view.View.GONE
        raw.text = if (showRaw) (snapshot?.let { it.formatStructureSummary() + "\n" + it.formatForDisplay() }
            ?: "暂无节点") + "\n" + SnapshotStore.continuationFailure?.format().orEmpty() else ""
    }
}
