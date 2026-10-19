package com.flowreader.scroll

/** A long text node can expose identical text and clipped bounds after real scrolling. */
internal class ScrollMovementEvidence {
    private var moved = false
    fun observe(deltaX: Int, deltaY: Int): Boolean {
        // Undefined event deltas are represented by -1, not evidence of movement.
        val valid = (deltaX != 0 && deltaX != -1) || (deltaY != 0 && deltaY != -1)
        if (valid) moved = true
        return valid
    }
    /** TYPE_VIEW_SCROLLED itself is evidence when the app omits optional offsets. */
    fun observeEvent(deltaX: Int, deltaY: Int): Boolean {
        val reported = observe(deltaX, deltaY)
        val omitted = deltaX == -1 && deltaY == -1
        if (omitted) moved = true
        return reported || omitted
    }
    fun changed(contentChanged: Boolean): Boolean = contentChanged || moved
    fun reset() { moved = false }
}
