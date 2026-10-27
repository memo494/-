package com.clu.quickanswer

import com.clu.quickanswer.interaction.NumericEntryCheck
import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class GameTapTimingRegressionTest {
    private data class Task(val at: Long, val serial: Int, val action: () -> Unit)
    private data class Press(val rect: PxRect, val at: Long, val duration: Long)
    private val digit = PxRect(10, 20, 40, 50)
    private val submit = PxRect(70, 20, 130, 80)

    private class Driver {
        private val tasks = PriorityQueue(compareBy<Task> { it.at }.thenBy { it.serial })
        private var serial = 0
        var now = 0L
        val presses = mutableListOf<Press>()
        val results = mutableListOf<Boolean>()

        private fun schedule(delay: Long, action: () -> Unit) {
            tasks += Task(now + delay, serial++, action)
        }

        val sequence = TapSequence(
            dispatch = { rect, duration, complete ->
                presses += Press(rect, now, duration)
                schedule(duration) { complete(true) }
                true
            },
            schedule = ::schedule,
            pressDurationMs = TapSequence.GAME_PRESS_MS,
        )

        fun start(boxes: List<PxRect>, submitLast: Boolean = false): Boolean =
            sequence.start(boxes, submitLast) { results += it }

        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) {
                check(count++ < 100)
                val task = tasks.remove()
                now = task.at
                task.action()
            }
        }
    }

    @Test fun gamePressLastsLongerThanOneFifteenFpsFrameAndRepeatedDigitsHaveAnUpGap() {
        val driver = Driver()
        val boxes = List(4) { digit } + submit
        assertTrue(driver.start(boxes, submitLast = true))
        driver.drain()
        assertEquals(boxes, driver.presses.map { it.rect })
        assertTrue(driver.presses.all { it.duration == 90L && it.duration > 1_000L / 15 })
        // The confirmation follows the last digit's UP by SUBMIT_GAP_MS (40 ms): the read-back that
        // gates it (NumericEntryCheck.READBACK_*) waits for the game instead of a fixed 120 ms.
        assertEquals(listOf(0L, 130L, 260L, 390L, 520L), driver.presses.map { it.at })
        assertEquals(610L, driver.presses.last().at + driver.presses.last().duration)
        assertEquals(listOf(true), driver.results)
    }

    @Test fun resetFourDigitEntryAndConfirmationRemainOrderedWithinNineTaps() {
        val driver = Driver()
        val boxes = List(8) { digit } + submit
        assertTrue(driver.start(boxes, submitLast = true))
        driver.drain()
        assertEquals(boxes, driver.presses.map { it.rect })
        assertTrue(driver.presses.zipWithNext().all { (a, b) -> b.at >= a.at + a.duration + 40L })
        assertEquals(1_130L, driver.presses.last().at + driver.presses.last().duration) // confirm 40 ms after the last UP
        assertEquals(listOf(true), driver.results)
    }

    @Test fun configurableDurationCannotBecomeATooShortOrLongPress() {
        for (invalid in listOf(0L, 44L, 201L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                TapSequence(dispatch = { _, _, _ -> true }, schedule = { _, _ -> }, pressDurationMs = invalid)
            }
        }
    }

    /** The read-back before a confirmation still covers about a third of a second after the last UP. */
    @Test fun theReadBackWindowCoversFiveFramesAtFifteenFps() {
        val window = TapSequence.SUBMIT_GAP_MS +
            (NumericEntryCheck.READBACK_ATTEMPTS - 1) * NumericEntryCheck.READBACK_INTERVAL_MS
        assertTrue("window $window ms", window >= 5 * 1_000L / 15)
        assertTrue("first read comes sooner than before", TapSequence.SUBMIT_GAP_MS < 120L)
    }
}
