package com.flowreader.diagnostics

/** Indexes one immutable snapshot. Indices are local to this capture, never post identities. */
class SnapshotTree(private val nodes: List<AccessibilityNodeSnapshot>) {
    private val children = List(nodes.size) { mutableListOf<Int>() }
    val roots: List<Int>

    init {
        val rootIndices = mutableListOf<Int>()
        nodes.forEachIndexed { index, node ->
            val parent = node.parentIndex
            if (parent == null) {
                require(node.depth == 0) { "Non-root node without parent at $index" }
                rootIndices += index
            } else {
                require(parent in 0 until index) { "Invalid parent at $index" }
                require(nodes[parent].depth + 1 == node.depth) { "Invalid depth at $index" }
                require(node.indexInParent in 0 until nodes[parent].childCount) { "Invalid child slot at $index" }
                require(children[parent].none { nodes[it].indexInParent == node.indexInParent }) {
                    "Duplicate child slot at $index"
                }
                children[parent] += index
            }
        }
        roots = rootIndices.toList()
    }

    fun childrenOf(index: Int): List<Int> = children[index].toList()

    fun subtreeIndices(index: Int): List<Int> {
        require(index in nodes.indices)
        val result = mutableListOf<Int>()
        val pending = ArrayDeque<Int>()
        pending.addLast(index)
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            result += next
            children[next].asReversed().forEach(pending::addLast)
        }
        return result
    }
}

/** Structural diagnostics only; does not assume a scroll child corresponds to one post. */
fun ScreenSnapshot.formatStructureSummary(): String = buildString {
    val tree = SnapshotTree(nodes)
    val suppliedIds = nodes.mapNotNull { it.uniqueId?.takeIf(String::isNotBlank) }
    appendLine("包名=$packageName; windowId=$windowId; 节点=${nodes.size}; 根=${tree.roots.size}")
    appendLine("提供uniqueId的节点=${suppliedIds.size}; 不同uniqueId=${suppliedIds.distinct().size}")
    appendLine("注意：节点uniqueId和列表位置均未验证为推文ID；以下仅为容器结构。")
    appendLine(traversalDiagnostics)
    if (packageName == "com.twitter.android") {
        val bodies = XPostTextExtractor.extract(this@formatStructureSummary)
        appendLine("单屏规则正文块=${bodies.size}; 字符=${bodies.sumOf { it.length }}")
    }
    val timeline = XTimelineCards.analyze(this@formatStructureSummary)
    appendLine("时间线候选：${timeline.reason}; container=${timeline.containerIndex}; cards=${timeline.cards.size}")
    timeline.cards.forEach {
        appendLine("  卡片 root=${it.rootIndex} 节点=${it.nodeIndices.size}" +
            " summaryNode=${it.summaryNodeIndex} summaryLength=${it.summary.length}" +
            " 引用子树=${it.quoteRootIndices}")
    }
    nodes.forEachIndexed { index, node ->
        if (!node.isScrollable) return@forEachIndexed
        appendLine("滚动容器 index=$index parent=${node.parentIndex} class=${node.className} viewId=${node.viewId}")
        tree.childrenOf(index).forEach { child ->
            val subtree = tree.subtreeIndices(child)
            val texts = subtree.count { nodes[it].text.isNotBlank() }
            val descriptions = subtree.count { nodes[it].contentDescription.isNotBlank() }
            val missingSlots = subtree.sumOf { (nodes[it].childCount - tree.childrenOf(it).size).coerceAtLeast(0) }
            appendLine("  child=$child slot=${nodes[child].indexInParent} 节点=${subtree.size}" +
                " text节点=$texts description节点=$descriptions 未采子节点=$missingSlots" +
                " bounds=${nodes[child].boundsInScreen} viewId=${nodes[child].viewId}")
        }
    }
}
