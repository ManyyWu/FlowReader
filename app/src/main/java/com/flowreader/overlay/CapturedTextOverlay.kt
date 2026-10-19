package com.flowreader.overlay

import android.accessibilityservice.AccessibilityService
import com.flowreader.speech.TtsSpeaker
import com.flowreader.speech.VisibleTextSelection
import com.flowreader.speech.PlaybackBoundary
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.flowreader.diagnostics.ReadingBlock
import com.flowreader.scroll.ContentContinuation
import com.flowreader.scroll.ContinuousReading
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flowreader.R
import com.flowreader.diagnostics.SnapshotStore
import com.flowreader.diagnostics.DisplayStatus
import kotlin.math.roundToInt

/** One bounded, non-focusable window; only its rectangle consumes touch. */
class CapturedTextOverlay(private val service: AccessibilityService,
    private val onScroll: (String, Int, (com.flowreader.scroll.ScrollResult) -> Unit) -> Unit,
    private val onScrollOnly: (Float, Int, com.flowreader.scroll.ScrollOnlyTarget?,
        (com.flowreader.scroll.ScrollOnlyActionResult) -> Unit) -> Unit,
    private val onCancelScroll: () -> Unit, private val onChanged: () -> Unit) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    var windowError: String? = null
        private set
    private var body: ScrollView? = null
    private var text: TextView? = null
    private var collapsed = true
    private val settings = com.flowreader.settings.AppSettings(service)
    private var panelWidth = settings.panelWidth
    private var panelHeight = settings.panelHeight
    private var ballView: TextView? = null
    private var resizeGrip: View? = null
    private var textPanel: android.widget.FrameLayout? = null
    private var menu: LinearLayout? = null
    private var menuOpen = false
    private var injectedScrollStart = Long.MIN_VALUE
    private var injectedScrollEnd = Long.MIN_VALUE
    fun noteInjectedScroll(durationMs: Long) {
        injectedScrollStart = android.os.SystemClock.uptimeMillis()
        injectedScrollEnd = injectedScrollStart + durationMs
    }
    private var ballX = dp(12)
    private var ballY = dp(100)
    private var docked = false
    private var scrollMode = settings.overlayScrollMode
    private var textToggle: Button? = null
    private var pendingBallTap: Runnable? = null
    private val autoCollapseMenu = Runnable { if (visible && menuOpen) setMenuOpen(false) }
    private fun scheduleMenuCollapse() {
        handler.removeCallbacks(autoCollapseMenu)
        if (menuOpen) handler.postDelayed(autoCollapseMenu, 3000)
    }
    private val autoDock = Runnable {
        if (settings.overlayAutoHide && visible && !menuOpen && nearScreenEdge()) { docked = true; snapBall() }
    }
    private fun scheduleDock() {
        handler.removeCallbacks(autoDock)
        if (settings.overlayAutoHide && !menuOpen) handler.postDelayed(autoDock, 3000)
    }
    private fun nearScreenEdge(): Boolean = ballX <= dp(12) || ballX >= service.resources.displayMetrics.widthPixels - dp(52)
    private fun styleBall() {
        ballView?.alpha = 1f - settings.overlayTransparency / 100f
        ballView?.background = if (docked) GradientDrawable().apply {
            setColor((settings.overlayColor and 0x00ffffff) or 0xff000000.toInt()); cornerRadius = dp(6).toFloat()
        } else FloatingBallDrawable(settings.overlayColor)
    }
    private fun icon(button: Button?, kind: String, label: String) {
        button ?: return
        button.text = ""; button.contentDescription = label; button.tooltipText = label
        val color = settings.overlayColor
        val backgroundColor = FloatingBallDrawable.innerRingColor(color)
        button.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(backgroundColor) }
        button.alpha = 1f - settings.overlayTransparency / 100f
        val luminance = (Color.red(backgroundColor) * 299 + Color.green(backgroundColor) * 587 + Color.blue(backgroundColor) * 114) / 1000
        val symbol = OverlayIconDrawable(kind, if (luminance > 145) Color.rgb(36, 45, 60) else Color.WHITE)
        symbol.setBounds(0, 0, dp(22), dp(22))
        button.setCompoundDrawables(symbol, null, null, null)
    }
    fun refreshPreferences() {
        panelWidth = settings.panelWidth; panelHeight = settings.panelHeight
        speaker?.refreshSpeechRate()
        autoEnabled = settings.autoContinue
        if (!settings.overlayAutoHide) docked = false
        scheduleDock()
        styleBall(); updatePlaybackControls()
        if (visible) { clampPosition(); updateWindow() }
    }

    private fun setMenuOpen(open: Boolean) {
        menuOpen = open
        if (open) { docked = false; handler.removeCallbacks(autoDock) } else scheduleDock()
        scheduleMenuCollapse()
        layoutController()
    }

    private fun toggleTextPanel() {
        collapsed = !collapsed
        if (!collapsed) renderText(force = true)
        textPanel?.visibility = if (collapsed) View.GONE else View.VISIBLE
        icon(textToggle, "text", if (collapsed) "显示文本" else "隐藏文本")
        clampPosition(); updateWindow()
    }

    private fun toggleCurrentMode() {
        if (scrollMode) {
            if (scrollOnly) stopPlayback()
            toggleScrollOnly()
        } else {
            if (!playing && SnapshotStore.latestDisplay.blocks.isEmpty()) {
                android.widget.Toast.makeText(service,
                    "当前为朗读模式，尚未识别到正文。若要纯滚屏，请点击模式图标切换为双向箭头。",
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }
            if (speaker?.ready != true && !playing) {
                android.widget.Toast.makeText(service, "语音引擎尚未就绪，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            renderText(force = true)
            toggleReading()
        }
    }

    private fun layoutController() {
        val panel = root ?: return
        val list = menu ?: return
        val ball = ballView ?: return
        val metrics = service.resources.displayMetrics
        val menuHeight = dp(4 * 48)
        val up = ballY + dp(20) > metrics.heightPixels / 2
        val ballWidth = dp(if (docked) 12 else 40)
        val ballLayout = LinearLayout.LayoutParams(ballWidth, dp(40))
        if (ball.parent == null) panel.addView(ball, ballLayout) else ball.layoutParams = ballLayout
        if (menuOpen) {
            val index = if (up) 0 else 1
            if (list.parent !== panel || panel.indexOfChild(list) != index) {
                if (list.parent === panel) panel.removeView(list)
                panel.addView(list, index, LinearLayout.LayoutParams(dp(40), menuHeight))
            } else list.layoutParams = LinearLayout.LayoutParams(dp(40), menuHeight)
        } else if (list.parent === panel) panel.removeView(list)
        params.width = ballWidth
        params.height = dp(40) + if (menuOpen) menuHeight else 0
        params.x = if (docked && ballX > metrics.widthPixels / 2) metrics.widthPixels - ballWidth else ballX
        params.y = ballY - if (menuOpen && up) menuHeight else 0
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
        styleBall()
        if (panel.isAttachedToWindow) manager.updateViewLayout(panel, params)
    }

    private var interacting = false
    private val releaseInteraction = Runnable {
        interacting = false
        if (visible) renderText()
    }
    private var lastText: String? = null
    private var displayedContent = ""
    private var displayedEntryIds = emptyList<Long?>()
    private var displayingCachedQueue = false
    private val selection = VisibleTextSelection()
    private val playbackBoundary = PlaybackBoundary()
    private var displayedPackage: String? = null
    private var playbackStatus: TextView? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var scrollOnlyButton: Button? = null
    private var scrollOnly = false
    private var scrollOnlyPaused = false
    private var scrollOnlyGeneration = 0L
    private var scrollOnlyTarget: com.flowreader.scroll.ScrollOnlyTarget? = null
    private var scrollOnlyRequesting = false
    private var waitingForScroll = false
    private var scrollStatus: String? = null
    private var speaker: TtsSpeaker? = null
    private var emptyAutoScreens = 0
    private var autoEnabled = settings.autoContinue
    private var autoWaiting = false
    private var autoPaused = false
    private val continuous = ContinuousReading()
    private var cycleBlocks = emptyList<ReadingBlock>()
    private var cycleSnapshot: com.flowreader.diagnostics.ScreenSnapshot? = null
    private var cyclePackage: String? = null
    private var cycleWindow = -1
    private var cycleDetail = false
    private var cycleIdentity: String? = null
    private var queuedEntries = emptyList<com.flowreader.diagnostics.ReadingSessionCache.Entry>()
    private var earlyScrolledBlock = -1
    private var followAttempts = 0
    private var startingReadingQueue = false
    private var browsingBack = false
    private var lastRenderedHighlight: Int? = null
    private var followRenderGeneration = 0L
    private val earlyScroll = Runnable {
        val tts = speaker
        val index = tts?.currentBlockIndex
        if (tts?.playing == true && index != null && autoEnabled && continuous.active && !autoWaiting && !waitingForScroll &&
            queuedEntries.isNotEmpty() && shouldFollowCurrentBlock(index) && (earlyScrolledBlock != index || followAttempts < 3) &&
            !browsingBack && !menuOpen) {
            if (earlyScrolledBlock != index) { earlyScrolledBlock = index; followAttempts = 0 }
            followAttempts++
            autoWaiting = true; waitingForScroll = true
            requestAutoScroll()
        }
    }

    fun activeReadingNode(): Int? {
        val id = speaker?.currentBlockIndex?.let { queuedEntries.getOrNull(it)?.id } ?: return null
        val index = SnapshotStore.readingCache.visibleIds.indexOf(id)
        return SnapshotStore.latestDisplay.readingBlocks.getOrNull(index)?.nodeIndex
    }

    private fun prepareParagraphStart(index: Int): Boolean {
        if (startingReadingQueue && autoEnabled && !continuous.active) startReadingCycle()
        if (!autoEnabled || !continuous.active || browsingBack || menuOpen) return true
        if (autoWaiting || waitingForScroll) return false
        if (shouldFollowCurrentBlock(index) && (earlyScrolledBlock != index || followAttempts < 3)) {
            handler.removeCallbacks(earlyScroll)
            earlyScroll.run()
            return !autoWaiting
        }
        return true
    }

    private fun shouldFollowCurrentBlock(index: Int): Boolean {
        val snapshot = SnapshotStore.latest ?: return false
        if (!snapshot.isTraversalComplete || !sameCyclePage(snapshot)) return false
        val cache = SnapshotStore.readingCache
        val id = queuedEntries.getOrNull(index)?.id ?: return false
        val visibleIndex = cache.visibleIds.indexOf(id)
        if (visibleIndex < 0) return false
        val block = SnapshotStore.latestDisplay.readingBlocks.getOrNull(visibleIndex) ?: return false
        val node = snapshot.nodes.getOrNull(block.nodeIndex) ?: return false
        // A summary/card opening is an approximate fallback; never use its media height as body height.
        if (node.className != "android.widget.TextView" &&
            !(block.kind == com.flowreader.diagnostics.ReadingBlockKind.POST &&
                com.flowreader.diagnostics.XPostTextExtractor.summaryBody(node.contentDescription) != null) &&
            !(block.kind == com.flowreader.diagnostics.ReadingBlockKind.QUOTE && node.isClickable)) return false
        var ancestor = node.parentIndex
        var viewport: String? = null
        while (ancestor != null) {
            val parent = snapshot.nodes[ancestor]
            if (parent.isScrollable && parent.isVisibleToUser) { viewport = parent.boundsInScreen; break }
            ancestor = parent.parentIndex
        }
        return com.flowreader.scroll.PlaybackViewportFollow.shouldAdvance(node.boundsInScreen, viewport, node.isVisibleToUser)
    }

    private var lastPlaying = false
    private var lastPlaybackBlock: Int? = null
    var readingPackage: String? = null
        private set
    val playing: Boolean get() = autoWaiting || autoPaused || speaker?.let { it.playing || it.paused } == true
    val active: Boolean get() = playing || scrollOnly
    val scrollOnlyActive: Boolean get() = scrollOnly
    fun stopPlayback() {
        val wasScrollOnly = scrollOnly
        scrollOnlyGeneration++
        scrollOnly = false; scrollOnlyPaused = false; scrollOnlyTarget = null
        browsingBack = false
        queuedEntries = emptyList(); SnapshotStore.readingCache.protect(emptySet())
        handler.removeCallbacks(earlyScroll)
        continuous.cancel(); autoWaiting = false; autoPaused = false; waitingForScroll = false
        emptyAutoScreens = 0
        onCancelScroll()
        scrollStatus = null
        speaker?.stop(); readingPackage = null
        scrollStatus = "已停止"
        updatePlaybackControls()
        if (wasScrollOnly && visible) renderText(force = true)
    }

    private fun toggleScrollOnly() {
        if (scrollOnly) { stopPlayback(); return }
        if (!visible) {
            scrollStatus = "请先展开悬浮窗"
            playbackStatus?.text = scrollStatus
            updatePlaybackControls()
            return
        }
        stopPlayback()
        scrollOnly = true; scrollOnlyPaused = false
        val ticket = ++scrollOnlyGeneration
        scrollOnlyTarget = null
        readingPackage = null
        lastText = null
        text?.text = "纯滚屏已启用：不采集正文，不播放语音"
        scrollStatus = "纯滚屏启动中"
        updatePlaybackControls()
        scrollOnlyStep(ticket)
    }

    private fun scrollOnlyStep(ticket: Long) {
        if (!scrollOnly || scrollOnlyPaused || ticket != scrollOnlyGeneration) return
        if (menuOpen || scrollOnlyRequesting) {
            handler.postDelayed({ scrollOnlyStep(ticket) }, 100)
            return
        }
        val startedAt = android.os.SystemClock.uptimeMillis()
        val continuousMode = settings.continuousScroll
        // Keep a steady cadence and preserve fractional screen-percent movement per gesture;
        // rounding each step to a whole percent made low speeds jump, then pause.
        val interval = if (continuousMode) 180 else settings.scrollIntervalMs
        val percent = if (continuousMode)
            settings.scrollSpeedPercentPerSecond * interval / 1000f
        else settings.scrollPercent.toFloat()
        scrollOnlyRequesting = true
        scrollStatus = if (continuousMode) "连续滚屏运行中 · ${settings.scrollSpeedPercentPerSecond}%屏高/秒"
            else "纯滚屏运行中 · ${percent.toInt()}% · 开始间隔 ${interval}毫秒"
        playbackStatus?.text = scrollStatus
        updatePlaybackControls()
        onScrollOnly(percent, interval, scrollOnlyTarget) { result ->
            scrollOnlyRequesting = false
            if (!scrollOnly || scrollOnlyPaused || ticket != scrollOnlyGeneration) return@onScrollOnly
            if (!result.success) {
                scrollOnly = false; scrollOnlyGeneration++; scrollOnlyTarget = null
                scrollStatus = "纯滚屏已停止：${result.message}"
                readingPackage = null
                renderText(force = true)
                updatePlaybackControls()
                return@onScrollOnly
            }
            scrollOnlyTarget = result.target
            readingPackage = result.target?.packageName
            scrollStatus = if (continuousMode) "连续滚屏运行中 · ${settings.scrollSpeedPercentPerSecond}%屏高/秒"
                else "纯滚屏运行中 · ${percent.toInt()}% · 开始间隔 ${interval}毫秒"
            playbackStatus?.text = scrollStatus
            updatePlaybackControls()
            val remaining = (startedAt + interval - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)
            handler.postDelayed({ scrollOnlyStep(ticket) }, remaining)
        }
    }

    fun noteSourceScrolled() {
        // Manual navigation preserves speech; stop/play explicitly selects the current viewport.
    }

    private fun onSpeechBlockCompleted(@Suppress("UNUSED_PARAMETER") blockIndex: Int): Boolean {
        return true
    }

    private fun playSelection() {
        if (scrollOnly) return
        val cache = SnapshotStore.readingCache
        var selected = displayedEntryIds.getOrNull(selection.index)
        if (displayingCachedQueue && selected != null) {
            val values = cache.entries.takeIf { entries -> entries.any { it.id == selected } } ?: queuedEntries
            val start = values.indexOfFirst { it.id == selected }
            if (start >= 0) beginCachedQueue(values.drop(start).take(200))
            return
        }
        if (selected == null || cache.entries.none { it.id == selected }) {
            SnapshotStore.adoptReadingView()
            selected = cache.visibleIds.getOrNull(selection.index)
        }
        val values = cache.entries
        val start = values.indexOfFirst { it.id == selected }
        if (start < 0) return
        beginCachedQueue(values.drop(start).take(200))
    }

    private fun startReadingCycle() {
        cycleBlocks = SnapshotStore.latestDisplay.readingBlocks.toList()
        val current = SnapshotStore.latest
        cycleSnapshot = current
        cyclePackage = current?.packageName; cycleWindow = current?.windowId ?: -1
        cycleDetail = current?.let(::isPostDetail) ?: false
        cycleIdentity = SnapshotStore.latestDisplay.detailIdentity
        continuous.start()
    }

    private fun beginCachedQueue(values: List<com.flowreader.diagnostics.ReadingSessionCache.Entry>, explicit: Boolean = true) {
        continuous.cancel(); autoWaiting = false; autoPaused = false; waitingForScroll = false
        emptyAutoScreens = 0
        onCancelScroll()
        scrollStatus = null
        queuedEntries = values; earlyScrolledBlock = -1
        if (explicit) browsingBack = false
        startingReadingQueue = true
        try { speaker?.playBlocks(values.map { it.block.text }, 0) } finally { startingReadingQueue = false }
        if (speaker?.playing == true) {
            if (autoEnabled && !continuous.active) startReadingCycle()
            readingPackage = displayedPackage; selection.started()
            selection.advancePlaybackTo(speaker?.currentBlockIndex)
            SnapshotStore.latest?.let { snapshot ->
                playbackBoundary.start(snapshot.packageName, snapshot.windowId,
                    isPostDetail(snapshot), SnapshotStore.latestDisplay.detailIdentity)
            }
        }
        renderText(force = true)
    }

    private fun syncCachedQueue() {
        if (queuedEntries.isEmpty()) return
        val cache = SnapshotStore.readingCache
        val values = cache.entries
        val current = speaker?.currentBlockIndex
        if (current != null && autoEnabled && continuous.active && cache.pending.isEmpty()) {
            val merged = com.flowreader.speech.ReadingQueuePlan.merge(queuedEntries, values, current, excluded = cache.excludedIds)
            if (merged.map { it.id } != queuedEntries.map { it.id } &&
                speaker?.replaceFollowingBlocks(merged.drop(current + 1).map { it.block.text }, current + 1) == true) {
                queuedEntries = merged
            }
        }
        val active = speaker?.currentBlockIndex?.let { queuedEntries.getOrNull(it)?.id }
        cache.protect(setOfNotNull(active, queuedEntries.lastOrNull()?.id))
    }

    private fun playCachedTail(): Boolean {
        val last = queuedEntries.lastOrNull()?.id ?: return false
        val values = SnapshotStore.readingCache.entries
        val tail = values.indexOfFirst { it.id == last }
        if (tail < 0 || tail == values.lastIndex) return false
        beginCachedQueue(values.drop(tail + 1).take(200), explicit = false)
        return true
    }

    private fun queueCompleted() {
        if (autoWaiting) return
        if (autoEnabled && continuous.active && playCachedTail()) return
        if (browsingBack && continuous.active) {
            finishAuto("正在手动浏览上文，已读队列完成；请滚回下文或点击正文")
            return
        }
        requestAutoScroll()
    }

    private fun requestAutoScroll(waitStarted: Long = android.os.SystemClock.uptimeMillis()) {
        val ticket = continuous.ticket() ?: return
        handler.post {
            if (!continuous.accepts(ticket)) return@post
            if (!autoEnabled || !visible) { finishAuto("自动续读已暂停"); return@post }
            if (menuOpen) {
                autoWaiting = true; readingPackage = cyclePackage
                scrollStatus = "菜单展开，暂缓自动滚动"
                updatePlaybackControls()
                handler.postDelayed({ if (continuous.accepts(ticket)) requestAutoScroll() }, 180)
                return@post
            }
            val snapshot = SnapshotStore.latest
            if (snapshot == null || !snapshot.isTraversalComplete) {
                finishAuto("当前快照不完整，停止自动续读"); return@post
            }
            if (!sameCyclePage(snapshot)) {
                finishAuto("页面已变化，停止自动续读"); return@post
            }
            if (!com.flowreader.scroll.ReadingViewportLayout.settled(snapshot, SnapshotStore.latestDisplay.readingBlocks)) {
                if (android.os.SystemClock.uptimeMillis() - waitStarted < 5_000) {
                    autoWaiting = true; waitingForScroll = true
                    scrollStatus = "等待页面进入动画结束…"
                    handler.postDelayed({ if (continuous.accepts(ticket)) requestAutoScroll(waitStarted) }, 200)
                } else finishAuto("页面仍处于切换状态，已暂停自动滚动")
                return@post
            }
            if (SnapshotStore.readingCache.pending.isNotEmpty()) {
                if (android.os.SystemClock.uptimeMillis() - waitStarted < 5_000) {
                    autoWaiting = true; waitingForScroll = true
                    scrollStatus = "等待正文顺序稳定…"
                    updatePlaybackControls()
                    handler.postDelayed({ if (continuous.accepts(ticket)) requestAutoScroll(waitStarted) }, 200)
                } else finishAuto("新正文尚未确认衔接，已暂停自动滚动；请点击当前正文继续")
                return@post
            }
            autoWaiting = false; waitingForScroll = false
            if (speaker?.playing != true && speaker?.paused != true && playCachedTail()) return@post
            if (!continuous.beginWait(ticket)) return@post
            autoWaiting = true; waitingForScroll = true
                readingPackage = snapshot.packageName
            playbackBoundary.start(snapshot.packageName, snapshot.windowId,
                isPostDetail(snapshot), SnapshotStore.latestDisplay.detailIdentity)
            scrollStatus = "自动续读：按正文位置滚动并采集…"
            playbackStatus?.text = scrollStatus
            updatePlaybackControls()
            onScroll("自动续读", 50) { result ->
                if (!continuous.completeWait(ticket)) return@onScroll
                autoWaiting = false; waitingForScroll = false
                if (!result.stable) { finishAuto(result.message); return@onScroll }
                syncCachedQueue()
                if (speaker?.playing == true || speaker?.paused == true) {
                    scrollStatus = null; updatePlaybackControls()
                } else continueFromLatest()
            }
        }
    }

    private fun finishAuto(message: String) {
        if (message !in setOf("自动续读已暂停", "连续滚动未发现新正文，已停止")) {
            SnapshotStore.recordContinuationFailure(cycleSnapshot, queuedEntries.map { it.block }.ifEmpty { cycleBlocks }, SnapshotStore.latest,
                SnapshotStore.latestDisplay.readingBlocks, message)
        }
        SnapshotStore.latest?.let { snapshot ->
            if (com.flowreader.diagnostics.ScrollSampling.active) com.flowreader.diagnostics.ScrollSampling.frame(
                snapshot, SnapshotStore.latestDisplay, SnapshotStore.readingCache.entries, playbackSummary() + "\n终止原因=$message", "continuation-failure")
        }
        continuous.cancel(); autoWaiting = false; autoPaused = false; waitingForScroll = false
        scrollStatus = message
        speaker?.releaseIdleAudioFocus()
        updatePlaybackControls()
    }

    private fun sameCyclePage(snapshot: com.flowreader.diagnostics.ScreenSnapshot): Boolean =
        snapshot.packageName == cyclePackage && snapshot.windowId == cycleWindow &&
            isPostDetail(snapshot) == cycleDetail && (cycleIdentity == null ||
                SnapshotStore.latestDisplay.detailIdentity == null ||
                cycleIdentity == SnapshotStore.latestDisplay.detailIdentity)

    private fun continueFromLatest() {
        val snapshot = SnapshotStore.latest
        if (snapshot == null || !snapshot.isTraversalComplete || !sameCyclePage(snapshot)) {
            finishAuto("页面已变化或正文不完整，请手动选择文本"); return
        }
        if (playCachedTail()) return
        if (SnapshotStore.readingCache.pending.isNotEmpty()) {
            requestAutoScroll()
            return
        }
        // Empty/unchanged text can be traversed; unresolved new text must never be skipped.
        if (autoEnabled && continuous.active && !browsingBack) {
            emptyAutoScreens++
            queueCompleted()
        } else finishAuto("自动续读已停止")
    }

    private fun toggleReading() {
        if (playing) stopPlayback()
        val snapshot = SnapshotStore.latest ?: return
        if (!snapshot.isTraversalComplete ||
            !com.flowreader.scroll.ReadingViewportLayout.settled(snapshot, SnapshotStore.latestDisplay.readingBlocks)) {
            scrollStatus = "等待当前页面正文稳定，请稍后播放"; updatePlaybackControls(); return
        }
        val cache = SnapshotStore.readingCache
        if (cache.visibleIds.firstOrNull() == null) SnapshotStore.adoptReadingView()
        val first = cache.visibleIds.firstOrNull() ?: return
        val start = cache.entries.indexOfFirst { it.id == first }
        if (start >= 0) beginCachedQueue(cache.entries.drop(start).take(200))
    }

    private fun updatePlaybackControls() {
        val tts = speaker ?: return
        val sessionActive = active
        if (lastPlaying != sessionActive) {
            lastPlaying = sessionActive
            if (!sessionActive) lastPlaybackBlock = null
            lastText = null
            if (visible && !renderPending) { renderPending = true; handler.post(render) }
        }
        if (!waitingForScroll || autoWaiting) playbackStatus?.text = scrollStatus ?: tts.status
        icon(pauseButton, "play", if (active) "从当前位置重新开始" else "播放")
        pauseButton?.isEnabled = true
        icon(scrollOnlyButton, if (scrollMode) "scroll" else "read", if (scrollMode) "当前纯滚屏模式，点击切换朗读" else "当前朗读模式，点击切换纯滚屏")
        icon(textToggle, "text", if (collapsed) "显示文本" else "隐藏文本")
        icon(stopButton, "stop", "停止并清空播放队列")
        if (tts.playing) {
            syncCachedQueue()
            val block = tts.currentBlockIndex
            selection.advancePlaybackTo(block)
            if (block != lastPlaybackBlock) {
                lastPlaybackBlock = block
                lastText = null
                if (visible && !renderPending) { renderPending = true; handler.post(render) }
            }
        }
        stopButton?.isEnabled = sessionActive || waitingForScroll
        if (!sessionActive) {
            if (tts.status != "朗读完成") continuous.cancel()
            readingPackage = null; selection.stopped(); playbackBoundary.clear()
        }
        onChanged()
        if (tts.playing && queuedEntries.isNotEmpty()) {
            handler.removeCallbacks(earlyScroll); handler.post(earlyScroll)
        }
    }
    fun previewSpeech() {
        if (!visible) show()
        stopPlayback()
        speaker?.play("你好，这是 FlowReader 语音试听。Hello, FlowReader.")
        if (speaker?.playing == true) readingPackage = service.packageName
    }

    fun playbackSummary(): String = "控制模式=${if (scrollMode) "纯滚屏" else "朗读"}；正文块=${SnapshotStore.latestDisplay.blocks.size}；状态=${scrollStatus ?: speaker?.status}; 自动续读=$autoEnabled; 纯滚屏=$scrollOnly; 纯滚屏暂停=$scrollOnlyPaused; 纯滚屏间隔=${settings.scrollIntervalMs}ms; 纯滚屏比例=${settings.scrollPercent}%; 朗读滚动=正文坐标定位，缺少坐标时使用50%基准; " +
        "会话=${continuous.active}; 等待=$autoWaiting; 暂停等待=$autoPaused; 连续已读屏=$emptyAutoScreens\n" +
        "播放队列=${queuedEntries.size}段；播放位置=${speaker?.currentBlockIndex}；上翻浏览=$browsingBack\n" +
        "小窗列表=${if (displayingCachedQueue) "会话缓存" else "当前视口"}；条目=${displayedEntryIds.size}；当前ID=${speaker?.currentBlockIndex?.let { queuedEntries.getOrNull(it)?.id }}\n${speaker?.diagnostics().orEmpty()}"

    val visible: Boolean get() = root != null
    fun occlusionBounds(): android.graphics.Rect? {
        val bounds = listOfNotNull(root, textPanel?.takeIf { !collapsed }).map { view ->
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            android.graphics.Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return bounds.firstOrNull()?.apply { bounds.drop(1).forEach { union(it) } }
    }
    private fun windowParams() = WindowManager.LayoutParams(
        dp(56), dp(56), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private val params = windowParams()
    private val textParams = windowParams().apply { x = dp(20); y = dp(160) }
    private var renderPending = false
    private val render = Runnable { renderPending = false; renderText() }
    private val snapshotListener: () -> Unit = {
        val current = SnapshotStore.latest
        if (playing && current != null && playbackBoundary.changed(current.packageName, current.windowId,
                isPostDetail(current), SnapshotStore.latestDisplay.detailIdentity,
                current.isTraversalComplete)) stopPlayback()
        if (current != null && playing) syncCachedQueue()
        // Clear must stop speech even while the user is dragging the text area.
        if (SnapshotStore.latest == null) {
            selection.update(null, emptyList())
            if (playing) stopPlayback() else updatePlaybackControls()
        }
        if (!renderPending) {
            renderPending = true
            handler.postDelayed(render, 200)
        }
    }

    private fun isPostDetail(snapshot: com.flowreader.diagnostics.ScreenSnapshot): Boolean =
        snapshot.nodes.any { it.viewId == "PostDetail" } ||
            (snapshot.packageName == "com.reddit.frontpage" &&
                snapshot.nodes.any { it.viewId == "post_detail_scaffold" })

    @Suppress("DEPRECATION")
    fun ownsWindow(windowId: Int): Boolean {
        return listOfNotNull(root, textPanel).any { view ->
            val node = view.createAccessibilityNodeInfo()
            try { node.windowId == windowId } finally { node.recycle() }
        }
    }

    fun show() {
        if (visible) return
        collapsed = true; menuOpen = false
        val panel = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        root = panel
        val list = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        menu = list
        fun action(label: String, clicked: () -> Unit): Button = Button(service).apply {
            text = ""; contentDescription = label; minimumWidth = 0; minimumHeight = 0
            setPadding(dp(9), dp(9), dp(9), dp(9)); stateListAnimator = null
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> handler.removeCallbacks(autoCollapseMenu)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleMenuCollapse()
                }
                false
            }
            setOnClickListener { clicked(); scheduleDock(); scheduleMenuCollapse() }
            list.addView(this, LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        }
        pauseButton = action("播放/从当前位置重新开始") { toggleCurrentMode() }
        stopButton = action("停止") { stopPlayback() }
        scrollOnlyButton = action("切换模式") {
            stopPlayback(); scrollMode = !scrollMode; settings.overlayScrollMode = scrollMode; updatePlaybackControls()
            android.widget.Toast.makeText(service, if (scrollMode) "已切换为纯滚屏模式" else "已切换为朗读模式", android.widget.Toast.LENGTH_SHORT).show()
        }
        textToggle = action("显示文本") { toggleTextPanel(); setMenuOpen(false) }
        val ball = TextView(service).apply {
            text = ""
            background = FloatingBallDrawable(settings.overlayColor)
            elevation = dp(6).toFloat()
            contentDescription = "FlowReader 悬浮球，单击菜单，双击从当前位置开始，拖动移动"
            setOnClickListener { docked = false; setMenuOpen(!menuOpen) }
        }
        ballView = ball
        panel.setOnTouchListener { _, event ->
            if (menuOpen && event.actionMasked in setOf(MotionEvent.ACTION_OUTSIDE, MotionEvent.ACTION_DOWN)) {
                // Injected swipes also deliver ACTION_OUTSIDE; they are not a user dismiss.
                if (event.actionMasked != MotionEvent.ACTION_OUTSIDE || (!scrollOnlyRequesting && event.eventTime !in injectedScrollStart..injectedScrollEnd))
                    setMenuOpen(false)
                true
            } else false
        }
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var moved = false
        val slop = android.view.ViewConfiguration.get(service).scaledTouchSlop
        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(autoDock)
                    startX = ballX; startY = ballY; downX = event.rawX; downY = event.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        moved = true; docked = false
                        pendingBallTap?.let(handler::removeCallbacks); pendingBallTap = null
                        menuOpen = false
                        ballX = startX + dx.roundToInt(); ballY = startY + dy.roundToInt()
                        clampPosition(); layoutController()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) { if (nearScreenEdge()) snapBall(); scheduleDock() }
                    else if (pendingBallTap != null) {
                        pendingBallTap?.let(handler::removeCallbacks); pendingBallTap = null
                        setMenuOpen(false); toggleCurrentMode(); scheduleDock()
                    } else {
                        val tap = Runnable { pendingBallTap = null; view.performClick() }
                        pendingBallTap = tap
                        handler.postDelayed(tap, android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pendingBallTap?.let(handler::removeCallbacks); pendingBallTap = null; true
                }
                else -> false
            }
        }
        createTextPanel()
        clampPosition(); layoutController()
        try {
            textPanel?.visibility = View.GONE
            manager.addView(textPanel, textParams)
            manager.addView(panel, params)
            windowError = null
        } catch (error: RuntimeException) {
            windowError = error.javaClass.simpleName; hide(); onChanged(); return
        }
        if (speaker == null) speaker = TtsSpeaker(service,
            onChanged = { updatePlaybackControls() }, onBlockCompleted = ::onSpeechBlockCompleted,
            onQueueCompleted = ::queueCompleted,
            beforeParagraphStart = ::prepareParagraphStart,
            keepAudioBetweenQueues = { autoEnabled && continuous.active },
            onAudioInterrupted = { message ->
                stopPlayback(); scrollStatus = message; updatePlaybackControls()
            })
        SnapshotStore.addListener(snapshotListener)
        renderText(); updatePlaybackControls(); scheduleDock(); onChanged()
    }

    private fun snapBall() {
        val width = service.resources.displayMetrics.widthPixels
        ballX = if (ballX + dp(20) < width / 2) 0 else width - dp(40)
        layoutController()
    }

    private fun createTextPanel() {
        val frame = android.widget.FrameLayout(service).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 250, 249, 246)); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(110, 120, 135))
            }
            elevation = dp(6).toFloat()
        }
        textPanel = frame
        val column = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val title = TextView(service).apply {
            text = "FlowReader · 拖动移动"; textSize = 13f; setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleRow = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
        titleRow.addView(Button(service).apply {
            text = "×"; textSize = 22f; minimumWidth = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0); setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.DKGRAY); contentDescription = "关闭文本小窗"
            setOnClickListener { if (!collapsed) toggleTextPanel() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        column.addView(titleRow, LinearLayout.LayoutParams(-1, dp(36)))
        playbackStatus = TextView(service).apply { textSize = 12f; setTextColor(Color.DKGRAY) }
        column.addView(playbackStatus, LinearLayout.LayoutParams(-1, dp(32)))
        val content = TextView(service).apply {
            textSize = 14f; setTextColor(Color.rgb(28, 32, 38)); setPadding(dp(6), dp(6), dp(6), dp(20))
            movementMethod = LinkMovementMethod.getInstance()
        }
        text = content
        val scroll = ScrollView(service).apply {
            addView(content)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { handler.removeCallbacks(releaseInteraction); interacting = true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.postDelayed(releaseInteraction, 800)
                }
                false
            }
        }
        body = scroll
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(column, android.widget.FrameLayout.LayoutParams(-1, -1))
        val grip = object : View(service) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(90, 98, 110); strokeWidth = dp(4).toFloat(); style = android.graphics.Paint.Style.STROKE
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                val edge = dp(2).toFloat()
                val radius = dp(12).toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(edge, edge); lineTo(edge, height - radius)
                    quadTo(edge, height - edge, radius, height - edge)
                    lineTo(width - edge, height - edge)
                }
                canvas.drawPath(path, paint)
            }
        }.apply { contentDescription = "拖动左下角边框调整文本小窗大小" }
        resizeGrip = grip
        frame.addView(grip, android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.LEFT))
        var resizeX = 0f; var resizeY = 0f; var widthAtDown = 0; var heightAtDown = 0; var leftAtDown = 0
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { resizeX = event.rawX; resizeY = event.rawY; widthAtDown = panelWidth; heightAtDown = panelHeight; leftAtDown = textParams.x; true }
                MotionEvent.ACTION_MOVE -> {
                    val density = service.resources.displayMetrics.density
                    panelWidth = (widthAtDown - (event.rawX - resizeX) / density).roundToInt().coerceIn(280, 800)
                    textParams.x = leftAtDown + dp(widthAtDown - panelWidth)
                    panelHeight = (heightAtDown + (event.rawY - resizeY) / density).roundToInt().coerceIn(320, 1000)
                    clampPosition(); updateWindow(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { settings.savePanelSize(panelWidth, panelHeight); true }
                else -> false
            }
        }
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = textParams.x; startY = textParams.y; downX = event.rawX; downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    textParams.x = startX + (event.rawX - downX).roundToInt(); textParams.y = startY + (event.rawY - downY).roundToInt()
                    clampPosition(); updateWindow(); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    fun hide() {
        queuedEntries = emptyList(); SnapshotStore.readingCache.protect(emptySet())
        handler.removeCallbacks(earlyScroll)
        continuous.cancel(); autoWaiting = false; autoPaused = false; waitingForScroll = false
        emptyAutoScreens = 0
        onCancelScroll()
        scrollOnlyGeneration++; scrollOnly = false; scrollOnlyPaused = false
        scrollStatus = null
        speaker?.close()
        speaker = null
        readingPackage = null
        playbackStatus = null; pauseButton = null; stopButton = null; scrollOnlyButton = null
        pendingBallTap?.let(handler::removeCallbacks); pendingBallTap = null
        handler.removeCallbacks(autoDock)
        handler.removeCallbacks(autoCollapseMenu)
        SnapshotStore.removeListener(snapshotListener)
        handler.removeCallbacks(render)
        handler.removeCallbacks(releaseInteraction)
        interacting = false
        renderPending = false
        val panel = root
        val oldTextPanel = textPanel
        root = null; body = null; text = null; lastText = null
        textPanel = null; menu = null; textToggle = null
        ballView = null; resizeGrip = null
        if (oldTextPanel != null && oldTextPanel.isAttachedToWindow) runCatching { manager.removeViewImmediate(oldTextPanel) }
        selection.update(null, emptyList())
        playbackBoundary.clear()
        if (panel != null) {
            try { manager.removeViewImmediate(panel) } catch (_: RuntimeException) { }
            onChanged()
        }
    }

    fun onConfigurationChanged() {
        if (!visible) return
        clampPosition(); updateWindow()
    }

    private fun renderText(force: Boolean = false) {
        if (interacting && !force) return
        val snapshot = SnapshotStore.latest
        displayedPackage = snapshot?.packageName
        val display = SnapshotStore.latestDisplay
        val activeId = speaker?.currentBlockIndex?.let { queuedEntries.getOrNull(it)?.id }
        val cacheEntries = SnapshotStore.readingCache.entries
        displayingCachedQueue = playing && queuedEntries.isNotEmpty()
        val shownEntries = if (displayingCachedQueue) {
            if (cacheEntries.any { it.id == activeId } || activeId == null) cacheEntries else queuedEntries
        } else emptyList()
        val blocks = if (displayingCachedQueue) shownEntries.map { it.block.text } else display.blocks
        displayedEntryIds = if (displayingCachedQueue) shownEntries.map { it.id } else SnapshotStore.readingCache.visibleIds.toList()
        displayedContent = if (displayingCachedQueue) blocks.joinToString("\n\n") else display.text
        val keys = if (displayingCachedQueue) shownEntries.map { "cache:${it.id}" } else display.readingBlocks.map { it.key }
        val selectionChanged = selection.update(displayedPackage, blocks, keys,
            display.selectionContext + if (displayingCachedQueue) ":cached" else ":visible")
        if (snapshot == null && playing) stopPlayback()
        val value = displayedContent.ifBlank {
            service.getString(when {
                SnapshotStore.latestDisplay.status == DisplayStatus.RECOMMENDATIONS_HIDDEN -> R.string.recommendations_hidden
                SnapshotStore.latestDisplay.status == DisplayStatus.DETAIL_SCOPE_UNKNOWN -> R.string.detail_scope_unknown
                snapshot?.packageName == "com.twitter.android" -> R.string.snapshot_body_unavailable
                else -> R.string.snapshot_empty
            })
        }
        val highlighted = if (playing && queuedEntries.isNotEmpty())
            displayedEntryIds.withIndex().filter { it.value != null && it.value == activeId }.singleOrNull()?.index
            else selection.highlightedIndex(playing)
        if (value == lastText && !selectionChanged && !force && highlighted == lastRenderedHighlight) { updatePlaybackControls(); return }
        lastRenderedHighlight = highlighted
        lastText = value
        updatePlaybackControls()
        val position = body?.scrollY ?: 0
        val styled = SpannableString(value)
        var offset = 0
        var activeRange: IntRange? = null
        selection.blocks.forEachIndexed { index, block ->
            val start = offset
            val end = start + block.length
            styled.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    selection.tap(index, android.os.SystemClock.uptimeMillis(),
                        android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
                    playSelection()
                }
                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (index == highlighted) {
                styled.setSpan(BackgroundColorSpan(Color.argb(65, 59, 130, 246)),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (end > start) activeRange = start until end
            }
            offset = end + 2
        }
        text?.text = styled
        body?.scrollTo(0, position)
        followPlayback(activeRange)
    }

    /** Wait for the updated text layout; stale renders must not scroll a later selection. */
    private fun followPlayback(range: IntRange?) {
        val generation = ++followRenderGeneration
        val scroll = body ?: return
        val content = text ?: return
        if (range == null || collapsed || speaker?.let { it.playing || it.paused } != true) return
        val observer = content.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                if (generation != followRenderGeneration || body !== scroll || text !== content ||
                    collapsed || interacting || speaker?.let { it.playing || it.paused } != true) return true
                val layout = content.layout ?: return true
                if (range.last >= content.length()) return true
                val top = content.top + content.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(range.first))
                val bottom = content.top + content.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset(range.last))
                val viewport = (scroll.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(0)
                if (viewport == 0) return true
                // A long block cannot fit: keep its opening lines visible instead of jumping into its middle.
                val blockHeight = bottom - top
                val shownHeight = if (blockHeight <= viewport) blockHeight else viewport / 2
                val target = top + shownHeight / 2 - viewport / 2 - scroll.paddingTop
                val maximum = (content.bottom + scroll.paddingBottom - scroll.height).coerceAtLeast(0)
                scroll.scrollTo(0, target.coerceIn(0, maximum))
                return true
            }
        })
        content.invalidate()
    }

    private fun clampPosition() {
        val metrics = service.resources.displayMetrics
        ballX = ballX.coerceIn(0, (metrics.widthPixels - dp(40)).coerceAtLeast(0))
        ballY = ballY.coerceIn(0, (metrics.heightPixels - dp(40)).coerceAtLeast(0))
        textParams.width = minOf(dp(panelWidth), metrics.widthPixels)
        textParams.height = minOf(dp(panelHeight), metrics.heightPixels)
        textParams.x = textParams.x.coerceIn(0, (metrics.widthPixels - textParams.width).coerceAtLeast(0))
        textParams.y = textParams.y.coerceIn(0, (metrics.heightPixels - textParams.height).coerceAtLeast(0))
    }

    private fun updateWindow() {
        try {
            layoutController()
            textPanel?.takeIf { it.isAttachedToWindow }?.let { manager.updateViewLayout(it, textParams) }
        } catch (_: RuntimeException) { hide() }
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()
}
