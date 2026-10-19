package com.flowreader.diagnostics

/** Keeps Reddit text in per-node blocks and drops recognizable post metadata/actions. */
internal object RedditTextExtractor {
    private val bodyContainers = setOf("comment_body_tag", "post_self_content", "post_title")
    private val time = Regex("""^(?:刚刚|昨天|前天|[0-9]+\s*(?:秒|分钟|分|小时|时|天|周|个月|月|年)前|[0-9]+\s*(?:秒|分钟|小时|天|周|月|年)|[0-9]+\s*[smhd])$""", RegexOption.IGNORE_CASE)
    private val community = Regex("""^r/[A-Za-z0-9_]+$""", RegexOption.IGNORE_CASE)
    private val galleryDescription = Regex("""^[0-9]+\s*/\s*[0-9]+\s*[,，]\s*图像\s*[,，]\s*在图像库中$""")
    private val counts = Regex("""^(?:(?:[0-9,.]+|已赞|投票)\s*(?:票|分|分数|赞|votes?|points?)\s*[,，·|]?\s*)?(?:(?:[0-9,.]+)\s*(?:条)?\s*(?:评论|条评论|comments?|replies))$""", RegexOption.IGNORE_CASE)
    private val countParts = Regex("""^(?:[0-9,.]+\s*(?:票|分|分数|赞|votes?|points?)|[0-9,.]+\s*(?:条)?\s*(?:评论|comments?|replies))$""", RegexOption.IGNORE_CASE)
    private val actions = setOf(
        "评论", "回复", "分享", "转发", "保存", "隐藏", "举报", "投票", "赞", "踩", "更多",
        "查看讨论", "评论区", "刷新", "返回", "搜索", "排序", "帖子菜单", "正在评论", "加入对话",
        "展开上下文", "面板句柄", "添加链接", "添加图像", "添加 gif", "添加视频", "发送评论", "下一条评论", "•", "●", "▪", "◦",
        "comments", "reply", "share", "save", "add gif", "add video", "send comment",
        "hide", "report", "upvote", "downvote", "more", "refresh", "back", "search", "sort"
    )
    private val metadataContainers = setOf(
        "comment_header", "post_header_info", "post_metadata", "x-post_metadata_bottom"
    )
    private val adContainers = setOf("conversation_screen_ad", "promoted_post", "sponsored_post", "promoted_post_unit")
    private val commentSummary = Regex("""^.+?\s*的\s*[0-9]+\s*级评论(?:[,，].*)?$""")
    private val badge = Regex("""^(?:成就|achievement).*(?:徽章|badge)$""", RegexOption.IGNORE_CASE)
    private val accountLabel = Regex("""^[A-Za-z0-9_ .-]{1,32}\s+(?:账户|账号|account)$""", RegexOption.IGNORE_CASE)
    private val postStats = Regex("""^[0-9,.]+\s*(?:票|分|分数|赞|votes?|points?)\s*[,，·|]\s*[0-9,.]+\s*(?:条)?\s*(?:评论|comments?|replies)(?:\s*[,，·|]\s*[0-9,.]+\s*(?:次)?\s*(?:分享|shares?))?$""", RegexOption.IGNORE_CASE)
    private val metricSequence = Regex(
        """^(?:[0-9,.]+\s*(?:个\s*)?(?:条评论|评论|票|赞同|反对|赞|次转载|次分享|转载|分享|votes?|points?|comments?|replies)\s*[,，·|]?\s*)+$""",
        RegexOption.IGNORE_CASE
    )

    fun readingBlocks(snapshot: ScreenSnapshot, excludedNodes: Set<Int> = emptySet()): List<ReadingBlock> {
        val nodes = snapshot.nodes
        val detail = nodes.any { it.viewId == "post_detail_scaffold" }
        return nodes.indices.asSequence()
            .filter { it !in excludedNodes }
            .mapNotNull { index ->
                val node = nodes[index]
                if (!node.isVisibleToUser) return@mapNotNull null
                val text = node.text.ifBlank {
                    // Container descriptions often repeat the whole page or post; keep leaf descriptions only.
                    if (node.childCount == 0) node.contentDescription else ""
                }.trim()
                if (text.isBlank() || isMetadata(snapshot, index, text) ||
                    (detail && !insideBody(snapshot, index))) return@mapNotNull null
                val owner = owner(snapshot, index)
                val structure = originalStructure(snapshot, index).takeIf { it.isNotEmpty() }?.let { "$it:" }.orEmpty()
                splitParagraphs(text).map { part ->
                    ReadingBlock(part, index, key = owner?.let { readingKey("reddit:$it:$structure${node.viewId}:$part") },
                        continuitySection = section(snapshot, index))
                }
            }.flatten().toList()
    }

