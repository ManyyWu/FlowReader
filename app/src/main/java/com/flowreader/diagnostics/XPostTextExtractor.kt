package com.flowreader.diagnostics

/** Only known X summary grammar is accepted. Raw diagnostics remain available separately. */
object XPostTextExtractor {
    private val promoted = linkedSetOf<String>()
    private val bodyEvidence = linkedMapOf<String, Pair<String, String>>()
    internal fun clearEvidence() { promoted.clear(); bodyEvidence.clear() }
    internal fun promotedKeys(): Set<String> = promoted.toSet()
    internal fun isKnownPromoted(summary: String) = readingKey(adjacentMatchKey(summary).second) in promoted
    private val handle = Regex("(?:^|\\.\\s+)@[A-Za-z0-9_]{1,15}\\.\\s+")
    private val badge = Regex("^(?:(?:认证政府账号|已认证|已验证|认证企业|Verified)[.。]?\\s*)+")
    private val footer = Regex(
        "\\.\\s+(?:刚刚|[0-9]+\\s*(?:秒|分钟|小时|天|周|个月|年)前|昨天|前天|" +
            "(?:(?:[0-9]{2}|[0-9]{4})年)?[0-9]{1,2}月[0-9]{1,2}日)\\.\\s+" +
            "(?:这是该帖子的最新版本\\.\\s+)?" +
            "(?:[0-9][0-9.,]*[KMBkmb万亿]?\\s*" +
            "(?:条回复|个回复|回复|次转帖|转帖|个喜欢|喜欢|认证查看次数|查看次数|次查看|次浏览|个书签|书签|次引用)" +
            "\\.?\\s*)+$"
    )
    private val mediaLabels = "(?:图像|图片|相片|视频|GIF)"
    private val mediaSuffix = Regex("(?:\\.\\s+$mediaLabels)+$")
    private val mediaBeforeQuote = Regex("(?:\\.\\s+$mediaLabels)+(\\.\\s+(?:引用|Quote)\\.)")
    private val quoteMarker = Regex("\\.\\s+(?:引用|Quote)\\.\\s+")
    private fun cleanSummaryLabels(body: String): String = body
        .replace(mediaBeforeQuote, "$1")
        .replace(quoteMarker, "\n\n")
        .replace(mediaSuffix, "").trim()

    /** Adjacent-frame evidence only. Keep author, body, quote and media labels exact. */
    internal fun adjacentMatchKey(summary: String): Pair<Boolean, String> {
        if (summaryBody(summary) == null) return false to summary
        return true to summary.substring(0, footer.find(summary)!!.range.first)
    }

    internal fun summaryBody(summary: String): String? {
        val header = handle.find(summary) ?: return null
        val suffix = footer.find(summary) ?: return null
        if (header.range.last >= suffix.range.first) return null
        var body = summary.substring(header.range.last + 1, suffix.range.first)
        body = body.replaceFirst(badge, "")
        body = body.replace(mediaSuffix, "").trim()
        return body.takeIf { it.isNotBlank() }
    }

    fun extract(snapshot: ScreenSnapshot, excludedNodes: Set<Int> = emptySet()): List<String> {
        return readingBlocks(snapshot, excludedNodes).map { it.text }
    }

