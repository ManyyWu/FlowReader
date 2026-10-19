package com.flowreader.diagnostics

import java.security.MessageDigest

enum class DisplayStatus { NORMAL, UNRECOGNIZED, RECOMMENDATIONS_HIDDEN, DETAIL_SCOPE_UNKNOWN }
data class SnapshotDisplay(val text: String = "", val status: DisplayStatus = DisplayStatus.NORMAL,
    val blocks: List<String> = emptyList(), val readingBlocks: List<ReadingBlock> = emptyList(),
    val selectionContext: String? = null, val detailIdentity: String? = null)
data class DetailScopeDecision(
    val excludedNodes: Set<Int> = emptySet(),
    val suppressAll: Boolean = false,
    val emptyStatus: DisplayStatus = DisplayStatus.UNRECOGNIZED
)

/** A conservative, bounded section memory. Hashes are anchors, not permanent comment/post IDs. */
class DetailSessionScope(private val maxAnchors: Int = 100) {
    init { require(maxAnchors > 0) }
    private var windowId: Int? = null
    private var originalKey: String? = null
    private var originalText = ""
    private var boundarySeen = false
    private val comments = linkedSetOf<String>()
    private val recommendations = linkedSetOf<String>()
    val selectionIdentity: String? get() = originalKey
    var state = "尚无详情会话"
        private set

    fun clear() {
        windowId = null; originalKey = null; originalText = ""; boundarySeen = false
        comments.clear(); recommendations.clear(); state = "尚无详情会话"
    }

    fun process(snapshot: ScreenSnapshot): DetailScopeDecision {
        val nodes = snapshot.nodes
        if (snapshot.packageName != "com.twitter.android" || nodes.none { it.viewId == "PostDetail" }) {
            clear(); return DetailScopeDecision()
        }
        if (!snapshot.isTraversalComplete) {
            state = "快照不完整，暂不更新分区"
            return DetailScopeDecision(suppressAll = true, emptyStatus = DisplayStatus.DETAIL_SCOPE_UNKNOWN)
        }
        if (windowId != snapshot.windowId) { clear(); windowId = snapshot.windowId }
        val tree = SnapshotTree(nodes)
        val identifiedBody = XDetailTextExtractor.findBodyIndex(snapshot, tree)
        val bodyIndex = identifiedBody ?: rememberedBodyIndex(snapshot, tree)
        if (identifiedBody != null) {
            val body = nodes[identifiedBody].text
            val scroller = nodes[identifiedBody].parentIndex
            val handles = scroller?.let { tree.subtreeIndices(it) }.orEmpty().filter { it < identifiedBody }
                .map { nodes[it].text }.filter { it.matches(Regex("@[A-Za-z0-9_]{1,15}")) }
            val key = fingerprint(handles.firstOrNull().orEmpty() + "\n" + body)
            if (key != originalKey) {
                clear(); windowId = snapshot.windowId
            }
            originalKey = key
            // Only retained as context; never inject offscreen original text into the live display.
            originalText = body.take(100_000)
        }
        val excluded = XDetailTextExtractor.recommendationNodes(snapshot, tree)
        val summaries = nodes.indices.filter {
            val n = nodes[it]
            n.childCount == 0 && n.text.isBlank() && XPostTextExtractor.summaryBody(n.contentDescription) != null &&
                (bodyIndex == null || n.parentIndex == null || bodyIndex !in tree.subtreeIndices(n.parentIndex))
        }
        fun anchor(index: Int) = fingerprint(nodes[index].contentDescription)
        fun remember(target: MutableSet<String>, value: String) {
            target += value
            while (target.size > maxAnchors) target.remove(target.first())
        }
        if (excluded.isNotEmpty()) {
            boundarySeen = true
            summaries.forEach { remember(if (it in excluded) recommendations else comments, anchor(it)) }
            state = "已记录发现更多边界"
            return DetailScopeDecision(excluded, emptyStatus = DisplayStatus.RECOMMENDATIONS_HIDDEN)
        }
        if (bodyIndex != null || (!boundarySeen && originalKey != null)) {
            summaries.forEach { remember(comments, anchor(it)) }
            state = "原帖/评论区域"
            return DetailScopeDecision()
        }
        if (!boundarySeen) {
            if (XDetailTextExtractor.quoteBodyIndices(snapshot, tree).isNotEmpty()) {
                state = "独立引用正文区域，原帖身份尚未确认"
                return DetailScopeDecision(excludedNodes = summaries.mapNotNull { nodes[it].parentIndex }
                    .flatMap(tree::subtreeIndices).toSet())
            }
            state = "未确认原帖，请回到正文建立会话"
            return DetailScopeDecision(suppressAll = true, emptyStatus = DisplayStatus.DETAIL_SCOPE_UNKNOWN)
        }
        // Once the heading has left the tree, allow only previously confirmed comment anchors.
        // Unknown items may be recommendations; do not infer their section from their list position.
        val allowed = summaries.filter { anchor(it) in comments && anchor(it) !in recommendations }.toSet()
        val rejectedParents = summaries.filter { it !in allowed }.mapNotNull { nodes[it].parentIndex }
        val rejectedNodes = rejectedParents.flatMap(tree::subtreeIndices).toSet()
        val knownRecommendation = summaries.any { anchor(it) in recommendations }
        state = if (knownRecommendation) "推荐区域，已排除" else "边界离屏，仅显示已确认评论"
        return DetailScopeDecision(rejectedNodes, suppressAll = allowed.isEmpty(), emptyStatus =
            if (knownRecommendation) DisplayStatus.RECOMMENDATIONS_HIDDEN else DisplayStatus.DETAIL_SCOPE_UNKNOWN)
    }

