package com.flowreader.settings

import android.content.Context

class AppSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("flowreader_settings", Context.MODE_PRIVATE)
    var simulatedPlayback: Boolean
        get() = preferences.getBoolean("simulated_playback", false)
        set(value) { preferences.edit().putBoolean("simulated_playback", value).apply() }
    var simulationDelayMs: Int
        get() = preferences.getInt("simulation_delay_ms", 500).coerceIn(100, 5000)
        set(value) { preferences.edit().putInt("simulation_delay_ms", value.coerceIn(100, 5000)).apply() }
    var scrollIntervalMs: Int
        get() = preferences.getInt("scroll_interval_ms", 5000).coerceIn(100, 60000)
        set(value) { preferences.edit().putInt("scroll_interval_ms", value.coerceIn(100, 60000)).apply() }
    var scrollPercent: Int
        get() = preferences.getInt("scroll_percent", 50).coerceIn(10, 100)
        set(value) { preferences.edit().putInt("scroll_percent", value.coerceIn(10, 100)).apply() }
    var continuousScroll: Boolean
        get() = preferences.getBoolean("continuous_scroll", false)
        set(value) { preferences.edit().putBoolean("continuous_scroll", value).apply() }
    var scrollSpeedPercentPerSecond: Int
        get() {
            val stored = preferences.getInt("scroll_speed_percent_per_second", 100).coerceIn(1, 300)
            if (stored <= 10) return stored
            return (((stored + 2) / 5) * 5).coerceIn(15, 300)
        }
        set(value) { preferences.edit().putInt("scroll_speed_percent_per_second", value.coerceIn(1, 300)).apply() }
    var overlayTransparency: Int
        get() = preferences.getInt("overlay_transparency", 0).coerceIn(0, 100)
        set(value) { preferences.edit().putInt("overlay_transparency", value.coerceIn(0, 100)).apply() }
    var overlayAutoHide: Boolean
        get() = preferences.getBoolean("overlay_auto_hide", true)
        set(value) { preferences.edit().putBoolean("overlay_auto_hide", value).apply() }
    var overlayColor: Int
        get() = preferences.getInt("overlay_color", android.graphics.Color.rgb(225, 233, 245))
        set(value) { preferences.edit().putInt("overlay_color", value or 0xff000000.toInt()).apply() }
    var overlayScrollMode: Boolean
        get() = preferences.getBoolean("overlay_scroll_mode", false)
        set(value) { preferences.edit().putBoolean("overlay_scroll_mode", value).apply() }
    var overlayEnabled: Boolean
        get() = preferences.getBoolean("overlay_enabled", true)
        set(value) { preferences.edit().putBoolean("overlay_enabled", value).apply() }
    var prefetchDepth: Int
        get() = preferences.getInt("prefetch_depth", 1).coerceIn(0, 2)
        set(value) { preferences.edit().putInt("prefetch_depth", value.coerceIn(0, 2)).apply() }
    var autoContinue: Boolean
        get() = preferences.getBoolean("auto_continue", true)
        set(value) { preferences.edit().putBoolean("auto_continue", value).apply() }
    var useEdge: Boolean
        get() = preferences.getBoolean("use_edge", true)
        set(value) { preferences.edit().putBoolean("use_edge", value).apply() }
    var edgeUrl: String
        get() = preferences.getString("edge_url", com.flowreader.speech.EdgeProtocol.DEFAULT_URL)!!
        set(value) { preferences.edit().putString("edge_url", value.trim()).apply() }
    var edgeToken: String
        get() = preferences.getString("edge_token", com.flowreader.speech.EdgeProtocol.DEFAULT_TOKEN)!!
        set(value) { preferences.edit().putString("edge_token", value.trim()).apply() }
    var edgeVoice: String
        get() = preferences.getString("edge_voice", "zh-CN-XiaoxiaoNeural")!!
        set(value) { preferences.edit().putString("edge_voice", value.trim()).apply() }
    var speechRate: Float
        get() = preferences.getFloat("speech_rate", 1.5f).coerceIn(0.5f, 3f)
        set(value) { preferences.edit().putFloat("speech_rate", value.coerceIn(0.5f, 3f)).apply() }
    var xCompatibility: Boolean
        get() = preferences.getBoolean("x_compatibility", false)
        set(value) { preferences.edit().putBoolean("x_compatibility", value).apply() }
    val panelWidth: Int get() = preferences.getInt("panel_width", 330).coerceIn(280, 800)
    val panelHeight: Int get() = preferences.getInt("panel_height", 440).coerceIn(320, 1000)
    fun savePanelSize(width: Int, height: Int) {
        preferences.edit().putInt("panel_width", width.coerceIn(280, 800))
            .putInt("panel_height", height.coerceIn(320, 1000)).apply()
    }
}