    fun readingBlocks(snapshot: ScreenSnapshot, excludedNodes: Set<Int> = emptySet()): List<ReadingBlock> {
        if (snapshot.packageName != "com.twitter.android") return emptyList()
        val nodes = snapshot.nodes
        val tree = SnapshotTree(nodes)
        val recommendations = (XDetailTextExtractor.recommendationNodes(snapshot, tree) + excludedNodes).toMutableSet()
        val summaries = nodes.indices.filter { it !in recommendations }.mapNotNull { index ->
            val node = nodes[index]
            if (!node.isVisibleToUser || node.childCount != 0 || node.text.isNotBlank() || node.contentDescription.isBlank()) null
            else summaryBody(node.contentDescription)?.let { body ->
                val parent = node.parentIndex
                if (parent != null && promotedCard(node.contentDescription, body, parent, tree.subtreeIndices(parent), nodes)) {
                    recommendations += tree.subtreeIndices(parent)
                    null
                } else Triple(index, parent, body)
            }
        }
        val byParent = summaries.groupBy { it.second }
        val detailBodyIndex = XDetailTextExtractor.findBodyIndex(snapshot, tree)?.takeIf { it !in recommendations }
        val summaryBodies = summaries.flatMap { (summaryIndex, parent, parsed) ->
            if (parent == null || byParent[parent]?.size != 1) return@flatMap emptyList()
            if (detailBodyIndex != null && detailBodyIndex in tree.subtreeIndices(parent)) return@flatMap emptyList()
            val subtree = tree.subtreeIndices(parent)
            val quotes = subtree.filter { candidate ->
                nodes[candidate].isClickable && tree.childrenOf(candidate).any {
                    nodes[it].text.ifBlank { nodes[it].contentDescription }.trim() in setOf("引用", "Quote")
                }
            }
            val marker = quoteMarker.find(parsed)
            val rawOwn = if (marker != null) parsed.substring(0, marker.range.first) else parsed
            val quoteIndices = quotes.flatMap(tree::subtreeIndices).toSet()
            val ownIndices = subtree.filter { it !in quoteIndices }
            val ownBody = stripMediaDescriptions(rawOwn, ownIndices, nodes)
            val key = readingKey(adjacentMatchKey(nodes[summaryIndex].contentDescription).second)
            val preferred = cleanSummaryLabels(preferCardBody(ownBody, parent, ownIndices, nodes))
            val raw = cleanSummaryLabels(rawOwn)
            val known = bodyEvidence[key]?.takeIf { it.first == raw }
            val own = if (directBody(parent, ownIndices, nodes) == null && canonicalBody(preferred) == canonicalBody(raw) && known != null) known.second else preferred
            if (canonicalBody(preferred) != canonicalBody(raw) && raw.length + preferred.length <= 100_000) {
                bodyEvidence[key] = raw to preferred
                while (bodyEvidence.size > 128 || bodyEvidence.values.sumOf { it.first.length + it.second.length } > 200_000)
                    bodyEvidence.remove(bodyEvidence.keys.first())
            }
            var canonicalQuote: String? = null
            val quote = if (marker != null) {
                var quotedBody = stripMediaDescriptions(parsed.substring(marker.range.last + 1), quoteIndices.toList(), nodes)
                val exposedAuthor = quotes.singleOrNull()?.let { root -> tree.childrenOf(root).asSequence()
                    .map { nodes[it] }.filter { it.className == "android.widget.TextView" && it.text.isNotBlank() }
                    .map { it.text }.firstOrNull { quotedBody.startsWith("$it. ") } }
                val author = exposedAuthor ?: quotedBody.substringBefore(". ", "").takeIf { it.isNotBlank() }
                if (author != null && quotedBody.startsWith("$author. ")) {
                    quotedBody = quotedBody.removePrefix("$author. ")
                }
                canonicalQuote = quotedBody.trim()
                preferVisibleText(quotedBody, quoteIndices.toList(), nodes)
            } else null
            listOfNotNull(own.takeIf { it.isNotBlank() }?.let {
                val bodyIndex = ownIndices.firstOrNull { candidate ->
                    val n = nodes[candidate]
                    n.isVisibleToUser && n.className == "android.widget.TextView" &&
                        n.text.isNotBlank() && normalize(n.text) == normalize(it)
                } ?: summaryIndex
                ReadingBlock(it, bodyIndex, key = key, continuityText = cleanSummaryLabels(rawOwn))
            },
                quote?.takeIf { it.isNotBlank() }?.let {
                    ReadingBlock(it, quotes.singleOrNull() ?: summaryIndex, ReadingBlockKind.QUOTE,
                        readingKey("$key\nquote\n$canonicalQuote"), key, continuityText = canonicalQuote)
                })
        }
        val blocks = summaryBodies.toMutableList()
        blocks += expandedTimelineBodies(snapshot, tree, recommendations, summaries.mapNotNull { it.second }.toSet())
        val detailKey = detailBodyIndex?.let { body ->
            val list = nodes[body].parentIndex
            val author = list?.let { tree.subtreeIndices(it) }.orEmpty().filter { it < body }
                .map { nodes[it].text }.firstOrNull { it.matches(Regex("@[A-Za-z0-9_]{1,15}")) }
            author?.let { readingKey("$it\n${nodes[body].text}") }
        }
        if (detailBodyIndex != null && nodes[detailBodyIndex].isVisibleToUser)
            blocks += ReadingBlock(nodes[detailBodyIndex].text.trim(), detailBodyIndex, key = detailKey)
        val summaryParents = summaries.mapNotNull { it.second }
        XDetailTextExtractor.quoteBodyIndices(snapshot, tree).filter { index ->
            index !in recommendations && summaryParents.none { index in tree.subtreeIndices(it) }
        }.forEach { blocks += ReadingBlock(nodes[it].text.trim(), it, ReadingBlockKind.QUOTE,
            key = detailKey?.let { parent -> readingKey("$parent\nquote\n${nodes[it].text}") }, parentKey = detailKey) }
        return sortBlocks(snapshot, blocks)
    }

