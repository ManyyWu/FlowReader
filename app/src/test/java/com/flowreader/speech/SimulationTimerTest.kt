package com.flowreader.speech

import org.junit.Assert.*
import org.junit.Test

class SimulationTimerTest {
    private class Scheduler {
        data class Task(val delay: Long, val complete: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        val timer = SimulationTimer { delay, complete ->
            val task = Task(delay, complete)
            tasks += task
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        }
    }

    @Test fun completionRunsOnlyOnce() {
        val s = Scheduler(); var calls = 0
        s.timer.start(500) { calls++ }
        assertEquals(500L, s.tasks.single().delay)
        s.tasks.single().complete(); s.tasks.single().complete()
        assertEquals(1, calls)
    }

    @Test fun stopAndRestartRejectStaleCallbacks() {
        val s = Scheduler(); var calls = 0
        s.timer.start(300) { calls += 100 }
        s.timer.cancel()
        s.timer.start(200) { calls++ }
        assertTrue(s.tasks.first().cancelled)
        s.tasks.first().complete(); s.tasks.last().complete()
        assertEquals(1, calls)
    }

    @Test fun invalidDelaysAreBounded() {
        val s = Scheduler()
        s.timer.start(-1) {}
        s.timer.start(10000) {}
        assertEquals(listOf(100L, 5000L), s.tasks.map { it.delay })
    }

    @Test fun completionCanScheduleNextBlockWithoutOldCallbackAdvancingIt() {
        val s = Scheduler(); var calls = 0
        s.timer.start(300) {
            calls++
            s.timer.start(400) { calls++ }
        }
        s.tasks.first().complete()
        s.tasks.first().complete()
        assertEquals(1, calls)
        s.tasks.last().complete()
        assertEquals(2, calls)
    }
}
