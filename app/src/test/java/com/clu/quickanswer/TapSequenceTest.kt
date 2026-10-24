package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class TapSequenceTest {
    private val one = PxRect(10, 20, 30, 50)
    private val zero = PxRect(40, 20, 60, 50)
    private val submit = PxRect(80, 20, 120, 70)

    private class Clock {
        private data class Pending(val due: Long, val order: Int, val action: () -> Unit)
        private val pending = PriorityQueue(compareBy<Pending> { it.due }.thenBy { it.order })
        private var serial = 0
        var now = 0L
            private set

        fun schedule(delay: Long, action: () -> Unit) {
            pending += Pending(now + delay, serial++, action)
        }

        fun runUntil(until: Long = Long.MAX_VALUE) {
            var turns = 0
            while (pending.isNotEmpty() && pending.peek().due <= until) {
                check(turns++ < 1_000) { "Unbounded rescheduling" }
                val next = pending.remove()
                now = next.due
                next.action()
            }
            if (until != Long.MAX_VALUE) now = until
        }
    }

    private data class Press(
        val box: PxRect, val at: Long, val duration: Long, val complete: (Boolean) -> Unit,
    )

    private class Harness {
        val clock = Clock()
        val presses = mutableListOf<Press>()
        val results = mutableListOf<Boolean>()
        var autoComplete = true
        var rejectIndex: Int? = null
        val sequence = TapSequence(
            dispatch = { box, duration, complete ->
                val index = presses.size
                presses += Press(box, clock.now, duration, complete)
                if (index == rejectIndex) false else {
                    if (autoComplete) clock.schedule(duration) { complete(true) }
                    true
                }
            },
            schedule = clock::schedule,
        )

        fun start(
            boxes: List<PxRect>, submitLast: Boolean = false, guard: () -> Boolean = { true },
        ): Boolean = sequence.start(boxes, submitLast, guard) { results += it }
    }

    @Test fun repeatedDigitsAndLeadingZeroKeepTheirOrderAndAnUpGap() {
        val h = Harness()
        val boxes = listOf(zero, zero, one, one, submit)
        assertTrue(h.start(boxes, submitLast = true))
        h.clock.runUntil()
        assertEquals(boxes, h.presses.map { it.box })
        assertEquals(listOf(0L, 85L, 170L, 255L, 420L), h.presses.map { it.at })
        assertTrue(h.presses.all { it.duration == 45L })
        assertEquals(listOf(true), h.results)
    }

    @Test fun nineTapResetAndFourDigitEntryCompletesWithoutOverlappingTouches() {
        val h = Harness()
        val boxes = List(4) { zero } + List(4) { one } + submit
        assertTrue(h.start(boxes, submitLast = true))
        h.clock.runUntil()
        assertEquals(boxes, h.presses.map { it.box })
        assertEquals(760L, h.presses.last().at)
        assertTrue(h.presses.zipWithNext().all { (a, b) -> b.at >= a.at + a.duration + 40L })
        assertEquals(listOf(true), h.results)
    }

    @Test fun theNextTapWaitsForActualCompletion() {
        val h = Harness().apply { autoComplete = false }
        assertTrue(h.start(listOf(one, zero)))
        h.clock.runUntil(300L)
        assertEquals(1, h.presses.size)
        h.presses[0].complete(true)
        h.clock.runUntil(339L)
        assertEquals(1, h.presses.size)
        h.clock.runUntil(340L)
        assertEquals(2, h.presses.size)
        h.presses[1].complete(true)
        h.clock.runUntil()
        assertEquals(listOf(true), h.results)
    }

    @Test fun rejectedPlatformDispatchStopsWithoutSkippingOrSubmitting() {
        val h = Harness().apply { rejectIndex = 1 }
        assertTrue(h.start(listOf(one, zero, one, submit), submitLast = true))
        h.clock.runUntil()
        assertEquals(listOf(one, zero), h.presses.map { it.box })
        assertEquals(listOf(false), h.results)
    }

    @Test fun firstDispatchFailureStillReportsAnAcceptedRunOnce() {
        val h = Harness().apply { rejectIndex = 0 }
        assertTrue(h.start(listOf(one, submit), submitLast = true))
        h.clock.runUntil()
        assertEquals(1, h.presses.size)
        assertEquals(listOf(false), h.results)
    }

    @Test fun guardIsCheckedBeforeEveryDigitAndSubmit() {
        val h = Harness()
        var calls = 0
        assertTrue(h.start(listOf(one, zero, submit), submitLast = true) { ++calls < 3 })
        h.clock.runUntil()
        assertEquals(3, calls)
        assertEquals(listOf(one, zero), h.presses.map { it.box })
        assertEquals(listOf(false), h.results)
    }

    @Test fun changedScreenBeforeTheFirstTapDoesNotTouchAnything() {
        val h = Harness()
        assertTrue(h.start(listOf(one, zero)) { false })
        assertTrue(h.presses.isEmpty())
        assertEquals(listOf(false), h.results)
    }

    @Test fun cancellationDuringTheReleaseGapSuppressesAllFollowingKeys() {
        val h = Harness()
        assertTrue(h.start(listOf(one, zero, submit), submitLast = true))
        h.clock.runUntil(50L)
        h.sequence.cancel()
        h.sequence.cancel()
        h.clock.runUntil()
        assertEquals(listOf(one), h.presses.map { it.box })
        assertEquals(listOf(false), h.results)
    }

    @Test fun lateAndDuplicateCallbacksCannotAlterANewerRun() {
        val h = Harness().apply { autoComplete = false }
        assertTrue(h.start(listOf(one, zero, submit), submitLast = true))
        val old = h.presses.single().complete
        h.sequence.cancel()
        assertTrue(h.start(listOf(zero)))
        old(true)
        old(false)
        h.presses.last().complete(true)
        h.presses.last().complete(false)
        h.clock.runUntil()
        assertEquals(listOf(one, zero), h.presses.map { it.box })
        assertEquals(listOf(false, true), h.results)
    }

    @Test fun duplicateCompletionCannotScheduleADuplicateDigitOrSubmission() {
        val h = Harness().apply { autoComplete = false }
        assertTrue(h.start(listOf(one, submit), submitLast = true))
        h.presses.single().complete(true)
        h.presses.single().complete(true)
        h.clock.runUntil(120L)
        assertEquals(listOf(one, submit), h.presses.map { it.box })
        h.presses.last().complete(true)
        h.presses.last().complete(true)
        h.clock.runUntil()
        assertEquals(2, h.presses.size)
        assertEquals(listOf(true), h.results)
    }

    @Test fun aMissingCallbackTimesOutWithoutReplayingTheDigit() {
        val h = Harness().apply { autoComplete = false }
        assertTrue(h.start(listOf(one, zero, submit), submitLast = true))
        h.clock.runUntil(749L)
        assertTrue(h.results.isEmpty())
        h.clock.runUntil(750L)
        assertEquals(listOf(false), h.results)
        h.presses.single().complete(true)
        h.clock.runUntil()
        assertEquals(1, h.presses.size)
        assertEquals(listOf(false), h.results)
    }

    @Test fun failedConfirmationIsNotRetried() {
        val h = Harness().apply { autoComplete = false }
        assertTrue(h.start(listOf(one, submit), submitLast = true))
        h.presses.single().complete(true)
        h.clock.runUntil(120L)
        h.presses.last().complete(false)
        h.presses.last().complete(true)
        h.clock.runUntil()
        assertEquals(listOf(one, submit), h.presses.map { it.box })
        assertEquals(listOf(false), h.results)
    }

    @Test fun invalidOrConcurrentRequestsAreRefusedWithNoExtraCallback() {
        val h = Harness().apply { autoComplete = false }
        assertFalse(h.start(emptyList()))
        assertFalse(h.start(List(10) { one }))
        assertFalse(h.start(listOf(submit), submitLast = true))
        assertFalse(h.start(listOf(PxRect(-1, 0, 10, 20))))
        assertFalse(h.start(listOf(PxRect(10, 0, 12, 20))))
        assertFalse(h.start(listOf(PxRect(Int.MAX_VALUE - 20, 0, Int.MAX_VALUE, 20))))
        assertTrue(h.start(listOf(one)))
        assertFalse(h.start(listOf(zero)))
        assertEquals(listOf(one), h.presses.map { it.box })
        assertTrue(h.results.isEmpty())
    }

    @Test fun inputListIsSnapshottedSoCallerCannotRedirectAPendingTap() {
        val h = Harness()
        val boxes = mutableListOf(one, zero)
        assertTrue(h.start(boxes))
        boxes[1] = submit
        h.clock.runUntil()
        assertEquals(listOf(one, zero), h.presses.map { it.box })
        assertEquals(listOf(true), h.results)
    }

    @Test fun guardAndSchedulerFailuresStopWithoutEscapingToTheService() {
        val h = Harness()
        assertTrue(h.start(listOf(one)) { error("capture lost") })
        assertTrue(h.presses.isEmpty())
        assertEquals(listOf(false), h.results)

        val result = mutableListOf<Boolean>()
        val sequence = TapSequence(
            dispatch = { _, _, _ -> true },
            schedule = { _, _ -> error("main looper stopped") },
        )
        assertTrue(sequence.start(listOf(one)) { result += it })
        assertEquals(listOf(false), result)
    }
}
