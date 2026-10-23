package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class TapSubmitVerificationTest {
    private val digit = PxRect(10, 10, 30, 30)
    private val submit = PxRect(50, 10, 100, 60)
    private class Driver {
        private data class Task(val time: Long, val order: Int, val action: () -> Unit)
        private val tasks = PriorityQueue(compareBy<Task> { it.time }.thenBy { it.order })
        private var order = 0
        var now = 0L
        val taps = mutableListOf<PxRect>()
        val results = mutableListOf<Boolean>()
        var verify: ((Boolean) -> Unit)? = null
        var guard = true
        private fun schedule(delay: Long, action: () -> Unit) { tasks += Task(now + delay, order++, action) }
        val sequence = TapSequence(
            dispatch = { box, duration, complete -> taps += box; schedule(duration) { complete(true) }; true },
            schedule = ::schedule,
        )
        fun start(boxes: List<PxRect>) = sequence.start(boxes, submitLast = true,
            canContinue = { guard }, beforeSubmit = { verify = it }, result = { results += it })
        fun until(deadline: Long) {
            while (tasks.isNotEmpty() && tasks.peek().time <= deadline) {
                val task = tasks.remove(); now = task.time; task.action()
            }
            now = deadline
        }
    }

    @Test fun confirmationWaitsForFieldReadbackAndIsDispatchedExactlyOnce() {
        val d = Driver()
        assertTrue(d.start(listOf(digit, digit, submit)))
        d.until(500)
        assertEquals(listOf(digit, digit), d.taps)
        assertNotNull(d.verify)
        assertTrue(d.results.isEmpty())
        val callback = d.verify!!
        callback(true)
        callback(true)
        d.until(1000)
        assertEquals(listOf(digit, digit, submit), d.taps)
        assertEquals(listOf(true), d.results)
    }

    @Test fun mismatchedFieldStopsWithoutSubmittingOrReplayingDigits() {
        val d = Driver(); d.start(listOf(digit, submit)); d.until(300)
        d.verify!!(false); d.until(3000)
        assertEquals(listOf(digit), d.taps)
        assertEquals(listOf(false), d.results)
    }

    @Test fun cancellationOrTimeoutRejectsALateSuccessfulReadback() {
        for (cancel in listOf(false, true)) {
            val d = Driver(); d.start(listOf(digit, submit)); d.until(300)
            val callback = d.verify!!
            if (cancel) d.sequence.cancel() else d.until(3000)
            callback(true)
            d.until(4000)
            assertEquals(listOf(digit), d.taps)
            assertEquals(listOf(false), d.results)
        }
    }

    @Test fun changedScreenAfterSuccessfulReadbackStillBlocksSubmit() {
        val d = Driver(); d.start(listOf(digit, submit)); d.until(300)
        d.guard = false
        d.verify!!(true)
        d.until(3000)
        assertEquals(listOf(digit), d.taps)
        assertEquals(listOf(false), d.results)
    }

    @Test fun oldReadbackCannotCompleteADifferentNewRun() {
        val d = Driver(); d.start(listOf(digit, submit)); d.until(300)
        val old = d.verify!!
        d.sequence.cancel()
        d.start(listOf(digit, submit)); d.until(600)
        val fresh = d.verify!!
        old(true)
        assertEquals(listOf(digit, digit), d.taps)
        fresh(true); d.until(1000)
        assertEquals(listOf(digit, digit, submit), d.taps)
        assertEquals(listOf(false, true), d.results)
    }
}
