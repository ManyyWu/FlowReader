package com.flowreader.speech

/** Split below the engine limit without losing text or breaking UTF-16 surrogate pairs. */
object SpeechChunks {
    fun split(text: String, maxLength: Int): List<String> {
        require(maxLength >= 2)
        val speechText = normalizeEllipses(removeEmoji(text))
        val result = mutableListOf<String>()
        var start = 0
        while (start < speechText.length) {
            var end = minOf(start + maxLength, speechText.length)
            if (end < speechText.length) {
                val boundary = (end - 1 downTo start + maxLength / 2).firstOrNull {
                    speechText[it] in "。！？.!?；;\n "
                }
                if (boundary != null) end = boundary + 1
                if (Character.isHighSurrogate(speechText[end - 1]) && Character.isLowSurrogate(speechText[end])) end--
            }
            speechText.substring(start, end).takeIf { it.isNotBlank() }?.let(result::add)
            start = end
        }
        return result
    }

    /** Turn long ellipses into a single comma pause without leaving TTS's long ellipsis pause. */
    private fun normalizeEllipses(text: String): String =
        text.replace(Regex("(?:\\s*[.．…⋯]){2,}\\s*"), ", ")

    /** Remove emoji code points and their variation/modifier/joiner marks from spoken text. */
    private fun removeEmoji(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            val keycap = cp == '#'.code || cp == '*'.code || cp in '0'.code..'9'.code
            if (keycap) {
                var end = i + width
                if (end < text.length && text.codePointAt(end) == 0xFE0F) end++
                if (end < text.length && text.codePointAt(end) == 0x20E3) { i = end + 1; continue }
            }
            if (isEmoji(cp)) {
                i += width
                while (i < text.length) {
                    val next = text.codePointAt(i)
                    if (next == 0xFE0E || next == 0xFE0F || next in 0x1F3FB..0x1F3FF ||
                        next in 0xE0020..0xE007F) { i += Character.charCount(next); continue }
                    if (next == 0x200D) {
                        i += Character.charCount(next)
                        if (i < text.length && isEmoji(text.codePointAt(i))) {
                            i += Character.charCount(text.codePointAt(i)); continue
                        }
                    }
                    break
                }
                continue
            }
            if (cp != 0x200D && cp != 0xFE0F) out.appendCodePoint(cp)
            i += width
        }
        return out.toString()
    }

    private fun isEmoji(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || cp in 0x1FC00..0x1FFFD ||
        cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x1F1E6..0x1F1FF ||
        cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
}
