package com.clu.quickanswer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When ClaudeClient may pause and send a second request: only if the answer budget can hold it. */
class ClaudeRetryBudgetTest {

    private var now = 0L
    private fun deadlineWith(remainingMs: Long) = AnswerDeadline.start({ now }, remainingMs)

    private val needed = ClaudeClient.RETRY_DELAY_MS + ClaudeClient.MIN_RETRY_CALL_MS

    @Test
    fun theFirstAttemptMayBeRetriedWhenThereIsRoom() {
        assertTrue(ClaudeClient.mayRetry(0, deadlineWith(AnswerDeadline.BUDGET_MS)))
        assertTrue(ClaudeClient.mayRetry(0, deadlineWith(needed)))
    }

    @Test
    fun noRetryWhenTheBudgetCannotHoldThePauseAndACall() {
        assertFalse(ClaudeClient.mayRetry(0, deadlineWith(needed - 1)))
        assertFalse(ClaudeClient.mayRetry(0, deadlineWith(ClaudeClient.RETRY_DELAY_MS)))
        assertFalse(ClaudeClient.mayRetry(0, deadlineWith(0)))
    }

    @Test
    fun theSecondAttemptIsNeverRetried() {
        assertFalse(ClaudeClient.mayRetry(1, deadlineWith(AnswerDeadline.BUDGET_MS)))
    }

    @Test
    fun theBudgetShrinksAsTimePasses() {
        val d = deadlineWith(AnswerDeadline.BUDGET_MS)
        assertTrue(ClaudeClient.mayRetry(0, d))
        now += AnswerDeadline.BUDGET_MS - needed + 1
        assertFalse(ClaudeClient.mayRetry(0, d))
    }
}
