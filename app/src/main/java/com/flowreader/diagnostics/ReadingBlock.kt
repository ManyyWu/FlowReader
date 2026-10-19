package com.flowreader.diagnostics

enum class ReadingBlockKind { POST, QUOTE }
/** A local matching hint, not a permanent post ID. Unknown ownership stays null. */
data class ReadingBlock(val text: String, val nodeIndex: Int, val kind: ReadingBlockKind = ReadingBlockKind.POST,
    val key: String? = null, val parentKey: String? = null, val continuitySection: String? = null,
    val continuityText: String? = null)

internal fun readingKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
