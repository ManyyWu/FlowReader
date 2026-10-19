package com.flowreader.scroll

import com.flowreader.diagnostics.*

/** Raw hidden cards are ordering evidence only; their text is never reinserted into speech. */
internal object XHiddenCardBoundary {
    private fun excludedCard(card: TimelineCardCandidate): Boolean = XPostTextExtractor.isKnownPromoted(card.summary) ||
        XPostTextExtractor.summaryBody(card.summary)?.let { ReadableText.withoutLinks(it).isBlank() } == true

    fun filteredOnly(snapshot: ScreenSnapshot): Boolean {
        val sample = XTimelineCards.analyze(snapshot)
        val visible = sample.cards.filter { snapshot.nodes[it.rootIndex].isVisibleToUser && snapshot.nodes[it.summaryNodeIndex].isVisibleToUser }
        return sample.containerIndex != null && visible.isNotEmpty() && visible.all(::excludedCard)
    }
    fun fromFilteredOnly(before: ScreenSnapshot, after: ScreenSnapshot, next: List<ReadingBlock>): Boolean =
        before.packageName == after.packageName && before.windowId == after.windowId &&
            before.isTraversalComplete && after.isTraversalComplete && next.isNotEmpty() &&
            filteredOnly(before) && promotedBridge(before, emptyList(), after, next)

    private fun promotedBridge(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot, next: List<ReadingBlock>): Boolean {
        val old = XTimelineCards.analyze(before)
        val current = XTimelineCards.analyze(after)
        val tail = old.cards.lastOrNull()?.takeIf(::excludedCard) ?: return false
        if (old.containerIndex == null || before.nodes[tail.rootIndex].indexInParent != before.nodes[old.containerIndex].childCount - 1) return false
        if (read.isNotEmpty() && read.none { block -> old.cards.any { block.nodeIndex in it.nodeIndices } }) return false
        val identity = XPostTextExtractor.adjacentMatchKey(tail.summary)
        val matches = current.cards.indices.filter { XPostTextExtractor.adjacentMatchKey(current.cards[it].summary) == identity }
        val position = matches.singleOrNull() ?: return false
        val following = current.cards.drop(position + 1).takeWhile(::excludedCard).size
        val candidate = current.cards.getOrNull(position + 1 + following) ?: return false
        return next.first().nodeIndex in candidate.nodeIndices
    }

    fun bridges(before: ScreenSnapshot, read: List<ReadingBlock>, after: ScreenSnapshot,
        next: List<ReadingBlock>): Boolean {
        if (before.packageName != "com.twitter.android" || after.packageName != before.packageName ||
            before.windowId != after.windowId || !before.isTraversalComplete || !after.isTraversalComplete ||
            read.isEmpty() || next.isEmpty()) return false
        if (promotedBridge(before, read, after, next)) return true
        val key = read.last().parentKey ?: read.last().key ?: return false
        val old = XTimelineCards.analyze(before)
        val current = XTimelineCards.analyze(after)
        if (old.containerIndex == null || current.containerIndex == null) return false
        fun key(card: TimelineCardCandidate): String? {
            val parsed = XPostTextExtractor.adjacentMatchKey(card.summary)
            return if (parsed.first) readingKey(parsed.second) else null
        }
        val previous = old.cards.filter { key(it) == key }.singleOrNull() ?: return false
        if (previous != old.cards.lastOrNull() || read.last().nodeIndex !in previous.nodeIndices) return false
        if (before.nodes[previous.rootIndex].indexInParent != before.nodes[old.containerIndex].childCount - 1)
            return false
        val anchors = current.cards.indices.filter { key(current.cards[it]) == key }
        val anchor = anchors.singleOrNull() ?: return false
        val hidden = current.cards[anchor]
        if (after.nodes[hidden.rootIndex].isVisibleToUser ||
            after.nodes[hidden.summaryNodeIndex].isVisibleToUser) return false
        val following = current.cards.getOrNull(anchor + 1) ?: return false
        if (after.nodes[following.rootIndex].indexInParent != after.nodes[hidden.rootIndex].indexInParent + 1)
            return false
        return next.first().nodeIndex in following.nodeIndices
    }
}
