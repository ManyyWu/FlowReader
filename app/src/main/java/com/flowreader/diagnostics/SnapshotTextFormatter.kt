package com.flowreader.diagnostics

/** Text presentation only; the original snapshot remains available for diagnostics. */
object SnapshotTextFormatter {
    fun format(snapshot: ScreenSnapshot, excludedNodes: Set<Int> = emptySet()): String {
        return blocks(snapshot, excludedNodes).joinToString("\n\n")
    }

    fun blocks(snapshot: ScreenSnapshot, excludedNodes: Set<Int> = emptySet()): List<String> {
        if (snapshot.packageName == "com.twitter.android")
            return XPostTextExtractor.extract(snapshot, excludedNodes)
        if (snapshot.packageName == "com.reddit.frontpage")
            return RedditTextExtractor.readingBlocks(snapshot, excludedNodes).map { it.text }
        val text = snapshot.nodes.asSequence()
        .map { it.text.ifBlank { it.contentDescription }.trim() }
        .filter { it.isNotEmpty() && it.trimEnd('.', '。').lowercase() !in hiddenLabels }
        .joinToString("\n\n")
        // Other apps do not yet have verified post boundaries.
        return listOf(text).filter { it.isNotBlank() }
    }

    // Match the complete label, so these words inside a post are preserved.
    private val hiddenLabels = setOf(
        "（无文本）", "视频", "图像", "相片", "图片", "gif",
        "转帖", "喜欢", "展示量", "书签", "分享", "关注", "正在关注",
        "发帖选项", "回复", "显示回复", "发布你的回复", "打开完整编辑器",
        "返回", "发帖", "订阅", "订阅者", "已验证", "认证政府账号", "引用", "相关", "查看引用",
        "翻译自英语", "显示原文", "让 grok 解释此帖子", "取消隐藏",
        "video", "image", "photo", "repost", "like", "views", "bookmark", "share",
        "follow", "following", "post options", "reply", "show replies", "post your reply",
        "back", "post", "subscribe", "verified", "show original", "explain this post"
    )
}
