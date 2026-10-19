package com.flowreader.scroll

data class ScrollOnlyTarget(val packageName: String, val windowId: Int)

data class ScrollOnlyActionResult(
    val success: Boolean,
    val message: String,
    val target: ScrollOnlyTarget? = null
)
