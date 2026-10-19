package com.flowreader

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.view.View
import android.widget.AdapterView
import android.widget.NumberPicker
import com.flowreader.speech.EdgeConfig
import com.flowreader.speech.EdgeSpeechClient
import com.flowreader.accessibility.AccessibilityReaderService
import com.flowreader.accessibility.AccessibilityServiceState
import com.flowreader.diagnostics.SnapshotStore
import com.flowreader.diagnostics.formatStructureSummary
import com.flowreader.settings.AppSettings
import java.io.FileDescriptor
import java.io.PrintWriter

class MainActivity : Activity() {
    private val scrollSpeedValues = (1..10).toList() + (15..300 step 5).toList()
    private val settings by lazy { AppSettings(this) }
    private var testClient: EdgeSpeechClient? = null
    private var cancelTest: (() -> Unit)? = null
    private var sampleExportFile: java.io.File? = null
    private var testEpoch = 0L
    private val listener: () -> Unit = { refreshSettings() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        fun beginSampling(pkg: String) {
            val name = if (pkg == "com.twitter.android") "X" else "Reddit"
            fun begin() {
                com.flowreader.diagnostics.ScrollSampling.start(this, pkg)
                refreshSettings()
                android.widget.Toast.makeText(this, "已开始 $name 采样，请切到 $name 后播放", android.widget.Toast.LENGTH_LONG).show()
            }
            if (com.flowreader.diagnostics.ScrollSampling.active) {
                android.app.AlertDialog.Builder(this).setMessage("结束当前批次，开始 $name 采样？")
                    .setNegativeButton("取消", null).setPositiveButton("开始 $name 采样") { _, _ -> begin() }.show()
            } else begin()
        }
        findViewById<Button>(R.id.sample_x).setOnClickListener { beginSampling("com.twitter.android") }
        findViewById<Button>(R.id.sample_reddit).setOnClickListener { beginSampling("com.reddit.frontpage") }
        findViewById<Button>(R.id.sample_stop).setOnClickListener {
            com.flowreader.diagnostics.ScrollSampling.stop(); refreshSettings()
        }
        findViewById<Button>(R.id.sample_export).setOnClickListener {
            com.flowreader.diagnostics.ScrollSampling.stop()
            val file = com.flowreader.diagnostics.ScrollSampling.latestFile(this)
            if (file == null) android.widget.Toast.makeText(this, "暂无采样", android.widget.Toast.LENGTH_SHORT).show()
            else {
                sampleExportFile = file
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-ndjson"
                    putExtra(Intent.EXTRA_TITLE, file.name)
                }, 901)
            }
        }
        findViewById<Button>(R.id.open_accessibility_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<SeekBar>(R.id.overlay_transparency).apply {
            progress = settings.overlayTransparency
            this@MainActivity.findViewById<TextView>(R.id.overlay_transparency_label).text = "悬浮球整体透明度：${settings.overlayTransparency}%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) settings.overlayTransparency = value
                    this@MainActivity.findViewById<TextView>(R.id.overlay_transparency_label).text = "悬浮球整体透明度：${value}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        findViewById<Switch>(R.id.overlay_auto_hide).apply {
            isChecked = settings.overlayAutoHide
            setOnCheckedChangeListener { _, checked -> settings.overlayAutoHide = checked }
        }
        findViewById<Button>(R.id.overlay_color).setOnClickListener {
            val input = EditText(this).apply {
                setText("#%06X".format(settings.overlayColor and 0x00ffffff))
                hint = "#RRGGBB，例如 #E1E9F5"; setSingleLine()
            }
            val presets = arrayOf("雾白", "浅蓝", "薄荷", "淡紫", "暖杏", "自定义 HEX")
            val colors = intArrayOf(0xffe1e9f5.toInt(), 0xffbfdbfe.toInt(), 0xffc3e5d3.toInt(), 0xffddd2ef.toInt(), 0xfff2dcc4.toInt())
            android.app.AlertDialog.Builder(this).setTitle("悬浮球与图标颜色").setItems(presets) { _, index ->
                if (index < colors.size) settings.overlayColor = colors[index]
                else {
                    val dialog = android.app.AlertDialog.Builder(this).setTitle("自定义颜色").setView(input)
                        .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                    dialog.setOnShowListener {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val raw = input.text.toString().trim()
                            val color = runCatching { android.graphics.Color.parseColor(raw) }.getOrNull()
                            if (color == null || !raw.matches(Regex("#[0-9a-fA-F]{6}"))) input.error = "请输入 #RRGGBB"
                            else { settings.overlayColor = color; dialog.dismiss() }
                        }
                    }
                    dialog.show()
                }
            }.show()
        }
        findViewById<Button>(R.id.open_snapshots).setOnClickListener {
            startActivity(Intent(this, SnapshotActivity::class.java))
        }
        findViewById<Switch>(R.id.x_compatibility).apply {
            isChecked = settings.xCompatibility
            setOnCheckedChangeListener { _, checked -> settings.xCompatibility = checked }
        }
        findViewById<SeekBar>(R.id.speech_rate).apply {
            progress = ((settings.speechRate - 0.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val rate = 0.5f + value / 10f
                    if (fromUser) settings.speechRate = rate
                    updateRateLabel(rate)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        setupEdgeSettings()
        findViewById<Switch>(R.id.show_overlay).apply {
            isChecked = settings.overlayEnabled
            setOnCheckedChangeListener { _, checked -> settings.overlayEnabled = checked }
        }
        refreshSettings()
    }
    private fun setupEdgeSettings() {
        findViewById<Switch>(R.id.simulated_playback).apply {
            isChecked = settings.simulatedPlayback
            setOnCheckedChangeListener { _, checked -> settings.simulatedPlayback = checked }
        }
        findViewById<SeekBar>(R.id.simulation_delay).apply {
            progress = settings.simulationDelayMs / 100 - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val delay = (value + 1) * 100
                    if (fromUser) settings.simulationDelayMs = delay
                    this@MainActivity.findViewById<TextView>(R.id.simulation_delay_label).text = "模拟播放：每条 ${delay}ms"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        findViewById<NumberPicker>(R.id.scroll_interval).apply {
            minValue = 1; maxValue = 600
            wrapSelectorWheel = false
            displayedValues = Array(600) { index -> formatScrollInterval((index + 1) * 100).substringAfter("：") }
            value = settings.scrollIntervalMs / 100
            setOnValueChangedListener { _, _, value ->
                settings.scrollIntervalMs = value * 100
                this@MainActivity.findViewById<TextView>(R.id.scroll_interval_label).text = formatScrollInterval(value * 100)
            }
        }
        findViewById<NumberPicker>(R.id.scroll_speed).apply {
            minValue = 0; maxValue = scrollSpeedValues.lastIndex
            wrapSelectorWheel = false
            displayedValues = scrollSpeedValues.map { "$it%屏高/秒" }.toTypedArray()
            value = scrollSpeedValues.indexOf(settings.scrollSpeedPercentPerSecond).coerceAtLeast(0)
            setOnValueChangedListener { _, _, index ->
                val speed = scrollSpeedValues[index]
                settings.scrollSpeedPercentPerSecond = speed
                this@MainActivity.findViewById<TextView>(R.id.scroll_speed_label).text = "连续滚屏速度：${speed}%屏高/秒"
            }
        }
        findViewById<Switch>(R.id.continuous_scroll).apply {
            isChecked = settings.continuousScroll
            setOnCheckedChangeListener { _, checked ->
                settings.continuousScroll = checked
                updateScrollSettingsVisibility()
            }
        }
        findViewById<SeekBar>(R.id.scroll_percent).apply {
            progress = settings.scrollPercent - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val percent = value + 10
                    if (fromUser) settings.scrollPercent = percent
                    this@MainActivity.findViewById<TextView>(R.id.scroll_percent_label).text = "每次滚动：屏高的${percent}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        val depthButton = findViewById<Button>(R.id.prefetch_depth)
        val depths = arrayOf("关闭预加载", "提前1段", "提前2段")
        fun refreshDepth() { depthButton.text = "语音预加载：${depths[settings.prefetchDepth]}" }
        refreshDepth()
        depthButton.setOnClickListener {
            android.app.AlertDialog.Builder(this).setTitle("语音预加载")
                .setSingleChoiceItems(depths, settings.prefetchDepth) { dialog, which ->
                    settings.prefetchDepth = which; refreshDepth(); dialog.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        findViewById<Switch>(R.id.auto_continue).apply {
            isChecked = settings.autoContinue
            setOnCheckedChangeListener { _, checked -> settings.autoContinue = checked }
        }
        findViewById<EditText>(R.id.edge_url).setText(settings.edgeUrl)
        findViewById<EditText>(R.id.edge_token).setText(settings.edgeToken)
        val voiceInput = findViewById<EditText>(R.id.edge_voice)
        voiceInput.setText(settings.edgeVoice)
        findViewById<Switch>(R.id.use_edge).apply {
            isChecked = settings.useEdge
            setOnCheckedChangeListener { _, checked -> settings.useEdge = checked }
        }
        findViewById<Button>(R.id.edge_voice_list).setOnClickListener {
            showVoicePicker(voiceInput)
        }
        findViewById<Button>(R.id.save_edge).setOnClickListener {
            if (saveEdgeSettings()) findViewById<TextView>(R.id.edge_test_status).text = "语音配置已保存"
        }
        findViewById<Button>(R.id.test_edge).setOnClickListener { testEdge() }
        findViewById<Button>(R.id.preview_voice).setOnClickListener {
            if (saveEdgeSettings()) {
                val started = AccessibilityServiceState.previewSpeech()
                findViewById<TextView>(R.id.edge_test_status).text = if (started) "已请求试听，可在悬浮球展开后停止"
                    else if (!settings.overlayEnabled) "请先打开“显示悬浮球”，以便控制和停止试听" else "请先开启无障碍服务"
            }
        }
        findViewById<Button>(R.id.system_tts_settings).setOnClickListener {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
    private fun showVoicePicker(input: EditText) {
        val names = mapOf("Xiaoxiao" to "晓晓", "Xiaoyi" to "晓伊", "Yunjian" to "云健",
            "Yunxi" to "云希", "Yunxia" to "云夏", "Yunyang" to "云扬", "Xiaobei" to "晓北",
            "Xiaoni" to "晓妮", "HiuGaai" to "晓佳", "HiuMaan" to "晓曼", "WanLung" to "云龙",
            "HsiaoChen" to "晓臻", "YunJhe" to "云哲", "HsiaoYu" to "晓雨")
        val data = org.json.JSONArray(assets.open("edge-voices.json").bufferedReader().use { it.readText() })
        val voices = (0 until data.length()).map { data.getJSONObject(it) }
            .sortedWith(compareBy({ !it.getString("locale").startsWith("zh-") }, { it.getString("id") }))
        val labels = voices.map { voice ->
            val id = voice.getString("id")
            val name = names.entries.firstOrNull { id.endsWith("${it.key}Neural") }?.value
            "${name?.let { "$it · " }.orEmpty()}$id · ${if (voice.getString("gender") == "Male") "男声" else "女声"}"
        }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 0, 24, 0)
        }
        val search = EditText(this).apply { hint = "搜索：云健 / zh-CN / en-US"; setSingleLine() }
        val list = android.widget.ListView(this)
        var indices = voices.indices.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels.toMutableList())
        list.adapter = adapter
        content.addView(search)
        content.addView(list, android.widget.LinearLayout.LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
        val dialog = android.app.AlertDialog.Builder(this).setTitle("选择音色（${voices.size}种）")
            .setView(content).setNegativeButton("取消", null).create()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                indices = labels.indices.filter { labels[it].contains(query, ignoreCase = true) ||
                    voices[it].getString("name").contains(query, ignoreCase = true) }
                adapter.clear(); adapter.addAll(indices.map { labels[it] }); adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            input.setText(voices[indices[position]].getString("id")); dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    private fun saveEdgeSettings(): Boolean {
        val url = findViewById<EditText>(R.id.edge_url).text.toString().trim()
        val token = findViewById<EditText>(R.id.edge_token).text.toString().trim()
        val voice = findViewById<EditText>(R.id.edge_voice).text.toString().trim()
        if (!url.startsWith("wss://") || android.net.Uri.parse(url).host.isNullOrBlank() || token.isEmpty() ||
            !voice.matches(Regex("[A-Za-z0-9-]{1,120}"))) {
            findViewById<TextView>(R.id.edge_test_status).text = "请填写有效的 wss:// 接口、Token 和音色"
            return false
        }
        settings.edgeUrl = url; settings.edgeToken = token; settings.edgeVoice = voice
        return true
    }
    private fun testEdge() {
        if (!saveEdgeSettings()) return
        cancelTest?.invoke()
        val epoch = ++testEpoch
        val button = findViewById<Button>(R.id.test_edge)
        button.isEnabled = false
        findViewById<TextView>(R.id.edge_test_status).text = "正在合成示例，最长等待30秒…"
        val client = testClient ?: EdgeSpeechClient().also { testClient = it }
        cancelTest = client.synthesize(EdgeConfig(settings.edgeUrl, settings.edgeToken, settings.edgeVoice, settings.speechRate),
            "你好，这是 FlowReader 在线语音测试。Hello, FlowReader.") { result ->
            runOnUiThread {
                if (epoch != testEpoch || isDestroyed) return@runOnUiThread
                button.isEnabled = true
                findViewById<TextView>(R.id.edge_test_status).text = result.fold(
                    { "合成成功：收到 ${it.size} 字节音频，可点击试听" }, { it.message ?: "连接失败" })
            }
        }
    }
    override fun onDestroy() {
        testEpoch++; cancelTest?.invoke(); testClient?.close(); super.onDestroy()
    }
    override fun onStart() { super.onStart(); AccessibilityServiceState.addListener(listener) }
    override fun onStop() { AccessibilityServiceState.removeListener(listener); super.onStop() }
    override fun onResume() {
        super.onResume(); refreshSettings(); AccessibilityServiceState.restoreTextOverlay()
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 901 && resultCode == RESULT_OK) {
            val file = sampleExportFile ?: return
            val uri = data?.data ?: return
            com.flowreader.diagnostics.ScrollSampling.export(this, file, uri) { success ->
                android.widget.Toast.makeText(this, if (success) "采样已导出" else "导出失败", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun updateRateLabel(rate: Float) {
        findViewById<TextView>(R.id.speech_rate_label).text = "语速：%.1f 倍".format(rate)
    }
    private fun refreshSettings() {
        findViewById<TextView>(R.id.sample_status).text = com.flowreader.diagnostics.ScrollSampling.summary()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any {
                it.equals(ComponentName(this, AccessibilityReaderService::class.java).flattenToString(), true)
            }
        findViewById<TextView>(R.id.service_status).text = when {
            AccessibilityServiceState.connected -> "无障碍服务已连接"
            enabled -> "无障碍已授权，等待服务连接"
            else -> "无障碍服务未开启"
        }
        findViewById<TextView>(R.id.compatibility_status).text = AccessibilityServiceState.compatibilityStatus
        updateRateLabel(settings.speechRate)
        findViewById<Switch>(R.id.auto_continue).isChecked = settings.autoContinue
        findViewById<Switch>(R.id.show_overlay).isChecked = settings.overlayEnabled
        findViewById<Switch>(R.id.overlay_auto_hide).isChecked = settings.overlayAutoHide
        findViewById<Switch>(R.id.simulated_playback).isChecked = settings.simulatedPlayback
        findViewById<Switch>(R.id.continuous_scroll).isChecked = settings.continuousScroll
        findViewById<TextView>(R.id.simulation_delay_label).text = "模拟播放：每条 ${settings.simulationDelayMs}ms"
        findViewById<TextView>(R.id.scroll_interval_label).text = formatScrollInterval(settings.scrollIntervalMs)
        findViewById<TextView>(R.id.scroll_percent_label).text = "每次滚动：屏高的${settings.scrollPercent}%"
        findViewById<NumberPicker>(R.id.scroll_interval).value = settings.scrollIntervalMs / 100
        findViewById<TextView>(R.id.scroll_speed_label).text = "连续滚屏速度：${settings.scrollSpeedPercentPerSecond}%屏高/秒"
        findViewById<NumberPicker>(R.id.scroll_speed).value = scrollSpeedValues.indexOf(settings.scrollSpeedPercentPerSecond).coerceAtLeast(0)
        updateScrollSettingsVisibility()
    }
    private fun formatScrollInterval(intervalMs: Int): String = if (intervalMs < 1000)
        "滚动开始间隔：${intervalMs}毫秒" else "滚动开始间隔：%.1f秒".format(intervalMs / 1000f)
    private fun updateScrollSettingsVisibility() {
        val continuous = settings.continuousScroll
        findViewById<TextView>(R.id.scroll_interval_label).visibility = if (continuous) View.GONE else View.VISIBLE
        findViewById<NumberPicker>(R.id.scroll_interval).visibility = if (continuous) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.scroll_percent_label).visibility = if (continuous) View.GONE else View.VISIBLE
        findViewById<SeekBar>(R.id.scroll_percent).visibility = if (continuous) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.scroll_speed_label).visibility = if (continuous) View.VISIBLE else View.GONE
        findViewById<NumberPicker>(R.id.scroll_speed).visibility = if (continuous) View.VISIBLE else View.GONE
    }
    // Explicit debug read from memory; avoids UiAutomation suppressing accessibility services.
    override fun dump(prefix: String, fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && args?.contains("--flowreader-sampling") == true) {
            writer.println(com.flowreader.diagnostics.ScrollSampling.summary())
            writer.println(com.flowreader.diagnostics.ScrollSampling.latestFile(this)?.absolutePath); return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-reading-cache") == true) {
            writer.println(SnapshotStore.readingCache.format()); return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-continuation") == true) {
            writer.println(SnapshotStore.continuationFailure?.format() ?: "暂无续读失败现场")
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-snapshots") == true
        ) {
            writer.println(SnapshotStore.formatRecentForDisplay().ifBlank { getString(R.string.snapshot_empty) })
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-structure") == true
        ) {
            writer.println(SnapshotStore.latest?.formatStructureSummary() ?: "暂无快照")
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-timeline") == true
        ) {
            writer.println(SnapshotStore.timelineSummary())
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-detail") == true
        ) {
            writer.println(SnapshotStore.detailSummary())
            writer.println("显示状态=${SnapshotStore.latestDisplay.status}; 字符=${SnapshotStore.latestDisplay.text.length}")
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-playback") == true) {
            writer.println(AccessibilityServiceState.playbackSummary())
            return
        }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            args?.contains("--flowreader-scroll") == true) {
            writer.println(com.flowreader.scroll.ScrollDiagnostics.format())
            return
        }
        super.dump(prefix, fd, writer, args)
    }

}