    // Formatted original posts expose headings directly and table cells inside a wrapper.
    // Slots, child counts and coordinates change as earlier rows leave the viewport.
    private fun originalStructure(snapshot: ScreenSnapshot, index: Int): String {
        var current: Int? = index
        var budget = snapshot.nodes.size
        val path = mutableListOf<String>()
        while (current != null && budget-- > 0) {
            val node = snapshot.nodes.getOrNull(current) ?: return ""
            if (node.viewId == "comment_layout") return ""
            if (node.viewId == "formatted_fbp_body") return "formatted:" + path.joinToString("/")
            if (node.viewId == "post_self_content") return ""
            path += "${node.className}:${node.viewId}"
            current = node.parentIndex
        }
        return ""
    }

    private fun insideBody(snapshot: ScreenSnapshot, index: Int): Boolean {
        var current: Int? = index
        var remaining = snapshot.nodes.size
        while (current != null && remaining-- > 0) {
            val node = snapshot.nodes.getOrNull(current) ?: return false
            if (node.viewId in bodyContainers) return true
            current = node.parentIndex
        }
        return false
    }

    // Author evidence comes from the containing comment/post, never its screen coordinates.
    private fun section(snapshot: ScreenSnapshot, index: Int): String? {
        var current: Int? = index
        while (current != null) {
            when (snapshot.nodes[current].viewId) {
                "comment_layout" -> return "reddit:comment"
                "post_self_content" -> return "reddit:original"
            }
            current = snapshot.nodes[current].parentIndex
        }
        return null
    }

    private fun owner(snapshot: ScreenSnapshot, index: Int): String? {
        val nodes = snapshot.nodes
        var root: Int? = index
        while (root != null) {
            val id = nodes[root].viewId
            // Original paragraphs are scoped by the reading session's page boundary.
            // Reddit recycles the author/title header while these paragraphs remain visible.
            if (id == "post_title") return "post-title"
            if (id == "post_self_content") return "original-body"
            if (id == "comment_layout" || id == "post_detail_section") {
                val depth = nodes[root].depth
                val descendants = nodes.drop(root + 1).takeWhile { it.depth > depth }
                return if (id == "comment_layout") descendants.firstNotNullOfOrNull { node ->
                    Regex("^(.+?) 的 ([0-9]+) 级评论").find(node.contentDescription)?.let {
                        "${it.groupValues[1]}:${it.groupValues[2]}"
                    }
                }?.let { "comment:$it" }
                else descendants.firstNotNullOfOrNull { node ->
                    Regex("发帖者[：:]\\s*(u/[A-Za-z0-9_-]+)").find(node.contentDescription)?.groupValues?.get(1)
                }?.let { "post:$it" }
            }
            root = nodes[root].parentIndex
        }
        return null
    }

    private fun isMetadata(snapshot: ScreenSnapshot, index: Int, value: String): Boolean {
        val nodes = snapshot.nodes
        val text = normalizeWhitespace(value).trim().trimEnd('.', '。')
        val id = nodes[index].viewId.lowercase()
        val node = nodes[index]
        // A media accessibility label is not a comment paragraph or a continuity anchor.
        if (id == "image_content_tag" && node.text.isBlank() && text.lowercase() in setOf("图像", "image", "gif")) return true
        if (node.text.isBlank() && node.childCount == 0 && node.isClickable &&
            nodes.getOrNull(node.parentIndex ?: -1)?.viewId == "post_content" &&
            galleryDescription.matches(text)) return true
        var parent = nodes[index].parentIndex
        var remaining = nodes.size
        while (parent != null && remaining-- > 0) {
            val ancestorId = nodes.getOrNull(parent)?.viewId?.lowercase() ?: break
            if (ancestorId in adContainers || ancestorId in metadataContainers) return true
            parent = nodes[parent].parentIndex
        }
        if (id.contains("flair") || id.contains("timestamp") || id.contains("subreddit") ||
            id.contains("vote") || id.contains("comment_count") || id.contains("post_metadata") ||
            id.contains("post_domain") || id == "x-post_unit_title_thumbnail_subtitle" ||
            id.contains("comment_author") || id.contains("role_indicator") || id.contains("fbp_comment_footer") ||
            id.contains("ad_attribution") || id.contains("sponsor_label") || id.contains("promoted_label")) return true
        if (text.lowercase() in actions || time.matches(text) || community.matches(text) ||
            counts.matches(text) || countParts.matches(text) || commentSummary.matches(text) || badge.matches(text) ||
            accountLabel.matches(text) || postStats.matches(text) || metricSequence.matches(text) || text.startsWith("发帖者:") ||
            text.startsWith("发帖者：")) return true
        if (text.matches(Regex("""^(?:r/[A-Za-z0-9_]+\s*[·•|,，]\s*)?(?:[0-9]+\s*(?:票|分|赞|votes?|points?)\s*[,，·|]\s*)?[0-9]+\s*(?:条)?\s*(?:评论|comments?|replies)$""", RegexOption.IGNORE_CASE))) return true
        return false
    }

    private fun normalizeWhitespace(text: String): String = buildString(text.length) {
        text.forEach { append(if (it == '\u00A0' || it == '\u202F' || it.isWhitespace()) ' ' else it) }
    }

    private fun splitParagraphs(text: String): List<String> = text.split(Regex("\\n{2,}"))
        .map(String::trim).filter(String::isNotBlank)
}