    /** Compose may expose author/body/actions separately instead of a summary description. */
    private fun expandedTimelineBodies(snapshot: ScreenSnapshot, tree: SnapshotTree,
        excluded: Set<Int>, summarizedCards: Set<Int>): List<ReadingBlock> {
        val nodes = snapshot.nodes
        val labels = setOf("显示翻译", "显示原文", "翻译自英语", "Show translation", "Show original", "Translate post", "显示更多", "Show more")
        val actions = setOf("回复", "转帖", "喜欢", "Reply", "Repost", "Like")
        return nodes.indices.filter { nodes[it].viewId.substringAfterLast(":id/") == "timeline_post" }
            .mapNotNull { header ->
                val card = nodes[header].parentIndex ?: return@mapNotNull null
                if (card in summarizedCards || !nodes[card].isClickable || !nodes[card].isVisibleToUser) return@mapNotNull null
                val subtree = tree.subtreeIndices(card)
                if (subtree.any { it in excluded }) return@mapNotNull null
                val author = tree.subtreeIndices(header).map { nodes[it].text.trim() }
                    .singleOrNull { it.matches(Regex("@[A-Za-z0-9_]{1,15}")) } ?: return@mapNotNull null
                val children = tree.childrenOf(card)
                val footers = children.filter { child -> tree.subtreeIndices(child).any {
                    nodes[it].text.ifBlank { nodes[it].contentDescription }.trim() in actions
                } }
                if (footers.size < 2) return@mapNotNull null
                val footerSlot = footers.minOf { nodes[it].indexInParent }
                val bodyNodes = children.filter {
                    val n = nodes[it]
                    n.indexInParent > nodes[header].indexInParent && n.indexInParent < footerSlot &&
                        n.isVisibleToUser && !n.isClickable && n.className == "android.widget.TextView" &&
                        n.text.isNotBlank() && n.text.trim() !in labels
                }
                if (bodyNodes.isEmpty()) return@mapNotNull null
                val body = bodyNodes.joinToString("\n\n") { nodes[it].text.trim() }
                ReadingBlock(body, bodyNodes.first(), key = readingKey("$author\n$body"))
            }
    }

    internal fun sortBlocks(snapshot: ScreenSnapshot, blocks: List<ReadingBlock>): List<ReadingBlock> {
        val nodes = snapshot.nodes
        val bounds = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")
        fun position(index: Int): Pair<Int, Int>? = bounds.matchEntire(nodes[index].boundsInScreen)?.let {
            it.groupValues[2].toIntOrNull()?.let { top -> it.groupValues[1].toIntOrNull()?.let { left -> top to left } }
        }
        // If geometry is unavailable, preserve tree order for the entire sample.
        val cards = nodes.indices.filter { index ->
            val node = nodes[index]
            node.viewId.substringAfterLast(":id/") == "timeline_post" ||
                (node.text.isBlank() && node.childCount == 0 && summaryBody(node.contentDescription) != null)
        }.mapNotNull { nodes[it].parentIndex }.toSet()
        fun cardPosition(block: ReadingBlock): Int {
            var current: Int? = block.nodeIndex
            while (current != null) {
                if (current in cards) return current
                current = nodes.getOrNull(current)?.parentIndex
            }
            return block.nodeIndex
        }
        val owners = blocks.filter { it.kind == ReadingBlockKind.POST && it.key != null }.groupBy { it.key }
        fun anchor(block: ReadingBlock) = block.parentKey?.let { owners[it]?.singleOrNull() } ?: block
        return if (blocks.all { position(it.nodeIndex) != null && position(cardPosition(anchor(it))) != null }) {
            val geometry = compareBy<ReadingBlock> { position(cardPosition(anchor(it)))!!.first }
                .thenBy { position(cardPosition(anchor(it)))!!.second }
                .thenBy { cardPosition(anchor(it)) }
                .thenBy { if (it.kind == ReadingBlockKind.POST) 0 else 1 }
                .thenBy { position(it.nodeIndex)!!.first }.thenBy { it.nodeIndex }
            blocks.sortedWith { a, b ->
                val first = cardPosition(anchor(a)); val second = cardPosition(anchor(b))
                val siblings = first != second && first in cards && second in cards &&
                    nodes[first].parentIndex != null && nodes[first].parentIndex == nodes[second].parentIndex
                if (siblings) nodes[first].indexInParent.compareTo(nodes[second].indexInParent)
                else geometry.compare(a, b)
            }
        } else blocks.sortedBy { it.nodeIndex }
    }

