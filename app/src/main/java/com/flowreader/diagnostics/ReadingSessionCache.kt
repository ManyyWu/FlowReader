package com.flowreader.diagnostics

/** Ordered content for one page session. Screen coordinates never identify an entry. */
class ReadingSessionCache(private val maxEntries: Int = 200, private val maxCharacters: Int = 100_000) {
    data class Entry(val id: Long, val block: ReadingBlock, val lastBounds: String?, val visible: Boolean)
    enum class Direction { FORWARD, BACKWARD, STILL, UNKNOWN }
    private var nextId = 0L
    private var context: String? = null
    private var identity: String? = null
    private var items = emptyList<Entry>()
    private var protected = emptySet<Long>()
    private var excluded = emptySet<Long>()
    val excludedIds: Set<Long> get() = excluded
    private var confirmedVisibleIds = emptyList<Long?>()
    var visibleIds: List<Long?> = emptyList()
        private set
    var pending = emptyList<ReadingBlock>()
        private set
    var rejectionReason: String? = null
        private set
    var direction = Direction.STILL
        private set
    val entries: List<Entry> get() = items.toList()
    val atTail: Boolean get() = items.isNotEmpty() && pending.isEmpty() && visibleIds.lastOrNull() == items.lastOrNull()?.id
    fun canAdvanceForward(browsingBack: Boolean): Boolean = items.isNotEmpty() && pending.isEmpty() && !browsingBack
    fun summary() = "阅读缓存：${items.size}段/${items.sumOf { it.block.text.length }}字，待确认=${pending.size}，方向=$direction，位于尾部=$atTail${rejectionReason?.let { "，未确认原因=$it" }.orEmpty()}"
    fun format(): String = buildString {
        appendLine(summary())
        items.forEach { appendLine("id=${it.id} 可见=${it.visible} 最近位置=${it.lastBounds} key=${it.block.key}\n${it.block.text}") }
        if (pending.isNotEmpty()) { appendLine("待确认内容（未加入播放队列）"); pending.forEach { appendLine(it.text) } }
    }
    fun clear() {
        context = null; identity = null; items = emptyList(); visibleIds = emptyList(); rejectionReason = null
        pending = emptyList(); protected = emptySet(); excluded = emptySet(); confirmedVisibleIds = emptyList(); direction = Direction.STILL
    }
    fun excludeKeys(keys: Set<String>) {
        pending = pending.filter { it.key !in keys && it.parentKey !in keys }
        if (pending.isEmpty()) rejectionReason = null
        val removed = items.filter { it.block.key in keys || it.block.parentKey in keys }.map { it.id }.toSet()
        if (removed.isEmpty()) return
        excluded = (excluded + removed).sorted().takeLast(400).toSet()
        items = items.filter { it.id !in removed }
        visibleIds = visibleIds.map { if (it in removed) null else it }
        confirmedVisibleIds = confirmedVisibleIds.filter { it !in removed }
        protected = protected - removed
    }
    fun protect(ids: Set<Long>) { protected = ids; trim() }
    fun observePage(page: String, knownIdentity: String?): Boolean {
        val changed = context != page || (identity != null && knownIdentity != null && identity != knownIdentity)
        if (changed) clear()
        context = page
        if (knownIdentity != null) identity = knownIdentity
        return changed
    }
    fun incomplete(count: Int) { visibleIds = List(count) { null }; direction = Direction.UNKNOWN }
    fun adopt(blocks: List<ReadingBlock>, bounds: List<String?>) {
        items = blocks.mapIndexed { index, block -> Entry(++nextId, block, bounds.getOrNull(index), true) }
        visibleIds = items.map { it.id }; confirmedVisibleIds = visibleIds; pending = emptyList(); rejectionReason = null; direction = Direction.STILL; trim()
    }
    /** verifiedPairs refer to the preceding complete viewport, whose IDs are supplied by the caller. */
    fun ingest(blocks: List<ReadingBlock>, bounds: List<String?>,
        verifiedIds: Map<Int, Long> = emptyMap(), appendWithoutOverlap: Boolean = false): Boolean {
        if (blocks.isEmpty()) { visibleIds = emptyList(); return false }
        if (items.isEmpty()) { adopt(blocks, bounds); return true }
        val oldVisible = confirmedVisibleIds
        val anchors = mutableMapOf<Int, Long>()
        blocks.forEachIndexed { index, block ->
            if (block.key != null && blocks.count { it.key == block.key && sameContent(it, block) } == 1) {
                items.filter { it.block.key == block.key && sameContent(it.block, block) }
                    .singleOrNull()?.let { anchors[index] = it.id }
            }
        }
        verifiedIds.forEach { (index, id) ->
            val old = items.singleOrNull { it.id == id }
            if (old != null && blocks.getOrNull(index)?.let { sameContent(it, old.block) } == true) anchors[index] = id
        }
        // Repeated table cells share a body key. Locate each occurrence only between
        // two ordered, independently confirmed neighbours in the same original body.
        val initialPositions = anchors.toSortedMap().values.map { id -> items.indexOfFirst { it.id == id } }
        if (initialPositions.size >= 2 && initialPositions.zipWithNext().all { (a, b) -> a < b }) {
            blocks.forEachIndexed { index, block ->
                if (index in anchors || block.key == null || block.continuitySection != "reddit:original") return@forEachIndexed
                val left = anchors.keys.filter { it < index }.maxOrNull() ?: return@forEachIndexed
                val right = anchors.keys.filter { it > index }.minOrNull() ?: return@forEachIndexed
                val oldLeft = items.indexOfFirst { it.id == anchors[left] }
                val oldRight = items.indexOfFirst { it.id == anchors[right] }
                if (oldLeft >= oldRight || blocks[left].continuitySection != block.continuitySection ||
                    blocks[right].continuitySection != block.continuitySection) return@forEachIndexed
                fun matches(other: ReadingBlock) = other.key == block.key &&
                    other.continuitySection == block.continuitySection && sameContent(other, block)
                val candidates = ((oldLeft + 1) until oldRight).filter { matches(items[it].block) }
                if (candidates.size == 1 && ((left + 1) until right).count { matches(blocks[it]) } == 1) {
                    val id = items[candidates.single()].id
                    if (id !in anchors.values) anchors[index] = id
                }
            }
        }
        // A clipped Reddit comment may expose its author only on the next viewport.
        // Extend a confirmed chain by immediate neighbours only, including transition frames
        // where just the original body is still visible above a clipped comment.
        val keyed = anchors.toSortedMap().values.map { id -> items.indexOfFirst { it.id == id } }
        if (keyed.isNotEmpty() && keyed.distinct().size == keyed.size &&
            keyed.zipWithNext().all { (a, b) -> a < b }) {
            var extended: Boolean
            do {
                extended = false
                blocks.forEachIndexed { index, block ->
                    if (index in anchors || block.continuitySection != "reddit:comment") return@forEachIndexed
                    val neighbours = listOfNotNull(
                        anchors[index - 1]?.let { id -> items.indexOfFirst { it.id == id } + 1 },
                        anchors[index + 1]?.let { id -> items.indexOfFirst { it.id == id } - 1 }
                    ).distinct()
                    val slot = neighbours.singleOrNull() ?: return@forEachIndexed
                    val old = items.getOrNull(slot) ?: return@forEachIndexed
                    if (old.id in anchors.values || old.block.continuitySection != block.continuitySection ||
                        (block.key != null && old.block.key != null) || !sameContent(block, old.block)) return@forEachIndexed
                    if (blocks.count { sameContent(it, block) } != 1 ||
                        items.count { sameContent(it.block, block) } != 1) return@forEachIndexed
                    anchors[index] = old.id
                    extended = true
                }
            } while (extended)
        }
        val ordered = anchors.toSortedMap().entries.toList()
        val positions = ordered.map { pair -> items.indexOfFirst { it.id == pair.value } }
        val usable = positions.distinct().size == positions.size && positions.zipWithNext().all { (a, b) -> a < b }
        val canAppend = ordered.isEmpty() && appendWithoutOverlap && oldVisible.lastOrNull() == items.last().id
        if (!usable) return reject(blocks, "已确认正文的顺序发生冲突")
        if (ordered.isEmpty() && !canAppend) return reject(blocks, "没有可确认的重叠正文")
        if (ordered.isNotEmpty()) {
            if ((ordered.first().key > 0 && positions.first() != 0) ||
                (ordered.last().key < blocks.lastIndex && positions.last() != items.lastIndex)) return reject(blocks, "新增内容尚不能定位到缓存边界")
            ordered.zipWithNext().forEachIndexed { index, (a, b) ->
                if (b.key - a.key > 1 && positions[index + 1] - positions[index] > 1) return reject(blocks, "重叠正文之间存在未定位条目")
            }
        }
        val firstOld = oldVisible.firstNotNullOfOrNull { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        val firstPosition = positions.firstOrNull()
        direction = when {
            canAppend -> Direction.FORWARD
            ordered.firstOrNull()?.key?.let { it > 0 } == true -> Direction.BACKWARD
            firstOld != null && firstPosition != null && firstPosition < firstOld -> Direction.BACKWARD
            firstOld != null && firstPosition != null && firstPosition > firstOld -> Direction.FORWARD
            else -> {
                val a = ordered.firstOrNull()
                val oldTop = items.firstOrNull { it.id == a?.value }?.lastBounds?.let(::top)
                val newTop = a?.key?.let { bounds.getOrNull(it) }?.let(::top)
                when { oldTop == null || newTop == null || oldTop == newTop -> Direction.STILL
                    newTop > oldTop -> Direction.BACKWARD
                    else -> Direction.FORWARD }
            }
        }
        val result = items.map { it.copy(visible = false) }.toMutableList()
        val ids = mutableListOf<Long>()
        blocks.forEachIndexed { index, block ->
            val id = anchors[index]
            if (id != null) {
                val slot = result.indexOfFirst { it.id == id }
                val old = result[slot]
                result[slot] = old.copy(block = block.copy(key = block.key ?: old.block.key), lastBounds = bounds.getOrNull(index), visible = true)
                ids += id
            } else {
                val nextAnchor = ordered.firstOrNull { it.key > index }?.value
                val slot = nextAnchor?.let { anchor -> result.indexOfFirst { it.id == anchor } } ?: result.size
                val entry = Entry(++nextId, block, bounds.getOrNull(index), true)
                result.add(slot, entry); ids += entry.id
            }
        }
        items = result; visibleIds = ids; confirmedVisibleIds = visibleIds; pending = emptyList(); rejectionReason = null; trim(); return true
    }
    private fun sameContent(a: ReadingBlock, b: ReadingBlock): Boolean =
        a.kind == b.kind && (a.text == b.text ||
            (!a.continuityText.isNullOrBlank() && a.continuityText == b.continuityText))

    private fun reject(blocks: List<ReadingBlock>, reason: String): Boolean {
        rejectionReason = reason
        pending = blocks.take(200); visibleIds = List(blocks.size) { null }; direction = Direction.UNKNOWN
        items = items.map { it.copy(visible = false) }; return false
    }
    private fun trim() {
        val values = items.toMutableList()
        while (values.size > maxEntries || values.sumOf { it.block.text.length } > maxCharacters) {
            if (values.size <= 1) break // Never truncate the only paragraph to enforce a character budget.
            val index = values.indexOfFirst { it.id !in protected && it.id !in visibleIds }
                .takeIf { it >= 0 } ?: values.indexOfFirst { it.id !in protected }.takeIf { it >= 0 } ?: break
            values.removeAt(index)
        }
        items = values
    }
    private fun top(bounds: String): Int? = Regex("""\[-?\d+,(-?\d+)\]""").find(bounds)?.groupValues?.get(1)?.toIntOrNull()
}
