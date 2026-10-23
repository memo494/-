package com.clu.quickanswer

/** A failed reading is not a completed answer. Retry briefly, without polling the model forever. */
internal class AutoAnswerRetry {
    private var failedFrame: IntArray? = null
    private var failures = 0
    private var retryAt: Long? = null

    fun reset() {
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
