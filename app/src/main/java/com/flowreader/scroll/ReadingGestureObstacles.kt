package com.flowreader.scroll

import com.flowreader.diagnostics.ScreenSnapshot

/** Media labels are separate non-text nodes; ordinary paragraphs mentioning video stay usable. */
internal object ReadingGestureObstacles {
    fun media(snapshot: ScreenSnapshot): List<HalfScrollGeometry.Box> {
        if (snapshot.packageName != "com.twitter.android") return emptyList()
        return snapshot.nodes.mapNotNull { node ->
            if (!node.isVisibleToUser || node.text.isNotBlank() || node.className == "android.widget.TextView") return@mapNotNull null
            val label = node.contentDescription.trim().trimEnd('.', '。')
            if (label !in setOf("视频", "图像", "图片", "Video", "Image", "GIF") &&
                !label.startsWith("GIF：") && !label.startsWith("GIF:")) return@mapNotNull null
            ReadingViewportLayout.box(node.boundsInScreen)?.let { HalfScrollGeometry.Box(it.left, it.top, it.right, it.bottom) }
        }
    }
}
