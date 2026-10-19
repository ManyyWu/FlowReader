package com.flowreader.scroll

import com.flowreader.diagnostics.ReadingBlock
import com.flowreader.diagnostics.ScreenSnapshot

/** A header-only trailing comment can become the first readable comment after a forward scroll. */
internal object RedditCommentBoundary {
    private val header = Regex("""^(.+?) 的 ([0-9]+) 级评论(?:[,，].*)?$""")
    private fun inside(snapshot: ScreenSnapshot, index: Int, root: Int): Boolean {
        var current: Int? = index
        var budget = snapshot.nodes.size
        while (current != null && budget-- > 0) {
            if (current == root) return true
            current = snapshot.nodes.getOrNull(current)?.parentIndex
        }
        return false
    }
    private fun comments(snapshot: ScreenSnapshot): List<Pair<Int, String>> =
        snapshot.nodes.indices.filter {
            snapshot.nodes[it].viewId == "comment_layout" && snapshot.nodes[it].isVisibleToUser
        }.mapNotNull { root ->
            val depth = snapshot.nodes[root].depth
            val descriptions = snapshot.nodes.drop(root + 1).takeWhile { it.depth > depth }
                // A clipped header still identifies its visible comment root; it is never spoken.
                .mapNotNull { header.matchEntire(it.contentDescription) }
            descriptions.singleOrNull()?.let { root to "${it.groupValues[1]}:${it.groupValues[2]}" }
        }

    fun bridges(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot,
        next: List<ReadingBlock>): Boolean {
        if (before.packageName != "com.reddit.frontpage" || after.packageName != before.packageName ||
            before.windowId != after.windowId || !before.isTraversalComplete || !after.isTraversalComplete ||
            read.isEmpty() || next.isEmpty()) return false
        return headerBridge(before, read, after, next) || unreadBodyBridge(before, read, after, next)
    }

    private fun unreadBodyBridge(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot,
        next: List<ReadingBlock>): Boolean {
        val (tail, _) = matchingBody(before, after, next) ?: return false
        return read.none { it.nodeIndex >= tail || inside(before, it.nodeIndex, tail) }
    }

    fun knownBodyContinuation(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot,
        next: List<ReadingBlock>): ContentContinuation.Result? {
        val (tail, first) = matchingBody(before, after, next) ?: return null
        val oldBody = read.filter { inside(before, it.nodeIndex, tail) }
        val newBody = next.takeWhile { inside(after, it.nodeIndex, first) }
        if (oldBody.isEmpty() || read.takeLast(oldBody.size) != oldBody ||
            oldBody.map { it.text } != newBody.map { it.text } ||
            (oldBody + newBody).any { it.continuitySection != "reddit:comment" }) return null
        return if (newBody.size == next.size) ContentContinuation.Result(reason = "当前屏正文均已读", allRead = true)
            else ContentContinuation.Result(newBody.size, "已确认评论正文子树衔接")
    }

    private fun matchingBody(before: ScreenSnapshot, after: ScreenSnapshot,
        next: List<ReadingBlock>): Pair<Int, Int>? {
        if (before.packageName != "com.reddit.frontpage" || after.packageName != before.packageName ||
            before.windowId != after.windowId || !before.isTraversalComplete || !after.isTraversalComplete ||
            next.isEmpty()) return null
        fun emptyPlaceholder(s: ScreenSnapshot, index: Int): Boolean = s.nodes[index].let {
            it.viewId == "comment_layout" && it.childCount == 0 && it.text.isBlank() && it.contentDescription.isBlank() &&
                !it.isClickable && !it.isScrollable
        }
        fun roots(s: ScreenSnapshot): List<Int> = s.nodes.indices.filter {
            s.nodes[it].viewId == "comment_layout" && s.nodes[it].isVisibleToUser &&
                !emptyPlaceholder(s, it) &&
                s.nodes.getOrNull(s.nodes[it].parentIndex ?: -1)?.viewId == "feed_lazy_column"
        }
        fun signature(s: ScreenSnapshot, root: Int): List<String>? {
            val bodies = s.nodes.indices.filter { s.nodes[it].viewId == "comment_body_tag" && inside(s, it, root) }
            val body = bodies.singleOrNull() ?: return null
            val nodes = s.nodes.indices.filter { inside(s, it, body) }
            val text = nodes.joinToString("") { s.nodes[it].text }.filter { it.isLetterOrDigit() }
            val media = nodes.any { s.nodes[it].viewId == "image_content_tag" }
            if (text.length < if (media) 4 else 12) return null
            return nodes.map { index -> s.nodes[index].let {
                "${it.depth - s.nodes[body].depth}|${if (index == body) 0 else it.indexInParent}|${it.viewId}|${it.className}|${it.childCount}|${it.text}|${it.contentDescription}"
            } }
        }
        val oldRoots = roots(before); val newRoots = roots(after)
        val tail = oldRoots.lastOrNull() ?: return null
        val first = newRoots.firstOrNull() ?: return null
        val parent = before.nodes[tail].parentIndex ?: return null
        // The old unread comment must be the trailing list item, and the new first item must own the first text.
        if (before.nodes.indices.any { it > tail && before.nodes[it].parentIndex == parent && !emptyPlaceholder(before, it) } ||
            !inside(after, next.first().nodeIndex, first)) return null
        val oldHeader = comments(before).filter { it.first == tail }.singleOrNull()?.second ?: return null
        val newHeader = comments(after).filter { it.first == first }.singleOrNull()?.second
        if (newHeader != null && newHeader != oldHeader) return null
        val body = signature(before, tail) ?: return null
        if (signature(after, first) != body || oldRoots.count { signature(before, it) == body } != 1 ||
            newRoots.count { signature(after, it) == body } != 1) return null
        // Hidden text is identity evidence only. Playback still starts from the visible new-screen queue.
        return tail to first
    }

    private fun headerBridge(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot,
        next: List<ReadingBlock>): Boolean {
        if (before.packageName != "com.reddit.frontpage" || after.packageName != before.packageName ||
            before.windowId != after.windowId || !before.isTraversalComplete || !after.isTraversalComplete ||
            read.isEmpty() || next.isEmpty()) return false
        val old = comments(before)
        val current = comments(after)
        val tail = old.lastOrNull() ?: return false
        if (old.count { it.second == tail.second } != 1 || current.count { it.second == tail.second } != 1)
            return false
        // It was exposed as a header only, after every block in the queue that has just finished.
        if (read.any { it.nodeIndex >= tail.first || inside(before, it.nodeIndex, tail.first) }) return false
        val first = current.firstOrNull() ?: return false
        return first.second == tail.second && inside(after, next.first().nodeIndex, first.first)
    }
}
