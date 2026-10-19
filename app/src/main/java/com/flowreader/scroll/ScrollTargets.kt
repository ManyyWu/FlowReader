package com.flowreader.scroll

import com.flowreader.diagnostics.ReadingBlock
import com.flowreader.diagnostics.ScreenSnapshot
import com.flowreader.diagnostics.SnapshotTree

internal object ScrollTargets {
    /** Profile header and timeline share nested scrolling, but only the tiny exposed list is marked scrollable. */
    fun profileViewport(snapshot: ScreenSnapshot, target: Int): Int? {
        if (snapshot.packageName != "com.twitter.android") return null
        val nodes = snapshot.nodes
        val tree = SnapshotTree(nodes)
        val ancestors = mutableListOf<Int>()
        var parent = nodes.getOrNull(target)?.parentIndex
        while (parent != null) {
            ancestors += parent
            if (nodes[parent].viewId == "UserProfileScreen") break
            parent = nodes[parent].parentIndex
        }
        if (ancestors.none { nodes[it].viewId == "UserProfileScreen" }) return null
        return ancestors.firstOrNull { root ->
            val labels = tree.subtreeIndices(root).map { nodes[it].text.ifBlank { nodes[it].contentDescription }.trim() }.toSet()
            ("帖子" in labels && "回复" in labels) || ("Posts" in labels && "Replies" in labels)
        }
    }

    fun select(snapshot: ScreenSnapshot, blocks: List<ReadingBlock>): Int? {
        if (snapshot.packageName == "com.reddit.frontpage") {
            return snapshot.nodes.indices.filter {
                val node = snapshot.nodes[it]
                node.viewId == "feed_lazy_column" && node.isVisibleToUser && node.isScrollable
            }.singleOrNull()
        }
        if (blocks.isEmpty() && XHiddenCardBoundary.filteredOnly(snapshot)) {
            return com.flowreader.diagnostics.XTimelineCards.analyze(snapshot).containerIndex?.takeIf {
                snapshot.nodes[it].isScrollable && snapshot.nodes[it].isVisibleToUser
            }
        }
        return blocks.mapNotNull { block ->
            var index = snapshot.nodes.getOrNull(block.nodeIndex)?.parentIndex
            while (index != null) {
                val node = snapshot.nodes[index]
                if (node.isScrollable && node.isVisibleToUser &&
                    node.className != "android.widget.HorizontalScrollView" &&
                    node.className != "androidx.viewpager.widget.ViewPager") return@mapNotNull index
                index = node.parentIndex
            }
            null
        }.distinct().singleOrNull()
    }
}
