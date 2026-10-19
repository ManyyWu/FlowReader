package com.flowreader.diagnostics

/** Supports the observed PostDetail layout: compact author header, then direct body TextView. */
internal object XDetailTextExtractor {
    private val handle = Regex("@[A-Za-z0-9_]{1,15}")
    private val controls = setOf("回复", "转帖", "喜欢", "书签", "分享", "展示量", "Reply", "Repost", "Like", "Share")
    private val labels = setOf("翻译自英语", "显示原文", "显示翻译", "正在翻译", "翻译帖子", "认证企业", "认证政府账号", "已认证", "已验证")
    private fun isLabel(text: String) = text.trim().trimEnd('.', '。') in labels

    /** Expanded quote directly attached to the detail scroller, without a merged summary. */
    fun quoteBodyIndices(snapshot: ScreenSnapshot, tree: SnapshotTree): List<Int> {
        val nodes = snapshot.nodes
        val detail = nodes.indices.singleOrNull { nodes[it].viewId == "PostDetail" } ?: return emptyList()
        return tree.subtreeIndices(detail).filter { root ->
            val parent = nodes[root].parentIndex
            val list = parent?.let { nodes[it].parentIndex }
            nodes[root].isClickable && list != null && nodes[list].isScrollable &&
                tree.childrenOf(root).any { nodes[it].contentDescription.trim() in setOf("引用", "Quote") }
        }.mapNotNull { root ->
            val children = tree.childrenOf(root)
            val author = children.indexOfFirst { handle.matches(nodes[it].text) }
            if (author < 0) return@mapNotNull null
            children.drop(author + 1).filter {
                val n = nodes[it]
                n.className == "android.widget.TextView" && !n.isClickable && n.text.isNotBlank() &&
                    !isLabel(n.text) && !n.text.trim().startsWith("·")
            }.singleOrNull()?.takeIf { nodes[it].isVisibleToUser }
        }
    }

    fun recommendationNodes(snapshot: ScreenSnapshot, tree: SnapshotTree): Set<Int> {
        val nodes = snapshot.nodes
        val detail = nodes.indices.singleOrNull { nodes[it].viewId == "PostDetail" } ?: return emptySet()
        val detailNodes = tree.subtreeIndices(detail).toSet()
        val excluded = mutableSetOf<Int>()
        nodes.indices.filter { it in detailNodes }.forEach { index ->
            val title = nodes[index]
            if (title.className != "android.widget.TextView" ||
                title.text.trim() !in setOf("发现更多", "Discover more")) return@forEach
            val header = title.parentIndex ?: return@forEach
            val list = nodes[header].parentIndex ?: return@forEach
            if (nodes[header].isClickable || tree.childrenOf(header) != listOf(index) || !nodes[list].isScrollable)
                return@forEach
            tree.childrenOf(list).filter { nodes[it].indexInParent >= nodes[header].indexInParent }
                .forEach { excluded.addAll(tree.subtreeIndices(it)) }
        }
        return excluded
    }

    fun findBodyIndex(snapshot: ScreenSnapshot, tree: SnapshotTree): Int? {
        val nodes = snapshot.nodes
        val detail = nodes.indices.singleOrNull { nodes[it].viewId == "PostDetail" } ?: return null
        val bodies = tree.subtreeIndices(detail).filter { nodes[it].isScrollable }.mapNotNull { scroller ->
            val children = tree.childrenOf(scroller)
            val headerSlot = children.indexOfFirst { index ->
                val subtree = tree.subtreeIndices(index)
                val avatar = subtree.singleOrNull { nodes[it].viewId == "timeline_post_avatar" }
                    ?.let { nodes[it].contentDescription }
                val texts = subtree.map { nodes[it] }.filter { it.className == "android.widget.TextView" && it.text.isNotBlank() }
                // A card containing an author and a body is not a compact header.
                avatar != null && texts.any { handle.matches(it.text) } &&
                    texts.all { it.text == avatar || handle.matches(it.text) || isLabel(it.text) }
            }
            if (headerSlot < 0) return@mapNotNull null
            children.drop(headerSlot + 1).asSequence().takeWhile { index ->
                tree.subtreeIndices(index).none {
                    nodes[it].text.ifBlank { nodes[it].contentDescription }.trim() in controls
                }
            }.firstOrNull { index ->
                val node = nodes[index]
                node.className == "android.widget.TextView" && !node.isClickable &&
                    node.text.isNotBlank() && !isLabel(node.text)
            }
        }
        return bodies.singleOrNull()
    }
}
