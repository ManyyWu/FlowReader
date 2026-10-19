package com.flowreader.diagnostics

data class AccessibilityNodeSnapshot(
    val depth: Int,
    val text: String,
    val contentDescription: String,
    val packageName: String,
    val className: String,
    val viewId: String,
    val boundsInScreen: String,
    val isScrollable: Boolean,
    val childCount: Int,
    val isDataSensitive: Boolean,
    val parentIndex: Int? = null,
    val indexInParent: Int = 0,
    val uniqueId: String? = null,
    val isVisibleToUser: Boolean = true,
    val isClickable: Boolean = false
)

data class ScreenSnapshot(
    val packageName: String,
    val eventType: String,
    val capturedAtMillis: Long,
    val nodes: List<AccessibilityNodeSnapshot>,
    val traversalDiagnostics: String,
    val windowId: Int = -1,
    val isTraversalComplete: Boolean = true
) {
    fun formatForDisplay(): String = buildString {
        appendLine("包名：$packageName")
        appendLine("事件：$eventType · 节点数：${nodes.size}")
        appendLine("采集时间：$capturedAtMillis")
        appendLine("遍历诊断：$traversalDiagnostics")

        nodes.take(MAX_DISPLAYED_NODES).forEachIndexed { index, node ->
            val label = node.text.ifBlank { node.contentDescription }
                .ifBlank { "（无文本）" }
                .replace('\n', ' ')
            val scrollMarker = if (node.isScrollable) " [可滚动]" else ""
            val description = if (node.text.isNotBlank() &&
                node.contentDescription.isNotBlank() && node.contentDescription != node.text
            ) " 描述=${node.contentDescription.replace('\n', ' ')}" else ""
            appendLine(
                "  ".repeat(node.depth.coerceAtMost(MAX_DISPLAY_DEPTH)) +
                    "[$label] ${node.className} ${node.boundsInScreen} " +
                    "${node.packageName} ${node.viewId}$scrollMarker" +
                    " index=$index parent=${node.parentIndex} slot=${node.indexInParent}" +
                    " depth=${node.depth} children=${node.childCount}" +
                    " uniqueId=${node.uniqueId} visible=${node.isVisibleToUser} clickable=${node.isClickable}" +
                    " sensitive=${node.isDataSensitive}" +
                    " textLength=${node.text.length} descriptionLength=${node.contentDescription.length}$description"
            )
        }

        if (nodes.size > MAX_DISPLAYED_NODES) {
            appendLine("…其余 ${nodes.size - MAX_DISPLAYED_NODES} 个节点未显示")
        }
    }

    private companion object {
        const val MAX_DISPLAYED_NODES = 200
        const val MAX_DISPLAY_DEPTH = 8
    }
}

object SnapshotStore {
    private var filteredBoundaryFrame: ScreenSnapshot? = null

    val readingCache = ReadingSessionCache()
    private var readingFrame: ScreenSnapshot? = null
    private var readingFrameBlocks = emptyList<ReadingBlock>()
    private var readingFrameIds = emptyList<Long?>()
    @Synchronized fun adoptReadingView() {
        val frame = latest ?: return
        readingCache.adopt(latestDisplay.readingBlocks, latestDisplay.readingBlocks.map { frame.nodes.getOrNull(it.nodeIndex)?.boundsInScreen })
        readingFrame = frame; readingFrameBlocks = latestDisplay.readingBlocks; readingFrameIds = readingCache.visibleIds.toList()
    }
    @Volatile var continuationFailure: ContinuationFailure? = null
        private set
    @Synchronized fun recordContinuationFailure(before: ScreenSnapshot?, read: List<ReadingBlock>,
        after: ScreenSnapshot?, next: List<ReadingBlock>, reason: String) {
        continuationFailure = ContinuationFailure(before, read.toList(), after, next.toList(), reason)
        listeners.toList().forEach { it() }
    }
    private val timelineContent = TimelineContentStore(maxCards = 10, requirePostBody = true)
    private val detailScope = DetailSessionScope()
    @Volatile var latestDisplay = SnapshotDisplay()
        private set
    @Synchronized fun detailSummary(): String = detailScope.summary()
    @Synchronized fun timelineSummary(): String = timelineContent.summary()
    private var selectionEpoch = 0L
    @Synchronized fun breakTimelineContinuity() {
        timelineContent.breakContinuity("非X窗口事件"); detailScope.clear(); selectionEpoch++
    }
    private val listeners = mutableSetOf<() -> Unit>()
    @Synchronized fun addListener(listener: () -> Unit) { listeners += listener }
    @Synchronized fun removeListener(listener: () -> Unit) { listeners -= listener }

