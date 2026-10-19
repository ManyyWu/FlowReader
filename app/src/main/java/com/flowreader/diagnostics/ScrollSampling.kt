package com.flowreader.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Explicit, app-scoped recording. Immutable snapshots are serialized off the capture/UI threads. */
object ScrollSampling {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var dropped = 0
    private const val MAX_BYTES = 24L * 1024 * 1024
    private const val MAX_FRAMES = 400
    private data class Session(val file: File, val pkg: String, var frames: Int = 0,
        var lastFrameAt: Long = 0, @Volatile var bytes: Long = 0, @Volatile var full: Boolean = false,
        @Volatile var lastSource: String? = null, @Volatile var endReason: String? = null)
    @Volatile private var session: Session? = null
    @Volatile private var recording = false
    @Volatile private var error: String? = null
    val active: Boolean get() = recording

    @Synchronized fun start(context: Context, pkg: String) {
        require(pkg in setOf("com.twitter.android", "com.reddit.frontpage"))
        stop("开始新批次")
        val folder = File(context.filesDir, "scroll-samples").apply { mkdirs() }
        // Keep the last X and Reddit batches plus one previous batch, each bounded to 24 MB.
        worker.execute {
            val files = folder.listFiles()?.filter { it.extension == "ndjson" }?.sortedByDescending { it.lastModified() }.orEmpty()
            val opposite = files.firstOrNull { it.name.startsWith(if (pkg == "com.twitter.android") "reddit-" else "x-") }
            val keep = (listOfNotNull(opposite) + files).distinct().take(2).toSet()
            files.filter { it !in keep }.forEach { it.delete() }
        }
        val file = File(folder, "${if (pkg == "com.twitter.android") "x" else "reddit"}-${System.currentTimeMillis()}.ndjson")
        val next = Session(file, pkg)
        session = next; recording = true; error = null; dropped = 0
        val settings = com.flowreader.settings.AppSettings(context)
        val config = JSONObject().put("simulation", settings.simulatedPlayback).put("simulationDelayMs", settings.simulationDelayMs)
            .put("autoContinue", settings.autoContinue).put("speechRate", settings.speechRate)
            .put("pureScrollMode", settings.overlayScrollMode).put("xCompatibility", settings.xCompatibility)
        val targetVersion = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
        enqueue(next) { JSONObject().put("type", "start").put("package", pkg).put("time", System.currentTimeMillis())
            .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("targetVersion", targetVersion).put("config", config) }
    }

    @Synchronized fun stop(reason: String = "手动结束") {
        val old = session
        if (recording && old != null) {
            old.endReason = reason
            enqueue(old) { JSONObject().put("type", "stop").put("reason", reason).put("time", System.currentTimeMillis()) }
        }
        recording = false
    }

