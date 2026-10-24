package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class NumericTouchRegressionTest {
    private val keys = ('0'..'9').associateWith { digit ->
        val i = digit - '0'
        PxRect(20 + i * 30, 200, 44 + i * 30, 230)
    }
    private val clear = PxRect(20, 300, 44, 330)
    private val submit = PxRect(360, 300, 440, 395)

    @Test fun resetsThenTypesAllLengthsAndLeadingRepeatedZeroesBeforeConfirming() {
        for (number in listOf("0", "11", "007", "0000", "1989", "9999")) {
            assertEquals(listOf(clear) + number.map { keys.getValue(it) } + submit,
                TouchPlanner.plan(number, null, keys, submit, 500, 800, clear = clear))
            assertEquals(List(4) { clear } + number.map { keys.getValue(it) } + submit,
                TouchPlanner.plan(number, null, keys, submit, 500, 800, backspace = clear))
        }
    }

    @Test fun ambiguousOrOverlappingControlsNeverAuthorizeTyping() {
        assertNull(TouchPlanner.plan("12", null, keys + ('2' to keys.getValue('1')), submit, 500, 800))
        assertNull(TouchPlanner.plan("12", null, keys, keys.getValue('4'), 500, 800))
        assertNull(TouchPlanner.plan("12", null, keys, submit, 500, 800, clear = keys.getValue('0')))
        assertNull(TouchPlanner.plan("12", null, keys, submit, 500, 800, clear, clear))
        assertNull(TouchPlanner.plan("12", null, keys - '9', submit, 500, 800))
        assertNull(TouchPlanner.plan("12345", null, keys, submit, 500, 800))
    }

    @Test fun automaticEntryRequiresAVisibleResetControlToAvoidAppending() {
        assertNull(TouchPlanner.plan("12", null, keys, submit, 500, 800, requireReset = true))
        assertEquals(listOf(clear, keys.getValue('1'), keys.getValue('2'), submit),
            TouchPlanner.plan("12", null, keys, submit, 500, 800, clear = clear, requireReset = true))
    }

    private fun stamp(): IntArray = IntArray(TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT) { 220 }
        .also { pixels -> for (i in 1400 until 1450) pixels[i] = 40 }

    @Test fun detectsSmallChangedWordAndTheBottomOfTheQuestion() {
        val before = stamp()
        assertTrue(TouchFrameGuard.matches(before, before.copyOf()))
        val changedWord = before.copyOf().also { for (i in 1400 until 1404) it[i] = 220 }
        assertFalse(TouchFrameGuard.matches(before, changedWord))
        val lastLine = before.copyOf().also { for (i in it.size - 4 until it.size) it[i] = 40 }
        assertFalse(TouchFrameGuard.matches(before, lastLine))
        assertTrue(TouchFrameGuard.matches(before, before.map { it + 1 }.toIntArray()))
        assertFalse(TouchFrameGuard.matches(before, IntArray(before.size)))
        assertFalse(TouchFrameGuard.matches(before, intArrayOf(1, 2, 3)))
    }

    @Test fun partialAttemptCannotReplayButNewQuestionOrManualRestartCanProceed() {
        val guard = TouchAttemptGuard()
        val first = stamp()
        assertTrue(guard.tryBegin(first, 1280, 576))
        assertFalse(guard.tryBegin(first.copyOf(), 1280, 576))
        val next = first.copyOf().also { for (i in 1400 until 1404) it[i] = 220 }
        assertTrue(guard.tryBegin(next, 1280, 576))
        assertFalse(guard.tryBegin(next, 1280, 576))
        guard.reset()
        assertTrue(guard.tryBegin(next, 1280, 576))
    }
}
