package com.flowreader.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.view.accessibility.AccessibilityWindowInfo
import com.flowreader.overlay.CapturedTextOverlay
import android.app.KeyguardManager
import android.os.PowerManager
import android.graphics.Point
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.os.HandlerThread
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import com.flowreader.diagnostics.AccessibilityNodeSnapshot
import com.flowreader.diagnostics.ScreenSnapshot
import com.flowreader.diagnostics.SnapshotStore

class AccessibilityReaderService : AccessibilityService() {
    private val captureThread = HandlerThread("FlowReader-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var foregroundPackage: String? = null
    private var overlayRestoreAttempt = 0
    private val overlayRestoreTask = object : Runnable {
        override fun run() {
            if (!running || !settings.overlayEnabled || !screenIsUsable() || foregroundPackage !in TARGET_PACKAGES) return
            if (textOverlay.visible) return
            overlayRestoreAttempt++
            textOverlay.show()
            AccessibilityServiceState.notifyChanged()
            if (!textOverlay.visible && overlayRestoreAttempt < 6) {
                mainHandler.postDelayed(this, longArrayOf(300, 600, 1200, 2000, 3000)[overlayRestoreAttempt - 1])
            }
        }
    }
    fun restoreTextOverlay() {
        if (!running) return
        updateCompatibilityForForeground()
        compatibilityHandler.removeCallbacks(foregroundMonitor)
        compatibilityHandler.postDelayed(foregroundMonitor, 1_000)
    }
    private fun updateOverlayForForeground(activePackage: String?) {
        foregroundPackage = activePackage
        // An explicitly requested settings-page voice preview keeps its stop control until it ends.
        val previewInSettings = activePackage == packageName && textOverlay.readingPackage == packageName && textOverlay.active
        if (!settings.overlayEnabled || (activePackage !in TARGET_PACKAGES && !previewInSettings)) {
            mainHandler.removeCallbacks(overlayRestoreTask)
            if (textOverlay.visible || textOverlay.active) {
                textOverlay.hide()
                AccessibilityServiceState.notifyChanged()
            }
            return
        }
        if (!textOverlay.visible) {
            mainHandler.removeCallbacks(overlayRestoreTask)
            overlayRestoreAttempt = 0
            mainHandler.post(overlayRestoreTask)
        }
    }
    private val settings by lazy { com.flowreader.settings.AppSettings(this) }
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post { if (running) applySavedSettings() }
    }
    private fun applySavedSettings() {
        if (settings.xCompatibility) enableXCompatibility() else disableXCompatibility()
        if (xCompatibilityEnabled) {
            if (settings.overlayScrollMode) suspendCompatibilityForScrollMode()
            else updateCompatibilityForForeground()
        }
        textOverlay.refreshPreferences()
        if (settings.overlayEnabled) restoreTextOverlay()
        else {
            mainHandler.removeCallbacks(overlayRestoreTask)
            if (textOverlay.visible || textOverlay.playing) textOverlay.hide()
        }
    }

    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) hideForLockedScreen()
            else {
                if (enforceUnlockedScreen()) restoreTextOverlay()
                if (xCompatibilityEnabled) {
                    compatibilityHandler.removeCallbacks(foregroundMonitor)
                    compatibilityHandler.post(foregroundMonitor)
                }
            }
        }
    }

    private fun screenIsUsable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun hideForLockedScreen() {
        mainHandler.removeCallbacks(overlayRestoreTask)
        textOverlay.hide()
        withdrawPassthrough()
        compatibilityHandler.removeCallbacks(foregroundMonitor)
        if (xCompatibilityEnabled) setCompatibilityStatus("X 兼容模式待机：屏幕关闭或已锁定")
    }

    private fun enforceUnlockedScreen(): Boolean {
        if (screenIsUsable()) return true
        hideForLockedScreen()
        return false
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        unregisterReceiver(screenReceiver)
        screenReceiverRegistered = false
    }
    private var captureInFlight = false
    private var requestRevision = 0L
    private var captureGeneration = 0L
    private var captureScheduled = false
    private var pendingPackage = ""
    private var pendingEventType = 0
    private var foregroundCheckPending = false
    private val captureTask: Runnable = Runnable {
        captureScheduled = false
        if (captureInFlight || !running || textOverlay.scrollOnlyActive || !enforceUnlockedScreen()) return@Runnable
        captureInFlight = true
        val generation = captureGeneration
        val request = requestRevision
        val targetPackage = pendingPackage
        val type = pendingEventType
        val revision = SnapshotStore.clearRevision
        captureHandler.post {
            val snapshot = collectSnapshot(targetPackage, type)
            mainHandler.post publish@ {
                if (generation != captureGeneration) return@publish
                captureInFlight = false
                if (running && screenIsUsable() && snapshot != null && !textOverlay.scrollOnlyActive &&
                    SnapshotStore.clearRevision == revision) {
                    SnapshotStore.publish(snapshot)
                    if (com.flowreader.diagnostics.ScrollSampling.active) com.flowreader.diagnostics.ScrollSampling.frame(snapshot, SnapshotStore.latestDisplay,
                        SnapshotStore.readingCache.entries, playbackSummary())
                }
                if (running && !textOverlay.scrollOnlyActive && requestRevision != request && !captureScheduled) {
                    captureScheduled = true
                    mainHandler.postDelayed(captureTask, 100)
                }
            }
        }
    }

    private val textOverlay: CapturedTextOverlay by lazy {
        CapturedTextOverlay(this, { reason, percent, callback -> scrollOnePage(reason, percent, callback) },
            { percent, interval, target, callback -> scrollOnlyStep(percent, interval, target, callback) }, ::cancelScrollWait) {
            AccessibilityServiceState.notifyChanged()
            if (textOverlay.visible || textOverlay.playing) {
                compatibilityHandler.removeCallbacks(foregroundMonitor)
                compatibilityHandler.postDelayed(foregroundMonitor, 1_000)
            }
        }
    }
    fun previewSpeech(): Boolean {
        if (!running || !settings.overlayEnabled || !enforceUnlockedScreen()) return false
        textOverlay.previewSpeech()
        return true
    }
    fun playbackSummary(): String = "服务运行=$running；悬浮球=${textOverlay.visible}；前台应用=${foregroundPackage ?: "未知"}；窗口恢复尝试=$overlayRestoreAttempt；窗口错误=${textOverlay.windowError ?: "无"}\n" + textOverlay.playbackSummary()
    val textOverlayVisible: Boolean get() = textOverlay.visible
    fun toggleTextOverlay() {
        settings.overlayEnabled = !settings.overlayEnabled
        applySavedSettings()
    }

    private var scrollInFlight = false
    @Volatile private var scrollOperation = 0L
    private val scrollWait = com.flowreader.scroll.ScrollSnapshotWait(mainHandler) { source ->
        if (running && screenIsUsable() && !captureInFlight && !captureScheduled) {
            pendingPackage = source
            pendingEventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            requestRevision++
            captureScheduled = true
            mainHandler.post(captureTask)
        }
    }
    private fun cancelScrollWait() { scrollOperation++; scrollWait.cancel() }

    /** Dispatches a timed screen swipe without collecting or parsing accessibility text. */
    private fun scrollOnlyStep(percent: Float, intervalMs: Int, expected: com.flowreader.scroll.ScrollOnlyTarget?,
        result: (com.flowreader.scroll.ScrollOnlyActionResult) -> Unit) {
        if (passthroughActive) suspendCompatibilityForScrollMode()
        if (!running || !screenIsUsable()) {
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "屏幕不可用，纯滚屏已停止")); return
        }
        if (serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "缺少手势能力，请重新启用无障碍服务")); return
        }
        val root = try { contentRoot() } catch (_: RuntimeException) { null }
        if (root == null) {
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "没有可滚动的前台应用窗口")); return
        }
        val target = com.flowreader.scroll.ScrollOnlyTarget(root.packageName?.toString().orEmpty(), root.windowId)
        if (target.packageName.isBlank() || target.packageName == packageName) {
            root.recycle()
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "请先切换到需要滚动的应用")); return
        }
        if (expected != null && target != expected) {
            root.recycle()
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "前台应用或窗口已变化，纯滚屏已停止")); return
        }
        if (expected == null) {
            requestRevision++
            captureScheduled = false
            mainHandler.removeCallbacks(captureTask)
        }
        val bounds = Rect().also(root::getBoundsInScreen)
        root.recycle()
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (!bounds.intersect(screen)) {
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "前台窗口没有可用屏幕区域")); return
        }
        fun box(r: Rect) = com.flowreader.scroll.HalfScrollGeometry.Box(r.left, r.top, r.right, r.bottom)
        val swipe = com.flowreader.scroll.HalfScrollGeometry.plan(box(bounds), textOverlay.occlusionBounds()?.let(::box), percent)
        if (swipe == null) {
            result(com.flowreader.scroll.ScrollOnlyActionResult(false, "悬浮窗遮挡了可用滚动区域")); return
        }
        val duration = if (intervalMs <= 180) (intervalMs - 30).coerceAtLeast(50).toLong() else 180L
        val path = android.graphics.Path().apply { moveTo(swipe.x, swipe.fromY); lineTo(swipe.x, swipe.toY) }
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(
            android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration)).build()
        val accepted = try { dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(description: android.accessibilityservice.GestureDescription?) {
                result(com.flowreader.scroll.ScrollOnlyActionResult(false, "系统取消了滚动手势", target))
            }
            override fun onCompleted(description: android.accessibilityservice.GestureDescription?) {
                result(com.flowreader.scroll.ScrollOnlyActionResult(true, "已滚动", target))
            }
        }, mainHandler) } catch (_: RuntimeException) { false }
        if (!accepted) result(com.flowreader.scroll.ScrollOnlyActionResult(false, "滚动手势未能启动", target))
    }
    private var scrollReportedBottom = false
    private val readingScrollPlanner = com.flowreader.scroll.ReadingScrollPlanner()
    @Suppress("DEPRECATION")
    private fun scrollOnePage(reason: String, scrollPercent: Int, result: (com.flowreader.scroll.ScrollResult) -> Unit) {
        if (scrollInFlight) { result(com.flowreader.scroll.ScrollResult("正在请求滚动")); return }
        if (!running || !screenIsUsable()) { result(com.flowreader.scroll.ScrollResult("当前无法滚动")); return }
        scrollInFlight = true
        val generation = captureGeneration
        val expected = SnapshotStore.latest
        if (expected == null) { scrollInFlight = false; result(com.flowreader.scroll.ScrollResult("暂无正文快照")); return }
        val operation = ++scrollOperation
        scrollReportedBottom = false
        val beforeBlocks = SnapshotStore.latestDisplay.readingBlocks.toList()
        val plan = if (reason == "自动续读") readingScrollPlanner.plan(expected, beforeBlocks, textOverlay.activeReadingNode(), scrollPercent) else null
        val requestedPercent = plan?.percent ?: scrollPercent
        var gesturePixels: Float? = null
        val baseline = SnapshotStore.latestDisplay.readingBlocks.take(4).joinToString(" | ") { block ->
            val bounds = expected.nodes.getOrNull(block.nodeIndex)?.boundsInScreen.orEmpty()
            "${block.text.take(48)}@$bounds"
        }
        com.flowreader.scroll.ScrollDiagnostics.begin(operation, expected.packageName, expected.windowId,
            reason, baseline)
        if (com.flowreader.diagnostics.ScrollSampling.active) com.flowreader.diagnostics.ScrollSampling.frame(expected, SnapshotStore.latestDisplay,
            SnapshotStore.readingCache.entries, playbackSummary(), "before", operation)
        plan?.let { com.flowreader.scroll.ScrollDiagnostics.plan(operation,
            "坐标计划 ${it.reason} target=${it.desiredPixels}px gain=${it.gain} gesture=${it.percent}%") }
        val fastScrollOnly = reason == "纯滚屏"
        scrollWait.start(expected, { message ->
            com.flowreader.scroll.ScrollDiagnostics.result(operation, message.message, message.stable,
                SnapshotStore.latestDisplay.readingBlocks.take(4).joinToString(" | ") { block ->
                    "${block.text.take(48)}@${SnapshotStore.latest?.nodes?.getOrNull(block.nodeIndex)?.boundsInScreen.orEmpty()}"
                })
            SnapshotStore.latest?.let { after ->
                if (message.stable && !scrollReportedBottom) gesturePixels?.let { distance ->
                    readingScrollPlanner.observe(expected, beforeBlocks, after, SnapshotStore.latestDisplay.readingBlocks, distance)?.let {
                        com.flowreader.scroll.ScrollDiagnostics.plan(operation, "坐标标定 gain=$it")
                    }
                }
                if (com.flowreader.diagnostics.ScrollSampling.active) com.flowreader.diagnostics.ScrollSampling.frame(after, SnapshotStore.latestDisplay,
                    SnapshotStore.readingCache.entries, playbackSummary(), "after", operation)
            }
            scrollOperation++; scrollInFlight = false; result(message)
        }, minimumStableMs = if (fastScrollOnly) 180 else 500,
            refreshEveryMs = if (fastScrollOnly) 120 else 350,
            pollEveryMs = if (fastScrollOnly) 40 else 100)
        captureHandler.post {
            var gestureBounds: Rect? = null
            var missingActionProbe = false
            val message = try {
                val root = if (running && screenIsUsable()) contentRoot() else null
                if (root == null) "未找到当前应用窗口"
                else try {
                    if (operation != scrollOperation || generation != captureGeneration ||
                        root.packageName?.toString() != expected.packageName ||
                        root.windowId != expected.windowId) "当前窗口已变化，请稍后重试"
                    else com.flowreader.scroll.SinglePageScroll.forward(root, { gestureBounds = it },
                        { detail -> com.flowreader.scroll.ScrollDiagnostics.plan(operation, detail) }, requestedPercent,
                        { missingActionProbe = true })
                } finally { root.recycle() }
            } catch (_: RuntimeException) { "页面已变化，请重试" }
            mainHandler.post {
                if (operation != scrollOperation) return@post
                if (generation != captureGeneration || !running || !screenIsUsable()) cancelScrollWait()
                else if (message != "已请求滚动，请观察页面变化") scrollWait.cancel(message)
                else gestureBounds?.let { bounds ->
                    if (missingActionProbe) scrollWait.requireContentChange()
                    dispatchHalfScroll(bounds, operation, expected, requestedPercent,
                        if (fastScrollOnly) 180 else 700) { gesturePixels = it }
                }
            }
        }
    }

    private fun dispatchHalfScroll(bounds: Rect, operation: Long, expected: ScreenSnapshot, scrollPercent: Int,
        gestureDurationMs: Long, onDistance: (Float) -> Unit = {}) {
        if (serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
            scrollWait.cancel("半屏滑动能力尚未加载，请在无障碍设置重新启用一次 FlowReader")
            return
        }
        val root = contentRoot()
        val matches = root?.packageName?.toString() == expected.packageName && root?.windowId == expected.windowId
        root?.recycle()
        if (!matches) { scrollWait.cancel("当前窗口已变化，取消半屏滚动"); return }
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (!bounds.intersect(screen)) { scrollWait.cancel("正文区域不在屏幕内"); return }
        fun box(r: Rect) = com.flowreader.scroll.HalfScrollGeometry.Box(r.left, r.top, r.right, r.bottom)
        val media = com.flowreader.scroll.ReadingGestureObstacles.media(expected)
        val swipe = com.flowreader.scroll.HalfScrollGeometry.plan(box(bounds), textOverlay.occlusionBounds()?.let(::box), scrollPercent.toFloat(), media)
        if (swipe == null) { scrollWait.cancel("悬浮小窗遮挡滚动区域，请先收起小窗"); return }
        onDistance(kotlin.math.abs(swipe.fromY - swipe.toY))
        val path = android.graphics.Path().apply { moveTo(swipe.x, swipe.fromY); lineTo(swipe.x, swipe.toY) }
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(
            android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, gestureDurationMs)).build()
        com.flowreader.scroll.ScrollDiagnostics.plan(operation,
            "dispatchGesture amount=${scrollPercent.coerceIn(10, 100)}% bounds=$bounds x=${swipe.x} y=${swipe.fromY}->${swipe.toY} duration=${gestureDurationMs}ms mediaAvoid=${media.size}")
        textOverlay.noteInjectedScroll(gestureDurationMs)
        val accepted = try { dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(description: android.accessibilityservice.GestureDescription?) {
                com.flowreader.scroll.ScrollDiagnostics.cancel(operation, "gesture callback cancelled")
                if (operation == scrollOperation) scrollWait.cancel("半屏滚动被取消")
            }
            override fun onCompleted(description: android.accessibilityservice.GestureDescription?) {
                com.flowreader.scroll.ScrollDiagnostics.plan(operation, "gesture callback completed")
            }
        }, mainHandler) } catch (_: RuntimeException) { false }
        com.flowreader.scroll.ScrollDiagnostics.plan(operation, "dispatchGesture accepted=$accepted")
        if (!accepted && operation == scrollOperation) scrollWait.cancel("半屏滑动未执行，请重试")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        textOverlay.onConfigurationChanged()
    }

    // A touched overlay can become active; use the focused application underneath it.
    @Suppress("DEPRECATION")
    private fun contentRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val window = root.window
        val overlay = window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        window?.recycle()
        if (!overlay) return root
        root.recycle()
        val candidates = windows
        return try {
            candidates.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root
        } finally { candidates.forEach { it.recycle() } }
    }

    private val compatibilityHandler = Handler(Looper.getMainLooper())
    var xCompatibilityEnabled = false
        private set
    var passthroughActive = false
        private set
    var compatibilityStatus = "X 兼容模式关闭"
        private set
    private val foregroundMonitor: Runnable = object : Runnable {
        override fun run() {
            if (!enforceUnlockedScreen()) return
            if (!running || (!settings.overlayEnabled && !xCompatibilityEnabled && !textOverlay.active)) return
            updateCompatibilityForForeground()
            if (running && (settings.overlayEnabled || xCompatibilityEnabled || textOverlay.active))
                compatibilityHandler.postDelayed(this, 1_000)
        }
    }
    private val explorationListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
        if (enabled && passthroughActive) {
            try { applyPassthroughRegion() }
            catch (_: RuntimeException) { failCompatibility("直通区域设置失败，兼容模式已关闭") }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyPassthroughRegion() {
        if (Build.VERSION.SDK_INT < 30) return
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display unavailable")
        val size = Point()
        display.getRealSize(size)
        // A square also covers the display after rotation, including system bars.
        val edge = maxOf(size.x, size.y)
        check(edge > 0)
        val region = Region(0, 0, edge, edge)
        setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, region)
        setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, region)
    }

    fun enableXCompatibility() {
        if (xCompatibilityEnabled) return
        val manager = getSystemService(AccessibilityManager::class.java)
        if (Build.VERSION.SDK_INT < 30 || manager.isTouchExplorationEnabled) {
            setCompatibilityStatus("无法开启：需要 Android 11 以上，并先关闭 TalkBack 等触摸探索服务")
            return
        }
        if (serviceInfo.capabilities and
            AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION == 0) {
            setCompatibilityStatus("请在系统设置中关闭再开启 FlowReader 无障碍服务，以加载兼容能力")
            return
        }
        // Restore the saved preference when the service connects; foreground checks still gate activation.
        xCompatibilityEnabled = true
        setCompatibilityStatus("X 兼容模式已开启，等待进入 X")
        compatibilityHandler.post(foregroundMonitor)
    }

    fun disableXCompatibility() {
        xCompatibilityEnabled = false
        compatibilityHandler.removeCallbacks(foregroundMonitor)
        withdrawPassthrough()
        setCompatibilityStatus("X 兼容模式关闭")
        if (settings.overlayEnabled || textOverlay.active)
            compatibilityHandler.postDelayed(foregroundMonitor, 1_000)
    }

    private fun setCompatibilityStatus(status: String) {
        if (compatibilityStatus == status) return
        compatibilityStatus = status
        AccessibilityServiceState.notifyChanged()
    }

    private fun failCompatibility(status: String) {
        disableXCompatibility()
        setCompatibilityStatus(status)
    }

    private fun withdrawPassthrough() {
        if (!passthroughActive) return
        passthroughActive = false
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            }
            if (Build.VERSION.SDK_INT >= 30) {
                setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
                setGestureDetectionPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
            }
        } catch (_: RuntimeException) {
            // Disconnect if the input-routing request cannot be withdrawn.
            xCompatibilityEnabled = false
            compatibilityHandler.removeCallbacks(foregroundMonitor)
            disableSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun updateCompatibilityForForeground() {
        if (!running || (!settings.overlayEnabled && !xCompatibilityEnabled && !textOverlay.active)) return
        if (foregroundCheckPending) return
        foregroundCheckPending = true
        val generation = captureGeneration
        captureHandler.post {
            val root = try { contentRoot() } catch (_: RuntimeException) { null }
            val activePackage = try {
                if (getSystemService(PowerManager::class.java).isInteractive &&
                    !getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                    root?.packageName?.toString() else null
            } finally { root?.recycle() }
            mainHandler.post foreground@ {
                if (generation != captureGeneration) return@foreground
                foregroundCheckPending = false
                if (!running || !enforceUnlockedScreen()) return@foreground
                updateOverlayForForeground(activePackage)
                if (textOverlay.active && activePackage != textOverlay.readingPackage) textOverlay.stopPlayback()
                if (scrollWait.active && activePackage != scrollWait.packageName) cancelScrollWait()
                if (xCompatibilityEnabled) applyForegroundCompatibility(activePackage == X_PACKAGE)
            }
        }
    }

    private fun suspendCompatibilityForScrollMode() {
        withdrawPassthrough()
        if (xCompatibilityEnabled) setCompatibilityStatus("X 兼容模式暂挂：纯滚屏无需读取正文，切回朗读后恢复")
    }

    private fun applyForegroundCompatibility(inX: Boolean) {
        if (settings.overlayScrollMode || textOverlay.scrollOnlyActive) {
            suspendCompatibilityForScrollMode()
            return
        }
        if (!inX) {
            withdrawPassthrough()
            setCompatibilityStatus(if (xCompatibilityEnabled) "X 兼容模式待机：当前未在 X" else "X 兼容模式关闭")
            return
        }
        if (passthroughActive) return
        if (getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) {
            failCompatibility("检测到其他触摸探索服务，兼容模式已关闭")
            return
        }
        passthroughActive = true
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            applyPassthroughRegion()
            setCompatibilityStatus("X 兼容模式运行中：全屏触摸直通")
        } catch (_: RuntimeException) {
            failCompatibility("启用直通失败，兼容模式已关闭")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        registerScreenReceiver()
        enforceUnlockedScreen()
        getSystemService(AccessibilityManager::class.java)
            .addTouchExplorationStateChangeListener(explorationListener)
        AccessibilityServiceState.attach(this)
        settings.preferences.registerOnSharedPreferenceChangeListener(settingsListener)
        applySavedSettings()
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!enforceUnlockedScreen()) return
        if (event.packageName?.toString() == packageName && textOverlay.ownsWindow(event.windowId)) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val deltaX = if (android.os.Build.VERSION.SDK_INT >= 28) event.scrollDeltaX else -1
            val deltaY = if (android.os.Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else -1
            if (scrollInFlight && event.packageName?.toString() == scrollWait.packageName && event.maxScrollY > 0)
                scrollReportedBottom = event.scrollY >= event.maxScrollY
            scrollWait.noteMovement(event.packageName?.toString(), deltaX, deltaY)
            val source = runCatching {
                event.source?.let { node ->
                    try {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        "${node.className}/${node.viewIdResourceName}@$bounds"
                    } finally { node.recycle() }
                } ?: "unknown"
            }.getOrDefault("unavailable")
            com.flowreader.scroll.ScrollDiagnostics.onScroll(event, event.packageName?.toString().orEmpty(), source)
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.packageName?.toString() == textOverlay.readingPackage) textOverlay.noteSourceScrolled()
        // A source-app click ends this post; a scroll rebases playback after the current block.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            if (scrollWait.active) cancelScrollWait()
            if (event.packageName?.toString() == textOverlay.readingPackage) textOverlay.stopPlayback()
            return // Click events do not need another full tree traversal.
        }

        // All packages may signal a foreground change; only allowlisted packages are captured.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (event.packageName?.toString() != X_PACKAGE) SnapshotStore.breakTimelineContinuity()
            updateCompatibilityForForeground()
        }

        // Pure auto-scroll does not sample or publish page text.
        if (textOverlay.scrollOnlyActive) return

        // Only the supported reading apps are sampled; settings/diagnostics are never captured.
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage !in TARGET_PACKAGES) return

        requestRevision++
        pendingPackage = eventPackage
        pendingEventType = event.eventType
        if (!captureScheduled && !captureInFlight) {
            captureScheduled = true
            mainHandler.postDelayed(captureTask, 100)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectSnapshot(eventPackage: String, eventType: Int): ScreenSnapshot? {
        // Normal capture uses the platform cache. Clearing it for every event forces IPC per node.
        if (!screenIsUsable()) return null
        val started = android.os.SystemClock.elapsedRealtime()
        val root = try { contentRoot() } catch (_: RuntimeException) { null } ?: return null
        return try {
            if (root.packageName?.toString() != eventPackage) return null
            captureSnapshot(root, eventPackage, eventType).let {
                it.copy(traversalDiagnostics = it.traversalDiagnostics +
                    "; 采集耗时ms=${android.os.SystemClock.elapsedRealtime() - started}")
            }
        } catch (_: RuntimeException) {
            null
        } finally { root.recycle() }
    }

    private fun cancelCapture() {
        mainHandler.removeCallbacks(overlayRestoreTask)
        settings.preferences.unregisterOnSharedPreferenceChangeListener(settingsListener)
        cancelScrollWait()
        running = false
        captureGeneration++
        captureInFlight = false
        foregroundCheckPending = false
        mainHandler.removeCallbacks(captureTask)
        captureScheduled = false
        captureHandler.removeCallbacksAndMessages(null)
    }

    override fun onInterrupt() { textOverlay.stopPlayback() }

    override fun onDestroy() {
        unregisterScreenReceiver()
        cancelCapture()
        captureThread.quitSafely()
        textOverlay.hide()
        disableXCompatibility()
        getSystemService(AccessibilityManager::class.java)
            .removeTouchExplorationStateChangeListener(explorationListener)
        AccessibilityServiceState.detach(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        unregisterScreenReceiver()
        cancelCapture()
        textOverlay.hide()
        disableXCompatibility()
        getSystemService(AccessibilityManager::class.java)
            .removeTouchExplorationStateChangeListener(explorationListener)
        AccessibilityServiceState.detach(this)
        return super.onUnbind(intent)
    }

    @Suppress("DEPRECATION")
    private fun captureSnapshot(
        root: AccessibilityNodeInfo,
        eventPackage: String,
        eventType: Int
    ): ScreenSnapshot {
        val nodes = mutableListOf<AccessibilityNodeSnapshot>()
        var depthLimitSkips = 0
        var childLimitSkips = 0
        var unavailableChildren = 0
        var nodeLimitReached = false
        var excludedOffscreenPages = 0
        val reddit = eventPackage == "com.reddit.frontpage"
        val depthLimit = if (reddit) 48 else MAX_DEPTH
        val nodeLimit = if (reddit) 500 else MAX_NODES
        val windowBounds = Rect().also(root::getBoundsInScreen)

        fun visit(node: AccessibilityNodeInfo, depth: Int, parentIndex: Int?, indexInParent: Int) {
            if (depth > depthLimit) {
                depthLimitSkips++
                return
            }
            if (nodes.size >= nodeLimit) {
                nodeLimitReached = true
                return
            }

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            // Reddit's pager retains whole adjacent posts. Exclude only recognized page roots
            // that are both marked invisible and geometrically outside the current window.
            if (reddit && node.viewIdResourceName == "post_detail_scaffold" &&
                !node.isVisibleToUser && !Rect.intersects(windowBounds, bounds)) {
                excludedOffscreenPages++
                return
            }
            val nodeIndex = nodes.size
            val sourceChildCount = node.childCount
            nodes += AccessibilityNodeSnapshot(
                depth = depth,
                text = node.text?.toString().orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
                packageName = node.packageName?.toString().orEmpty(),
                className = node.className?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                boundsInScreen = bounds.toShortString(),
                isScrollable = node.isScrollable,
                childCount = sourceChildCount,
                isDataSensitive = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive,
                parentIndex = parentIndex,
                indexInParent = indexInParent,
                uniqueId = if (Build.VERSION.SDK_INT >= 33) node.uniqueId else null,
                isVisibleToUser = node.isVisibleToUser,
                isClickable = node.isClickable
            )

            val childCount = minOf(sourceChildCount, MAX_CHILDREN_PER_NODE)
            childLimitSkips += (sourceChildCount - childCount).coerceAtLeast(0)
            for (index in 0 until childCount) {
                if (nodes.size >= nodeLimit) {
                    nodeLimitReached = true
                    break
                }
                val child = try {
                    node.getChild(index)
                } catch (_: RuntimeException) {
                    null
                }
                if (child == null) {
                    unavailableChildren++
                    continue
                }

                try {
                    visit(child, depth + 1, nodeIndex, index)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, 0, null, 0)
        val touchExplorationEnabled = getSystemService(AccessibilityManager::class.java)
            .isTouchExplorationEnabled
        val feedback = AccessibilityServiceInfo.feedbackTypeToString(serviceInfo.feedbackType)
        return ScreenSnapshot(
            packageName = eventPackage,
            eventType = AccessibilityEvent.eventTypeToString(eventType),
            capturedAtMillis = System.currentTimeMillis(),
            nodes = nodes,
            windowId = root.windowId,
            isTraversalComplete = depthLimitSkips == 0 && childLimitSkips == 0 &&
                unavailableChildren == 0 && !nodeLimitReached,
            traversalDiagnostics = "触摸探索=$touchExplorationEnabled; X兼容模式=$xCompatibilityEnabled; 直通运行=$passthroughActive; 反馈=$feedback; " +
                "清缓存=未请求; 采集范围=${if (reddit) "当前页及公共容器" else "窗口树"}; " +
                "排除离屏详情页=$excludedOffscreenPages; 深度上限=$depthLimit; 节点上限=$nodeLimit; " +
                "最大深度=${nodes.maxOfOrNull { it.depth } ?: 0}; " +
                "深度截断=$depthLimitSkips; 子节点截断=$childLimitSkips; " +
                "节点总数截断=$nodeLimitReached; 无法取得子节点=$unavailableChildren"
        )
    }

    private companion object {
        const val X_PACKAGE = "com.twitter.android"
        const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        val TARGET_PACKAGES = setOf("com.reddit.frontpage", X_PACKAGE, TELEGRAM_PACKAGE)
        const val MAX_DEPTH = 32
        const val MAX_NODES = 300
        const val MAX_CHILDREN_PER_NODE = 100
    }
}

object AccessibilityServiceState {
    fun restoreTextOverlay() { service?.restoreTextOverlay() }
    fun previewSpeech(): Boolean = service?.previewSpeech() == true
    fun playbackSummary(): String = service?.playbackSummary() ?: "服务未连接"
    @Volatile
    private var service: AccessibilityReaderService? = null
    private val listeners = mutableSetOf<() -> Unit>()

    val connected: Boolean get() = service != null

    internal fun attach(service: AccessibilityReaderService) {
        this.service = service
        notifyChanged()
    }

    internal fun detach(service: AccessibilityReaderService) {
        if (this.service === service) {
            this.service = null
            notifyChanged()
        }
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    val textOverlayVisible: Boolean get() = service?.textOverlayVisible == true
    fun toggleTextOverlay() { service?.toggleTextOverlay() }
    val compatibilityEnabled: Boolean get() = service?.xCompatibilityEnabled == true
    val compatibilityStatus: String get() = service?.compatibilityStatus ?: "兼容模式不可用：服务未连接"
    fun enableCompatibility() { service?.enableXCompatibility() }
    fun disableCompatibility() { service?.disableXCompatibility() }
    internal fun notifyChanged() { listeners.toList().forEach { it() } }
}
