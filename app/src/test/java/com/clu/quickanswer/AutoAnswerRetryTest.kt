package com.clu.quickanswer

import org.junit.Assert.*
import org.junit.Test

class AutoAnswerRetryTest {
    private val question = IntArray(32 * 32) { 80 }
    private val nextQuestion = IntArray(32 * 32) { 130 }

    @Test fun noAnswerAndMissingRegionRetryTwiceThenWaitForANewQuestion() {
        val retry = AutoAnswerRetry()
        retry.record(question, retryable = true, now = 0)
        assertFalse(retry.due(question, 1_499))
        assertTrue(retry.due(question, 1_500))
        retry.record(question, retryable = true, now = 2_000)
        assertFalse(retry.due(question, 5_999))
        assertTrue(retry.due(question, 6_000))
        retry.record(question, retryable = true, now = 6_000)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        assertEquals(FrameSignature.Verdict.SEND,
            FrameSignature.judge(nextQuestion, nextQuestion, question, false, 6.0, 2.0))
        retry.record(nextQuestion, retryable = true, now = 7_000)
        assertTrue(retry.due(nextQuestion, 8_500))
    }

    @Test fun retryStillHidesPanelAndWaitsForAnUnmaskedStableFrame() {
        val retry = AutoAnswerRetry()
        retry.record(question, true, 0)
        val masked = question.copyOf().apply { for (i in 0..100) this[i] = FrameSignature.IGNORED }
        fun judge(frame: IntArray, previous: IntArray, panel: Boolean) = FrameSignature.judge(
            frame, previous, if (retry.due(frame, 2_000)) null else question, panel, 6.0, 2.0)
        assertEquals(FrameSignature.Verdict.HIDE_PANEL, judge(masked, masked, true))
        assertEquals(FrameSignature.Verdict.WAIT, judge(question, masked, false))
        assertEquals(FrameSignature.Verdict.SEND, judge(question, question, false))
    }

    @Test fun successOrGestureDispatchDisarmsRetryEvenOnAnUnchangedFrame() {
        val retry = AutoAnswerRetry()
        retry.record(question, true, 0)
        retry.record(question, retryable = false, now = 1_600)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        assertEquals(FrameSignature.Verdict.IDLE,
            FrameSignature.judge(question, question, question, false, 6.0, 2.0))
    }

    @Test fun smallFrameNoiseDoesNotRenewTheRetryBudget() {
        val retry = AutoAnswerRetry()
        retry.record(question, true, 0)
        retry.record(IntArray(1024) { 81 }, true, 1_500)
        retry.record(IntArray(1024) { 82 }, true, 5_500)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        assertFalse(retry.due(IntArray(1024) { 83 }, Long.MAX_VALUE))
    }

    @Test fun manualRequestCanStartFreshWithoutWaitingForTheOldBudget() {
        val retry = AutoAnswerRetry()
        repeat(3) { retry.record(question, true, 0) }
        retry.reset()
        assertFalse(retry.due(question, Long.MAX_VALUE))
        retry.record(question, true, 10_000)
        assertTrue(retry.due(question, 11_500))
        assertFalse(retry.due(nextQuestion, 11_500))
    }
}
