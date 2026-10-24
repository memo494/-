package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class ResetFieldBaselineTest {
    private val key = PxRect(10, 10, 30, 30)
    private data class Task(val time: Long, val order: Int, val action: () -> Unit)

    @Test fun theFirstDigitWaitsForResetRenderingBeforeItsGuardCapturesTheField() {
        for (resetCount in listOf(1, 4)) {
            var now = 0L
            var order = 0
            val tasks = PriorityQueue(compareBy<Task> { it.time }.thenBy { it.order })
            val presses = mutableListOf<Long>()
            val guarded = mutableListOf<Long>()
            val result = mutableListOf<Boolean>()
            fun schedule(delay: Long, action: () -> Unit) { tasks += Task(now + delay, order++, action) }
            val sequence = TapSequence(dispatch = { _, duration, complete ->
                presses += now
                schedule(duration) { complete(true) }
                true
            }, schedule = ::schedule, pressDurationMs = TapSequence.GAME_PRESS_MS)
            assertTrue(sequence.start(List(resetCount + 2) { key }, submitLast = true,
                canContinue = { guarded += now; true }, resetCount = resetCount, result = { result += it }))
            while (tasks.isNotEmpty()) {
                val task = tasks.remove(); now = task.time; task.action()
            }
            assertEquals(presses, guarded)
            assertEquals(TapSequence.GAME_PRESS_MS + TapSequence.RESET_GAP_MS,
                presses[resetCount] - presses[resetCount - 1])
            assertEquals(listOf(true), result)
        }
    }

    @Test fun aResetCannotConsumeAllDigitsOrTheSubmitSlot() {
        val sequence = TapSequence(dispatch = { _, _, _ -> error("must not tap") }, schedule = { _, _ -> })
        assertFalse(sequence.start(listOf(key, key), submitLast = true, resetCount = 1) {})
        assertFalse(sequence.start(listOf(key, key), resetCount = 2) {})
        assertFalse(sequence.start(List(9) { key }, submitLast = true, resetCount = 5) {})
    }
}
