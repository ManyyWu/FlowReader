package com.flowreader.diagnostics

internal object ReadableText {
    private val links = Regex(
        """(?:https?://|www\.)[^\s<>"，。！？；、（）]+|(?<![A-Za-z0-9_@])(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,24}(?![A-Za-z0-9_-])(?::[0-9]+)?(?:/[^\s<>"，。！？；、（）]*)?""",
        RegexOption.IGNORE_CASE
    )
    fun withoutLinks(text: String): String = text.replace(links, " ")
        .replace(Regex("[ \\t]{2,}"), " ").trim()
}
