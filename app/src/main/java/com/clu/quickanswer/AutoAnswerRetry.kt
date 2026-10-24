package com.clu.quickanswer

/** A failed reading is not a completed answer. Retry briefly, without polling the model forever. */
internal class AutoAnswerRetry {
    private var failedFrame: IntArray? = null
    private var failures = 0
    private var retryAt: Long? = null
    private var currentAttempt: Attempt? = null

    /** A plan alone is not success: keep its budget until preflight/dispatch has an outcome. */
    fun begin(frame: IntArray): Attempt {
        retryAt = null
        return Attempt(frame.copyOf()).also { currentAttempt = it }
    }

    /** Main-thread ownership keeps late results from changing a newer request's retry state. */
    inner class Attempt internal constructor(private val frame: IntArray) {
        fun resolved(isAnswer: Boolean, touchRequested: Boolean, hasTouchPlan: Boolean, now: Long) {
            when {
                !isAnswer || touchRequested && !hasTouchPlan -> finish(retryable = true, now)
                !touchRequested -> finish(retryable = false, now)
                // Touch preparation succeeded; execution, not preparation, decides the outcome.
            }
        }

        fun finish(retryable: Boolean, now: Long) {
            if (currentAttempt !== this) return
            currentAttempt = null
            record(frame, retryable, now)
        }

        fun cancel() {
            if (currentAttempt === this) currentAttempt = null
        }
    }

    fun reset() {
        currentAttempt = null
        failedFrame = null
        failures = 0
        retryAt = null
    }

    /** Only failures before gesture dispatch qualify; partial input must never be replayed. */
    fun record(frame: IntArray, retryable: Boolean, now: Long) {
        if (!retryable) { reset(); return }
        val previous = failedFrame
        if (previous == null || FrameSignature.diff(previous, frame) > 6.0) {
            failedFrame = frame.copyOf()
            failures = 0
        }
        failures++
        retryAt = when (failures) {
            1 -> now + 1_500L
            2 -> now + 4_000L
            else -> null
        }
    }

    fun due(frame: IntArray, now: Long): Boolean {
        val deadline = retryAt ?: return false
        val previous = failedFrame ?: return false
        return now >= deadline && FrameSignature.diff(previous, frame) <= 6.0
    }
}