    private fun stripMediaDescriptions(body: String, indices: List<Int>, nodes: List<AccessibilityNodeSnapshot>): String {
        var result = body
        indices.map { nodes[it] }.filter { it.text.isBlank() && it.childCount == 0 &&
            it.contentDescription.startsWith("GIF", ignoreCase = true) &&
            (it.contentDescription.startsWith("GIF：") || it.contentDescription.startsWith("GIF:"))
        }.map { it.contentDescription }.distinct().forEach { label -> result = result.removeSuffix(". $label") }
        return result
    }
    private fun directBody(parent: Int, indices: List<Int>, nodes: List<AccessibilityNodeSnapshot>): String? =
        indices.map { nodes[it] }.filter {
            it.parentIndex == parent && it.className == "android.widget.TextView" && !it.isClickable &&
                it.text.isNotBlank() && it.text.trim() !in cardLabels
        }.singleOrNull()?.text?.trim()

    /** Learn an ad marker only when the card's real body proves it is a separate summary field. */
    private fun promotedCard(description: String, body: String, parent: Int, indices: List<Int>, nodes: List<AccessibilityNodeSnapshot>): Boolean {
        val marker = Regex("\\. (?:推荐|Promoted|广告)$", RegexOption.IGNORE_CASE).find(body) ?: return false
        val key = readingKey(adjacentMatchKey(description).second)
        val actual = directBody(parent, indices, nodes)
        if (actual != null) {
            if (Regex("(?:推荐|Promoted|广告)$", RegexOption.IGNORE_CASE).containsMatchIn(actual)) {
                promoted.remove(key); return false
            }
            val withoutMarker = body.substring(0, marker.range.first)
            if (preferCardBody(withoutMarker, parent, indices, nodes) == actual) {
                promoted += key
                while (promoted.size > 128) promoted.remove(promoted.first())
                return true
            }
        }
        return key in promoted
    }

    private val cardLabels = setOf("显示翻译", "显示原文", "翻译自英语", "Show translation", "Show original", "Translate post", "显示更多", "Show more")
    private fun canonicalBody(text: String): String = text.trim()
        .replace(Regex("\\s*\\n+\\s*"), ". ").replace(Regex("[ \\t]+"), " ")

    /** A card's single direct body TextView is stronger evidence than header decorations in its summary. */
    private fun preferCardBody(body: String, parent: Int, indices: List<Int>, nodes: List<AccessibilityNodeSnapshot>): String {
        val full = preferVisibleText(body, indices, nodes)
        if (full != body.trim()) return full
        val candidate = directBody(parent, indices, nodes) ?: return full
        val canonical = canonicalBody(candidate)
        val summary = canonicalBody(cleanSummaryLabels(body))
        if (summary == canonical) return candidate
        if (!summary.endsWith(". $canonical")) return full
        val prefix = summary.removeSuffix(". $canonical")
        val headerLabel = prefix.length <= 64 && ". " !in prefix && '\n' !in prefix
        val replyHeader = prefix.length <= 160 && Regex("(?:[^.]+\\. )?(?:回复给|Replying to) [@A-Za-z0-9_\\uFEFF ,，]+", RegexOption.IGNORE_CASE).matches(prefix)
        return if (headerLabel || replyHeader) candidate else full
    }

    private fun preferVisibleText(body: String, indices: List<Int>, nodes: List<AccessibilityNodeSnapshot>): String {
        val normalized = normalize(body)
        return indices.firstOrNull {
            val node = nodes[it]
            node.className == "android.widget.TextView" && node.text.isNotBlank() &&
                normalized.isNotEmpty() && normalize(node.text) == normalized
        }?.let { nodes[it].text.trim() } ?: body.trim()
    }

    // Summary adds punctuation between paragraphs; use normalization only to choose equivalent text.
    // Never use it as a post identity or global deduplication key.
    private fun normalize(text: String): String = text.replace(Regex("\\.\\s+"), "").filterNot { it.isWhitespace() }
}
