package com.flowreader.diagnostics

/** A structural candidate, not a verified post identity. Never use rootIndex across captures. */
data class TimelineCardCandidate(
    val rootIndex: Int,
    val nodeIndices: List<Int>,
    val summaryNodeIndex: Int,
    val summary: String,
    val quoteRootIndices: List<Int>
)

data class TimelineCardSample(
    val windowId: Int,
    val containerIndex: Int?,
    val cards: List<TimelineCardCandidate>,
    val reason: String
)

data class AdjacentCardMatch(val previousRootIndex: Int, val currentRootIndex: Int)

object XTimelineCards {
    fun analyze(snapshot: ScreenSnapshot): TimelineCardSample {
        fun unknown(reason: String) = TimelineCardSample(snapshot.windowId, null, emptyList(), reason)
        if (snapshot.packageName != "com.twitter.android") return unknown("非 X 页面")
        if (!snapshot.isTraversalComplete) return unknown("快照不完整，暂不分组")
        val nodes = snapshot.nodes
        val scaffold = nodes.indices.filter { nodes[it].viewId in setOf("scaffold_home_tabbed", "UserProfileScreen") }
            .singleOrNull() ?: return unknown("未找到已验证的信息流或个人首页结构")
        val tree = SnapshotTree(nodes)
        val containers = tree.subtreeIndices(scaffold).filter { nodes[it].isScrollable }.mapNotNull { list ->
            val cards = tree.childrenOf(list).mapNotNull card@ { root ->
                if (!nodes[root].isClickable) return@card null
                val summary = tree.childrenOf(root).filter {
                    nodes[it].childCount == 0 && nodes[it].text.isBlank() &&
                        nodes[it].contentDescription.length >= 50
                }.singleOrNull() ?: return@card null
                val subtree = tree.subtreeIndices(root)
                val quotes = subtree.filter { candidate ->
                    nodes[candidate].isClickable && tree.childrenOf(candidate).any {
                        nodes[it].text.ifBlank { nodes[it].contentDescription }.trim() in setOf("引用", "Quote")
                    }
                }
                TimelineCardCandidate(root, subtree, summary, nodes[summary].contentDescription, quotes)
            }
            if (cards.isEmpty()) null else list to cards
        }
        val selected = containers.singleOrNull() ?: return unknown(
            if (containers.isEmpty()) "未发现候选卡片" else "多个候选容器，暂不选择")
        return TimelineCardSample(snapshot.windowId, selected.first, selected.second,
            "结构候选：不保证每项都是普通推文，不提供持久帖子ID")
    }

    /** Adjacent-view evidence only; accepted grammar excludes volatile time/counter suffixes. */
    fun matchAdjacent(previous: TimelineCardSample, current: TimelineCardSample): List<AdjacentCardMatch> {
        if (previous.windowId < 0 || previous.windowId != current.windowId ||
            previous.containerIndex == null || current.containerIndex == null) return emptyList()
        val old = previous.cards.groupBy { XPostTextExtractor.adjacentMatchKey(it.summary) }
        val matches = current.cards.groupBy { XPostTextExtractor.adjacentMatchKey(it.summary) }.mapNotNull { (key, cards) ->
            val before = old[key]?.singleOrNull()
            val after = cards.singleOrNull()
            if (key.second.isBlank() || before == null || after == null) null
            else AdjacentCardMatch(before.rootIndex, after.rootIndex)
        }.sortedBy { it.currentRootIndex }
        // A reorder needs separate evidence; do not silently treat it as ordinary scrolling.
        return if (matches.zipWithNext().any { (a, b) -> a.previousRootIndex >= b.previousRootIndex })
            emptyList() else matches
    }
}