    @Synchronized fun frame(snapshot: ScreenSnapshot, display: SnapshotDisplay,
        entries: List<ReadingSessionCache.Entry>, playback: String, phase: String = "capture", operation: Long? = null, cacheStatus: String = SnapshotStore.readingCache.summary()) {
        val current = session ?: return
        if (!recording || current.full) return
        current.lastSource = snapshot.packageName
        if (snapshot.packageName != current.pkg) return
        val now = android.os.SystemClock.uptimeMillis()
        if (phase == "capture" && now - current.lastFrameAt < 400) return
        if (current.frames >= MAX_FRAMES) { stop("达到400帧上限"); return }
        current.frames++; current.lastFrameAt = now
        val queued = enqueue(current) {
            val blocks = JSONArray()
            display.readingBlocks.forEachIndexed { index, block ->
                blocks.put(JSONObject().put("index", index).put("node", block.nodeIndex)
                    .put("text", block.text).put("key", block.key).put("kind", block.kind.toString())
                    .put("continuitySection", block.continuitySection).put("continuityText", block.continuityText)
                    .put("bounds", snapshot.nodes.getOrNull(block.nodeIndex)?.boundsInScreen))
            }
            val bodyNodes = display.readingBlocks.map { it.nodeIndex }.toSet()
            val nodes = JSONArray()
            snapshot.nodes.forEachIndexed { index, node ->
                nodes.put(JSONObject().put("index", index).put("parent", node.parentIndex)
                    .put("slot", node.indexInParent).put("depth", node.depth).put("children", node.childCount)
                    .put("class", node.className).put("id", node.viewId).put("text", node.text)
                    .put("description", node.contentDescription).put("bounds", node.boundsInScreen)
                    .put("visible", node.isVisibleToUser).put("clickable", node.isClickable).put("scrollable", node.isScrollable)
                    .put("selectedBodyNode", index in bodyNodes).put("selectionEvidence", when {
                        index in bodyNodes -> "selected-body-node"
                        !node.isVisibleToUser -> "invisible"
                        node.text.isBlank() && node.contentDescription.isBlank() -> "empty-node"
                        ReadableText.withoutLinks(node.text.ifBlank { node.contentDescription }).isBlank() -> "link-only"
                        else -> "not-a-selected-body-node; inspect-ancestors-and-parser"
                    }))
            }
            val cache = JSONArray()
            entries.forEach { cache.put(JSONObject().put("id", it.id).put("key", it.block.key)
                .put("textHash", it.block.text.hashCode()).put("textLength", it.block.text.length).put("visible", it.visible).put("bounds", it.lastBounds)) }
            JSONObject().put("type", "frame").put("phase", phase).put("operation", operation)
                .put("time", snapshot.capturedAtMillis).put("uptime", now).put("package", snapshot.packageName)
                .put("window", snapshot.windowId).put("event", snapshot.eventType).put("complete", snapshot.isTraversalComplete)
                .put("diagnostics", snapshot.traversalDiagnostics).put("displayStatus", display.status.toString())
                .put("playback", playback).put("cacheStatus", cacheStatus).put("blocks", blocks).put("nodes", nodes).put("cache", cache)
        }
        if (!queued) current.frames--
        else if (current.frames >= MAX_FRAMES) stop("达到400帧上限")
    }

    @Synchronized fun event(detail: String) {
        val current = session ?: return
        if (!recording || current.full) return
        val at = android.os.SystemClock.uptimeMillis()
        enqueue(current) { JSONObject().put("type", "scroll-event").put("uptime", at).put("detail", detail) }
    }

    private fun enqueue(current: Session, value: () -> JSONObject): Boolean {
        if (pending.incrementAndGet() > 12) { pending.decrementAndGet(); dropped++; return false }
        worker.execute {
            try {
                if (current.full) return@execute
                val bytes = (value().toString() + "\n").toByteArray(Charsets.UTF_8)
                if (current.bytes + bytes.size > MAX_BYTES) { current.full = true; current.endReason = "达到24MB上限"; if (session === current) recording = false; return@execute }
                current.file.appendBytes(bytes); current.bytes += bytes.size
            } catch (failure: Exception) {
                if (session === current) { error = failure.javaClass.simpleName; recording = false }
            } finally { pending.decrementAndGet() }
        }
        return true
    }
    fun summary(): String {
        val current = session ?: return "采样未开始"
        val target = if (current.pkg == "com.twitter.android") "X" else "Reddit"
        val mismatch = current.lastSource?.takeIf { it != current.pkg }?.let {
            "\n注意：当前采集来源为${when (it) { "com.twitter.android" -> "X"; "com.reddit.frontpage" -> "Reddit"; else -> it }}，与目标不符，本批不会记录它。"
        }.orEmpty()
        return "${if (recording) "采样中" else "已结束"} · $target（${current.pkg}） · ${current.frames}/$MAX_FRAMES 帧 · ${current.bytes / 1024}KB/24576KB" +
            "$mismatch${current.endReason?.let { "\n结束原因：$it。仅停止记录，播放需单独停止。" }.orEmpty()}\n后台待写=${pending.get()}，繁忙跳过=$dropped\n${current.file.name}${if (current.full) " · 已到容量上限" else ""}${error?.let { " · 写入失败：$it" }.orEmpty()}"
    }
    fun latestFile(context: Context): File? = session?.file ?: File(context.filesDir, "scroll-samples")
        .listFiles()?.filter { it.extension == "ndjson" }?.maxByOrNull { it.lastModified() }
    fun export(context: Context, file: File, uri: Uri, done: (Boolean) -> Unit) {
        worker.execute {
            val success = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("No destination")
            }.isSuccess
            main.post { done(success) }
        }
    }
}