    private fun rememberedBodyIndex(snapshot: ScreenSnapshot, tree: SnapshotTree): Int? {
        if (originalKey == null || boundarySeen || windowId != snapshot.windowId || !snapshot.isTraversalComplete)
            return null
        val detail = snapshot.nodes.indices.singleOrNull { snapshot.nodes[it].viewId == "PostDetail" } ?: return null
        return tree.childrenOf(detail).filter { snapshot.nodes[it].isScrollable }
            .flatMap(tree::childrenOf).filter {
                val node = snapshot.nodes[it]
                node.className == "android.widget.TextView" && !node.isClickable && node.text == originalText
            }.singleOrNull()
    }

    /** Reuse only the currently exposed direct original node; never insert retained offscreen text. */
    fun readingBlocks(snapshot: ScreenSnapshot, decision: DetailScopeDecision): List<ReadingBlock> {
        if (decision.suppressAll) return emptyList()
        val blocks = XPostTextExtractor.readingBlocks(snapshot, decision.excludedNodes).toMutableList()
        val tree = SnapshotTree(snapshot.nodes)
        val index = rememberedBodyIndex(snapshot, tree) ?: return blocks
        val key = originalKey ?: return blocks
        if (index !in decision.excludedNodes && snapshot.nodes[index].isVisibleToUser && blocks.none { it.nodeIndex == index })
            blocks += ReadingBlock(snapshot.nodes[index].text.trim(), index, key = key)
        // Quote ownership is known only while the original node is still exposed in this detail tree.
        val quoteIndices = XDetailTextExtractor.quoteBodyIndices(snapshot, tree).toSet()
        val decorated = blocks.map { block ->
            when {
                block.nodeIndex == index -> block.copy(key = key)
                block.kind == ReadingBlockKind.QUOTE && block.nodeIndex in quoteIndices ->
                    block.copy(key = readingKey("$key\nquote\n${block.text}"), parentKey = key)
                else -> block
            }
        }
        return XPostTextExtractor.sortBlocks(snapshot, decorated)
    }

    fun summary(): String = "详情会话：window=$windowId 原帖已识别=${originalKey != null}" +
        " 原帖保留字符=${originalText.length} 已见推荐边界=$boundarySeen" +
        " 评论锚点=${comments.size}/$maxAnchors 推荐锚点=${recommendations.size}/$maxAnchors；$state"

    private fun fingerprint(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return CharArray(bytes.size * 2).also { chars ->
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 255
                chars[index * 2] = hex[value ushr 4]
                chars[index * 2 + 1] = hex[value and 15]
            }
        }.concatToString()
    }
}
