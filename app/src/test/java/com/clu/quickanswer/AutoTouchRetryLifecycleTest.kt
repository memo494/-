package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class AutoTouchRetryLifecycleTest {
    private val question = IntArray(32 * 32) { 80 }
    private val nextQuestion = IntArray(32 * 32) { 130 }
    private val key = PxRect(10, 10, 40, 40)

    @Test fun preparedPlansThatFailPreflightKeepTheBoundedRetryBudget() {
        val retry = AutoAnswerRetry()
        var checks = 0
        fun failBeforeTouch(now: Long) {
            val attempt = retry.begin(question)
            attempt.resolved(isAnswer = true, touchRequested = true, hasTouchPlan = true, now)
            // No old deadline may launch another solve while this attempt is awaiting preflight.
            assertFalse(retry.due(question, Long.MAX_VALUE))
            TouchPreflight { _, action -> action() }.awaitReady(
                isCurrent = { true }, ready = { checks++; false },
            ) { ready -> attempt.finish(retryable = !ready, now) }
        }
        failBeforeTouch(0)
        assertFalse(retry.due(question, 1_499))
        assertTrue(retry.due(question, 1_500))
        failBeforeTouch(1_500)
        assertFalse(retry.due(question, 5_499))
        assertTrue(retry.due(question, 5_500))
        failBeforeTouch(5_500)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        assertEquals(9, checks)
        // Exhaustion leaves the next question eligible for normal automatic detection.
        assertEquals(FrameSignature.Verdict.SEND,
            FrameSignature.judge(nextQuestion, nextQuestion, question, false, 6.0, 2.0))
    }

    @Test fun refusedSequenceRetriesBecauseItAcceptedNoNewStroke() {
        var presses = 0
        val sequence = TapSequence({ _, _, _ -> presses++; true }, { _, _ -> })
        assertTrue(sequence.start(listOf(key), result = {}))
        val retry = AutoAnswerRetry()
        fun refuse(now: Long) {
            val attempt = retry.begin(question)
            attempt.resolved(true, true, true, now)
            val accepted = sequence.start(listOf(key), result = { fail("A refused run has no callback") })
            assertFalse(accepted)
            attempt.finish(retryable = !accepted, now)
        }
        refuse(0)
        assertTrue(retry.due(question, 1_500))
        refuse(1_500)
        assertFalse(retry.due(question, 5_499))
        assertTrue(retry.due(question, 5_500))
        refuse(5_500)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        assertEquals(1, presses)
    }

    @Test fun acceptedPartialSequenceNeverRetriesEvenWhenItsCallbackTimesOut() {
        val work = mutableListOf<() -> Unit>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val outcomes = mutableListOf<Boolean>()
        val sequence = TapSequence({ _, _, complete -> callbacks += complete; true },
            { _, action -> work += action })
        val retry = AutoAnswerRetry()
        val attempt = retry.begin(question)
        attempt.resolved(true, true, true, 0)
        val accepted = sequence.start(listOf(key, key), result = outcomes::add)
        assertTrue(accepted)
        work.removeAt(0)() // The platform accepted the first digit but never reported its outcome.
        assertEquals(listOf(false), outcomes)
        attempt.finish(retryable = !accepted, now = 750)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        callbacks.single()(true)
        assertEquals(1, callbacks.size)
        assertEquals(listOf(false), outcomes)
    }

    @Test fun supersededAndCancelledAttemptsCannotAlterANewerRetry() {
        val retry = AutoAnswerRetry()
        val old = retry.begin(question)
        old.resolved(true, true, true, 0)
        val current = retry.begin(nextQuestion)
        current.resolved(true, true, true, 100)
        old.finish(retryable = true, now = 200)
        assertFalse(retry.due(question, Long.MAX_VALUE))
        current.finish(retryable = true, now = 300)
        old.finish(retryable = false, now = 400)
        old.cancel()
        assertFalse(retry.due(nextQuestion, 1_799))
        assertTrue(retry.due(nextQuestion, 1_800))

        val cancelled = retry.begin(nextQuestion)
        cancelled.cancel()
        cancelled.finish(retryable = true, now = 2_000)
        assertFalse(retry.due(nextQuestion, Long.MAX_VALUE))
    }

    @Test fun aLateFailureCannotUndoAcceptedInputOrAManualReset() {
        val retry = AutoAnswerRetry()
        val accepted = retry.begin(question)
        accepted.resolved(true, true, true, 0)
        accepted.finish(retryable = false, now = 100)
        accepted.finish(retryable = true, now = 200)
        assertFalse(retry.due(question, Long.MAX_VALUE))

        val old = retry.begin(question)
        old.resolved(true, true, true, 300)
        retry.reset()
        old.finish(retryable = true, now = 400)
        assertFalse(retry.due(question, Long.MAX_VALUE))
    }

    @Test fun missingPlansRetryButDisplayOnlyAnswersCompleteImmediately() {
        val retry = AutoAnswerRetry()
        retry.begin(question).resolved(true, true, false, 0)
        assertTrue(retry.due(question, 1_500))
        retry.begin(question).resolved(true, false, false, 1_500)
        assertFalse(retry.due(question, Long.MAX_VALUE))
    }
}
