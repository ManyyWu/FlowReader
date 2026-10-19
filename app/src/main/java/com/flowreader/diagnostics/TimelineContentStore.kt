package com.flowreader.diagnostics

/** Immutable, local-indexed subtree. Never holds live AccessibilityNodeInfo objects. */
data class CardObservation(
    val capturedAtMillis: Long,
    val nodes: List<AccessibilityNodeSnapshot>,
    val quoteRootIndices: List<Int>
) {
    val characterCount: Int get() = nodes.sumOf {
        it.text.length + it.contentDescription.length + it.className.length + it.viewId.length +
            it.packageName.length + it.boundsInScreen.length + (it.uniqueId?.length ?: 0)
    }
    val textSize: Int get() = nodes.sumOf { it.text.length + it.contentDescription.length }

    fun sameContentAs(other: CardObservation): Boolean = nodes.size == other.nodes.size &&
        nodes.indices.all { i ->
            val a = nodes[i]; val b = other.nodes[i]
            a.text == b.text && a.contentDescription == b.contentDescription && a.className == b.className &&
                a.viewId == b.viewId && a.parentIndex == b.parentIndex && a.indexInParent == b.indexInParent
        } && quoteRootIndices == other.quoteRootIndices
}

data class StoredTimelineCard(
    val localId: Long,
    val observations: List<CardObservation>,
    val lastSeenSequence: Long
) {
    val richestObservation: CardObservation get() = observations.maxBy { it.textSize }
    val latestObservation: CardObservation get() = observations.last()
}

