package com.flowreader.scroll

import android.view.accessibility.AccessibilityNodeInfo

/** Uses fresh nodes for one action; ambiguous containers are never guessed. */
internal object SinglePageScroll {
    @Suppress("DEPRECATION")
    fun forward(root: AccessibilityNodeInfo, gesture: (android.graphics.Rect) -> Unit,
        onPlan: (String) -> Unit = {}, scrollPercent: Int = 50, onMissingAction: () -> Unit = {}): String {
        val source = root.packageName?.toString()
        if (source !in setOf("com.twitter.android", "com.reddit.frontpage")) return "请先切到 X 或 Reddit"
        val snapshot = com.flowreader.diagnostics.SnapshotStore.latest
            ?: return "暂无正文快照"
        val blocks = com.flowreader.diagnostics.SnapshotStore.latestDisplay.readingBlocks
        val target = ScrollTargets.select(snapshot, blocks)
            ?: return "未找到唯一的正文滚动容器"
        val path = mutableListOf<Int>()
        var index: Int? = target
        while (index != null && snapshot.nodes[index].parentIndex != null) {
            path += snapshot.nodes[index].indexInParent
            index = snapshot.nodes[index].parentIndex
        }
        var current = AccessibilityNodeInfo.obtain(root)
        return try {
            for (slot in path.asReversed()) {
                val next = current.getChild(slot) ?: return "页面结构已变化，请重试"
                current.recycle(); current = next
            }
            val expected = snapshot.nodes[target]
            if (current.viewIdResourceName.orEmpty() != expected.viewId ||
                current.className?.toString() != expected.className || !current.isVisibleToUser ||
                !current.isScrollable) return "正文容器已变化，请重试"
            val profileViewport = ScrollTargets.profileViewport(snapshot, target)
            if (profileViewport != null) {
                var live = AccessibilityNodeInfo.obtain(current)
                try {
                    var ancestor = target
                    while (ancestor != profileViewport) {
                        val next = live.parent ?: return "个人首页结构已变化，请重试"
                        live.recycle(); live = next
                        ancestor = snapshot.nodes[ancestor].parentIndex ?: return "个人首页结构已变化，请重试"
                    }
                    if (!live.isVisibleToUser || live.className?.toString() != snapshot.nodes[profileViewport].className)
                        return "个人首页区域已变化，请重试"
                    val bounds = android.graphics.Rect().also(live::getBoundsInScreen)
                onPlan("个人首页手势目标=${live.className}/${live.viewIdResourceName} bounds=$bounds amount=${scrollPercent.coerceIn(10, 100)}%")
                    gesture(bounds)
                    return "已请求滚动，请观察页面变化"
                } finally { live.recycle() }
            }
            if (current.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                if (source != "com.reddit.frontpage" || expected.viewId != "feed_lazy_column")
                    return "正文容器不支持向前滚动，可能已到底"
                val probe = android.graphics.Rect().also(current::getBoundsInScreen)
                onPlan("Reddit正文容器缺少向前动作，尝试一次手势并验证正文位置变化 bounds=$probe")
                onMissingAction(); gesture(probe)
                return "已请求滚动，请观察页面变化"
            }
            val bounds = android.graphics.Rect().also(current::getBoundsInScreen)
            if (android.os.Build.VERSION.SDK_INT < 35 || !current.isGranularScrollingSupported) {
                onPlan("手势目标=${current.className}/${current.viewIdResourceName} bounds=$bounds amount=${scrollPercent.coerceIn(10, 100)}%")
                gesture(bounds)
                return "已请求滚动，请观察页面变化"
            }
            val amount = scrollPercent.coerceIn(10, 100) / 100f
            val arguments = android.os.Bundle().apply {
                putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT, amount)
                putFloat("androidx.core.view.accessibility.action.ARGUMENT_SCROLL_AMOUNT_FLOAT", amount)
            }
            onPlan("ACTION_SCROLL_FORWARD 目标=${current.className}/${current.viewIdResourceName} bounds=$bounds amount=${scrollPercent.coerceIn(10, 100)}%")
            if (current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, arguments))
                "已请求滚动，请观察页面变化"
            else "滚动未执行，可能已到底"
        } catch (_: RuntimeException) { "页面已变化，请重试" }
        finally { current.recycle() }
    }
}
