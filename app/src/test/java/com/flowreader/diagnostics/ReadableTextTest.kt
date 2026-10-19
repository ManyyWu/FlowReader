package com.flowreader.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableTextTest {
    @Test fun removesLinksWithoutEatingSurroundingChineseText() {
        assertEquals("正文 ，后文", ReadableText.withoutLinks("正文https://t.co/abc，后文"))
        assertEquals("see now", ReadableText.withoutLinks("see www.example.com/a now"))
        assertEquals("", ReadableText.withoutLinks("facebook.com"))
    }
    @Test fun preservesOrdinaryTextNumbersAndEmail() {
        assertEquals("价格 1.25，a@example.com", ReadableText.withoutLinks("价格 1.25，a@example.com"))
    }
}
