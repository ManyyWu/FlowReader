package com.flowreader.speech

/** Injectable scheduler; cancelled or duplicate timer callbacks never advance a later session. */
internal class SimulationTimer(private val schedule: (Long, () -> Unit) -> (() -> Unit)) {
    private var generation = 0L
    private var cancelTask: (() -> Unit)? = null
    fun start(milliseconds: Int, completed: () -> Unit) {
        cancel()
        val ticket = generation
        cancelTask = schedule(milliseconds.coerceIn(100, 5000).toLong()) {
            if (ticket == generation) {
                generation++; cancelTask = null; completed()
            }
        }
    }
    fun cancel() { generation++; cancelTask?.invoke(); cancelTask = null }
}