    @Volatile var clearRevision: Long = 0
        private set

    @Volatile
    private var snapshots: List<ScreenSnapshot> = emptyList()

    val latest: ScreenSnapshot?
        get() = snapshots.lastOrNull()

    @Synchronized
    fun clear() {
        readingCache.clear(); readingFrame = null; readingFrameBlocks = emptyList(); readingFrameIds = emptyList()
        continuationFailure = null
        clearRevision++
        selectionEpoch++
        snapshots = emptyList()
        timelineContent.clear()
        detailScope.clear()
        filteredBoundaryFrame = null
        XPostTextExtractor.clearEvidence()
        latestDisplay = SnapshotDisplay()
        listeners.toList().forEach { it() }
    }

    @Synchronized
    fun publish(snapshot: ScreenSnapshot) {
        timelineContent.ingest(snapshot)
        val scope = detailScope.process(snapshot)
        val readingBlocks = if (scope.suppressAll) emptyList() else when (snapshot.packageName) {
            "com.twitter.android" -> detailScope.readingBlocks(snapshot, scope)
            "com.reddit.frontpage" -> RedditTextExtractor.readingBlocks(snapshot, scope.excludedNodes)
            else -> SnapshotTextFormatter.blocks(snapshot, scope.excludedNodes).map { ReadingBlock(it, -1) }
        }
        val visibleBlocks = readingBlocks.map { it.copy(text = ReadableText.withoutLinks(it.text)) }
            .filter { it.text.isNotBlank() }
        val blocks = visibleBlocks.map { it.text }
        val text = blocks.joinToString("\n\n")
        val redditDetail = snapshot.packageName == "com.reddit.frontpage" &&
            snapshot.nodes.any { it.viewId == "post_detail_scaffold" }
        val redditIdentity = if (redditDetail) snapshot.nodes.firstOrNull {
            it.viewId == "post_title" && it.isVisibleToUser && it.text.isNotBlank()
        }?.text?.trim()?.take(512)?.let { "reddit:$it" } else null
        val detailIdentity = redditIdentity ?: detailScope.selectionIdentity
        val detailPage = redditDetail || snapshot.nodes.any { it.viewId == "PostDetail" }
        val layoutSettled = com.flowreader.scroll.ReadingViewportLayout.settled(snapshot, visibleBlocks)
        if (snapshot.isTraversalComplete && layoutSettled && readingCache.observePage("${snapshot.packageName}:${snapshot.windowId}:$detailPage", detailIdentity)) {
            readingFrame = null; readingFrameBlocks = emptyList(); readingFrameIds = emptyList(); filteredBoundaryFrame = null
        }
        if (snapshot.isTraversalComplete && layoutSettled) {
            if (snapshot.packageName == "com.twitter.android") readingCache.excludeKeys(XPostTextExtractor.promotedKeys())
            val before = readingFrame
            val verified = mutableMapOf<Int, Long>()
            var bridge = filteredBoundaryFrame?.let { com.flowreader.scroll.XHiddenCardBoundary.fromFilteredOnly(it, snapshot, visibleBlocks) } ?: false
            if (visibleBlocks.isEmpty() && com.flowreader.scroll.XHiddenCardBoundary.filteredOnly(snapshot)) filteredBoundaryFrame = snapshot
            if (before != null && readingFrameBlocks.isNotEmpty() && visibleBlocks.isNotEmpty()) {
                // Repeated observations of the same stationary body do not erase an established ID.
                if (readingFrameBlocks == visibleBlocks && visibleBlocks.all {
                    before.nodes.getOrNull(it.nodeIndex) == snapshot.nodes.getOrNull(it.nodeIndex)
                }) readingFrameIds.forEachIndexed { index, id -> if (id != null) verified[index] = id }
                XNodeRepresentation.pairs(before, readingFrameBlocks, snapshot, visibleBlocks).forEach { (next, old) ->
                    readingFrameIds.getOrNull(old)?.let { verified[next] = it }
                }
                val basic = com.flowreader.scroll.ContentContinuation.match(readingFrameBlocks, visibleBlocks)
                val matched = if (basic.startIndex == null && !basic.allRead)
                    com.flowreader.scroll.RedditCommentBoundary.knownBodyContinuation(before, readingFrameBlocks, snapshot, visibleBlocks) ?: basic
                    else basic
                val overlap = matched.startIndex ?: if (matched.allRead) visibleBlocks.size else 0
                if (overlap > 0) repeat(overlap) { index ->
                    readingFrameIds.getOrNull(readingFrameBlocks.size - overlap + index)?.let { verified[index] = it }
                }
                if (overlap == 0) {
                    val reverseBasic = com.flowreader.scroll.ContentContinuation.match(visibleBlocks, readingFrameBlocks)
                    val reverse = if (reverseBasic.startIndex == null && !reverseBasic.allRead)
                        com.flowreader.scroll.RedditCommentBoundary.knownBodyContinuation(snapshot, visibleBlocks, before, readingFrameBlocks) ?: reverseBasic
                        else reverseBasic
                    val count = reverse.startIndex ?: if (reverse.allRead) readingFrameBlocks.size else 0
                    if (count > 0) repeat(count) { index ->
                        readingFrameIds.getOrNull(index)?.let { verified[visibleBlocks.size - count + index] = it }
                    }
                }
                bridge = bridge || com.flowreader.scroll.RedditCommentBoundary.bridges(before, readingFrameBlocks, snapshot, visibleBlocks) ||
                    com.flowreader.scroll.XHiddenCardBoundary.bridges(before, readingFrameBlocks, snapshot, visibleBlocks)
            }
            if (readingCache.ingest(visibleBlocks, visibleBlocks.map { snapshot.nodes.getOrNull(it.nodeIndex)?.boundsInScreen }, verified, bridge)) {
                readingFrame = snapshot; readingFrameBlocks = visibleBlocks; readingFrameIds = readingCache.visibleIds.toList()
                filteredBoundaryFrame = null
            }
        } else readingCache.incomplete(visibleBlocks.size)
        // Reddit's title may be recycled offscreen; page-boundary checks use detailIdentity separately.
        val contextIdentity = if (snapshot.packageName == "com.reddit.frontpage") "" else detailIdentity.orEmpty()
        latestDisplay = SnapshotDisplay(text, if (text.isNotBlank()) DisplayStatus.NORMAL else scope.emptyStatus, blocks,
            visibleBlocks, "${snapshot.windowId}:$selectionEpoch:$contextIdentity", detailIdentity)
        val current = snapshots
        val previous = current.lastOrNull()
        snapshots = if (previous?.hasSameVisibleTreeAs(snapshot) == true) {
            current.dropLast(1) + snapshot
        } else {
            (current + snapshot).takeLast(MAX_HISTORY)
        }
        listeners.toList().forEach { it() }
    }

    fun recent(): List<ScreenSnapshot> = snapshots.toList()

    fun formatRecentForDisplay(): String {
        val history = recent()
        if (history.isEmpty()) return ""

        return history.asReversed().mapIndexed { index, snapshot ->
            "样本 ${history.size - index}（最新在前）\n${snapshot.formatForDisplay()}"
        }.joinToString("\n")
    }

    private fun ScreenSnapshot.hasSameVisibleTreeAs(other: ScreenSnapshot): Boolean =
        packageName == other.packageName && nodes == other.nodes

    private const val MAX_HISTORY = 8
}
