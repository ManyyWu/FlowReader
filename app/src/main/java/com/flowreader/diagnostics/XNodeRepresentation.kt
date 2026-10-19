package com.flowreader.diagnostics

/** Links only adjacent expanded/summary representations with an exact, unique author/body pair. */
internal object XNodeRepresentation {
    private val handle = Regex("(?:^|\\.\\s+)@([A-Za-z0-9_]{1,15})\\.\\s+")
    private val author = Regex("@[A-Za-z0-9_]{1,15}")
    private data class Evidence(val author: String, val body: String, val expanded: Boolean)
    private fun evidence(snapshot: ScreenSnapshot, block: ReadingBlock, tree: SnapshotTree): Evidence? {
        if (block.kind != ReadingBlockKind.POST) return null
        var root = snapshot.nodes.getOrNull(block.nodeIndex)?.parentIndex
        while (root != null) {
            val children = tree.childrenOf(root)
            val expanded = children.singleOrNull { snapshot.nodes[it].viewId.substringAfterLast(":id/") == "timeline_post" }
            val summary = children.map { snapshot.nodes[it] }.mapNotNull { handle.find(it.contentDescription)?.groupValues?.get(1) }.singleOrNull()
            val who = expanded?.let { header -> tree.subtreeIndices(header).map { snapshot.nodes[it].text.trim() }.filter(author::matches).singleOrNull()?.removePrefix("@") } ?: summary
            if (who != null) return Evidence(who, canonical(block.text), expanded != null)
            root = snapshot.nodes[root].parentIndex
        }
        return null
    }
    private fun canonical(text: String) = text.trim().replace(Regex("\\s*\\n+\\s*"), ". ").replace(Regex("[ \\t]+"), " ")
    fun pairs(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot, next: List<ReadingBlock>): Map<Int, Int> {
        if (before.packageName != "com.twitter.android" || before.packageName != after.packageName ||
            before.windowId != after.windowId || !before.isTraversalComplete || !after.isTraversalComplete) return emptyMap()
        val oldTree = SnapshotTree(before.nodes); val newTree = SnapshotTree(after.nodes)
        val old = read.map { evidence(before, it, oldTree) }; val current = next.map { evidence(after, it, newTree) }
        return current.mapIndexedNotNull { index, item ->
            if (item == null) return@mapIndexedNotNull null
            fun same(other: Evidence?) = other?.author == item.author && other.body == item.body
            if (current.count(::same) != 1 || old.count(::same) != 1) return@mapIndexedNotNull null
            val previous = old.indexOfFirst(::same)
            if (old[previous]?.expanded == item.expanded) null else index to previous
        }.toMap()
    }
}