/** Candidate cache, not a source of verified post IDs or a playback queue. Main-thread owned. */
class TimelineContentStore(
    private val maxCards: Int = 100,
    private val maxCharacters: Int = 200_000,
    private val maxNodes: Int = 6_000,
    private val maxObservationsPerCard: Int = 4,
    private val requirePostBody: Boolean = false,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    init { require(maxCards > 0 && maxCharacters > 0 && maxNodes > 0 && maxObservationsPerCard >= 2) }
    private val entries = linkedMapOf<Long, StoredTimelineCard>()
    private var previous: TimelineCardSample? = null
    private var previousNodes: List<AccessibilityNodeSnapshot>? = null
    private var previousIds = emptyMap<Int, Long>()
    private var incompleteSince: Long? = null
    private data class Anchor(val key: String, val id: Long)
    private val recentFrames = ArrayDeque<List<Anchor>>()
    private val diagnosticCounts = linkedMapOf<String, Int>()
    private val diagnosticEvents = ArrayDeque<String>()
    private fun diagnose(reason: String) {
        diagnosticCounts[reason] = (diagnosticCounts[reason] ?: 0) + 1
        diagnosticEvents.addLast("采样$sequence：$reason")
        while (diagnosticEvents.size > 20) diagnosticEvents.removeFirst()
    }
    private var sequence = 0L
    private var nextId = 1L
    var visibleIds: Set<Long> = emptySet()
        private set
    var lastResult = "尚未采集时间线"
        private set
    var evictedCount = 0
        private set
    var rejectedCount = 0
        private set
    var historicalRecoveryCount = 0
        private set
    private var pinnedIds = emptySet<Long>()

    fun cards(): List<StoredTimelineCard> = entries.values.toList()
    fun pin(ids: Set<Long>) { pinnedIds = ids.filterTo(mutableSetOf()) { it in entries } }
    fun breakContinuity(reason: String = "显式中断") {
        if (previous != null || recentFrames.isNotEmpty()) diagnose("连续性中断/$reason")
        previous = null; previousNodes = null; previousIds = emptyMap(); visibleIds = emptySet()
        recentFrames.clear()
        incompleteSince = null
    }
    fun clear() {
        entries.clear(); breakContinuity(); pinnedIds = emptySet()
        evictedCount = 0; rejectedCount = 0; historicalRecoveryCount = 0; lastResult = "缓存已清除"
        diagnosticCounts.clear(); diagnosticEvents.clear()
    }

    fun ingest(snapshot: ScreenSnapshot) {
        val now = monotonicMillis()
        if (incompleteSince?.let { now - it !in 0..2_000L } == true) {
            breakContinuity("不完整采样等待超时")
        }
        if (!snapshot.isTraversalComplete) {
            val sameTimeline = snapshot.packageName == "com.twitter.android" && snapshot.windowId >= 0 &&
                previous?.windowId == snapshot.windowId &&
                snapshot.nodes.count { it.viewId == "scaffold_home_tabbed" } == 1 &&
                snapshot.nodes.none { it.viewId == "PostDetail" }
            if (sameTimeline) {
                if (incompleteSince == null) incompleteSince = now
                // Never learn, assign IDs or replace cached nodes from a partial tree.
                previousNodes = null
                visibleIds = emptySet()
                diagnose("不完整采样/暂留证据")
                lastResult = "不完整采样跳过学习，等待同窗口完整时间线（最多2秒）"
                return
            }
        }
        val analyzed = XTimelineCards.analyze(snapshot)
        val sample = if (requirePostBody) analyzed.copy(cards = analyzed.cards.filter {
            XPostTextExtractor.summaryBody(it.summary) != null
        }) else analyzed
        if (sample.containerIndex == null || sample.cards.isEmpty()) {
            breakContinuity(if (!snapshot.isTraversalComplete) "不完整采样" else sample.reason); lastResult = "本屏未累积：" +
                if (sample.containerIndex != null && requirePostBody) "未识别到推文正文" else sample.reason
            return
        }
        if (previous?.windowId == sample.windowId && previousNodes == snapshot.nodes) return
        if (previous != null && previous?.windowId != sample.windowId) breakContinuity("窗口改变")
        if (incompleteSince != null) {
            diagnose("不完整采样/完整时间线恢复")
            incompleteSince = null
        }
        sequence++
        val matches = previous?.let { XTimelineCards.matchAdjacent(it, sample) }.orEmpty()
        val matchedIds = matches.mapNotNull { match ->
            previousIds[match.previousRootIndex]?.takeIf { it in entries }?.let { match.currentRootIndex to it }
        }.toMap().toMutableMap()
        val recovered = recoverPairs(sample, matchedIds)
        matchedIds.putAll(recovered)
        val updated = LinkedHashMap(entries)
        val assignments = mutableMapOf<Int, Long>()
        sample.cards.forEach { candidate ->
            val id = matchedIds[candidate.rootIndex] ?: nextId++
            val observation = observation(snapshot, candidate)
            val old = updated[id]
            val versions = mergeObservations(old?.observations.orEmpty(), observation)
            updated[id] = StoredTimelineCard(id, versions, sequence)
            assignments[candidate.rootIndex] = id
        }
        val protected = assignments.values.toSet() + pinnedIds
        var removed = 0
        while (overBudget(updated)) {
            val victim = updated.values.filter { it.localId !in protected }
                .minByOrNull { it.lastSeenSequence }
            if (victim == null) {
                // Commit nothing if the visible/pinned working set itself exceeds the limit.
                rejectedCount++; breakContinuity("容量拒绝")
                lastResult = "容量不足，本屏暂停累积；既有内容保留"
                return
            }
            updated.remove(victim.localId); removed++
        }
        entries.clear(); entries.putAll(updated)
        evictedCount += removed
        historicalRecoveryCount += recovered.size
        previous = sample; previousNodes = snapshot.nodes; previousIds = assignments; visibleIds = assignments.values.toSet()
        val anchors = sample.cards.map { Anchor(historyKey(it.summary), assignments.getValue(it.rootIndex)) }
        // Geometry, counters and child-node updates must not consume another history slot.
        // Refresh recency of an identical ordered frame, preserving distinct IDs and order.
        recentFrames.remove(anchors)
        recentFrames.addLast(anchors)
        while (recentFrames.size > 10) {
            recentFrames.removeFirst()
            diagnose("历史帧达到上限/移除最旧帧")
        }
        if (recovered.isNotEmpty()) diagnose("历史恢复${recovered.size}项")
        lastResult = "本屏候选${sample.cards.size}项，匹配${matchedIds.size}项（历史恢复${recovered.size}项），新增${sample.cards.size - matchedIds.size}项"
    }

    private fun historyKey(summary: String): String {
        val (recognized, key) = XPostTextExtractor.adjacentMatchKey(summary)
        if (!recognized) return "" // Unknown grammar is never historical evidence.
        return java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Require a previously observed adjacent pair, unique retained IDs and uninterrupted page continuity. */
    private fun recoverPairs(sample: TimelineCardSample, adjacent: Map<Int, Long>): Map<Int, Long> {
        if (sample.cards.all { it.rootIndex in adjacent }) return emptyMap()
        if (sample.windowId < 0 || previous?.windowId != sample.windowId) {
            diagnose("未恢复/无连续前帧"); return emptyMap()
        }
        if (sample.cards.size < 2) { diagnose("未恢复/仅单项可见"); return emptyMap() }
        val current = sample.cards.map { historyKey(it.summary) }
        val counts = current.groupingBy { it }.eachCount()
        val ids = recentFrames.flatten().filter { it.id in entries && it.key.isNotEmpty() }
            .groupBy { it.key }.mapValues { (_, anchors) -> anchors.map { it.id }.toSet() }
        val proposed = mutableMapOf<Int, Long>()
        (0 until sample.cards.size - 1).forEach { i ->
            if (sample.cards[i].rootIndex in adjacent && sample.cards[i + 1].rootIndex in adjacent) return@forEach
            val a = current[i]; val b = current[i + 1]
            if (a.isEmpty() || b.isEmpty()) { diagnose("未恢复/摘要语法未知"); return@forEach }
            if (counts[a] != 1 || counts[b] != 1) { diagnose("未恢复/本屏键重复"); return@forEach }
            if (ids[a].isNullOrEmpty() || ids[b].isNullOrEmpty()) {
                val retainedKeys = entries.values.flatMap { card -> card.latestObservation.nodes.mapNotNull {
                    it.contentDescription.takeIf { text -> XPostTextExtractor.summaryBody(text) != null }?.let(::historyKey)
                } }.toSet()
                diagnose(if (a in retainedKeys && b in retainedKeys) "未恢复/缓存仍在但历史帧缺锚点"
                    else "未恢复/缺少保留条目证据")
                return@forEach
            }
            val left = ids[a]?.singleOrNull()
            val right = ids[b]?.singleOrNull()
            if (left == null || right == null) { diagnose("未恢复/历史键对应多个ID"); return@forEach }
            if (left == right || recentFrames.none { frame -> frame.zipWithNext().any {
                (x, y) -> x.key == a && x.id == left && y.key == b && y.id == right
            } }) { diagnose("未恢复/未记录同序相邻对"); return@forEach }
            val pair = mapOf(sample.cards[i].rootIndex to left, sample.cards[i + 1].rootIndex to right)
            if (pair.any { (root, id) -> adjacent[root]?.let { it != id } == true ||
                    adjacent.any { it.key != root && it.value == id } }) {
                diagnose("未恢复/与相邻匹配冲突"); return@forEach
            }
            proposed.putAll(pair)
        }
        return proposed.filterKeys { it !in adjacent }
    }

    private fun observation(snapshot: ScreenSnapshot, card: TimelineCardCandidate): CardObservation {
        val local = card.nodeIndices.withIndex().associate { it.value to it.index }
        val depth = snapshot.nodes[card.rootIndex].depth
        val nodes = card.nodeIndices.map { source ->
            val node = snapshot.nodes[source]
            node.copy(parentIndex = if (source == card.rootIndex) null else local[node.parentIndex],
                depth = node.depth - depth, indexInParent = if (source == card.rootIndex) 0 else node.indexInParent)
        }
        return CardObservation(snapshot.capturedAtMillis, nodes, card.quoteRootIndices.mapNotNull(local::get))
    }

    private fun mergeObservations(old: List<CardObservation>, current: CardObservation): List<CardObservation> {
        val versions = old.filterNot { it.sameContentAs(current) }.toMutableList()
        versions += current
        while (versions.size > maxObservationsPerCard) {
            val richest = versions.indices.maxBy { versions[it].textSize }
            val victim = versions.indices.first { it != richest && it != versions.lastIndex }
            versions.removeAt(victim)
        }
        return versions.toList()
    }

    private fun overBudget(cards: Map<Long, StoredTimelineCard>): Boolean =
        cards.size > maxCards || cards.values.sumOf { card -> card.observations.sumOf { it.characterCount.toLong() } } > maxCharacters ||
            cards.values.sumOf { card -> card.observations.sumOf { it.nodes.size.toLong() } } > maxNodes

    fun summary(): String = buildString {
        val label = if (requirePostBody) "时间线推文观测缓存" else "时间线候选缓存"
        appendLine("$label：${entries.size}/$maxCards 项；当前屏=${visibleIds.size}项")
        appendLine("字符=${entries.values.sumOf { c -> c.observations.sumOf { it.characterCount } }}/$maxCharacters；" +
            "节点=${entries.values.sumOf { c -> c.observations.sumOf { it.nodes.size } }}/$maxNodes")
        appendLine("已淘汰=$evictedCount；容量拒绝=$rejectedCount；累计历史恢复=$historicalRecoveryCount；$lastResult")
        entries.values.forEach { card ->
            appendLine("localId=${card.localId} 可见=${card.localId in visibleIds} 观测=${card.observations.size}" +
                " 最丰富文本字符=${card.richestObservation.textSize} 最新文本字符=${card.latestObservation.textSize}")
        }
        appendLine("历史证据帧（不同有序锚点）=${recentFrames.size}/10；原因累计（按检查次数）：$diagnosticCounts")
        diagnosticEvents.forEach { appendLine(it) }
    }
}
