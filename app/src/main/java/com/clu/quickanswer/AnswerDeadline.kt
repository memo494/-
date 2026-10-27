package com.clu.quickanswer

/**
 * The time one answer has, from the moment the question is sent. Every wait on the way to the answer
 * (the race between models, each HTTP call, the pause before a retry) is cut to what is left of it, so
 * a slow or silent server cannot keep the bubble busy: when it is spent the answer is dropped and the
 * bubble is free again.
 *
 * [clock] is a monotonic clock in milliseconds (SystemClock.elapsedRealtime on the device, a fake one
 * in tests) and [endsAt] is a reading of it. Pure Kotlin, no android.*.
 */
class AnswerDeadline(private val clock: () -> Long, val endsAt: Long) {

    /** Milliseconds left; 0 once the deadline has passed, never negative. */
    fun remainingMs(): Long = (endsAt - clock()).coerceAtLeast(0L)

    /** True once the whole budget is spent. */
    val expired: Boolean
        get() = remainingMs() <= 0L

    /**
     * [timeoutMs] cut to what is left, for a socket's connect or read timeout: the smaller of the two,
     * and at least 1 because 0 means "wait for ever" to HttpURLConnection.
     */
    fun capTimeoutMs(timeoutMs: Int): Int =
        minOf(timeoutMs.toLong(), remainingMs()).coerceAtLeast(1L).toInt()

    companion object {
        /**
         * The owner's choice (26 Oct 2026): an answer gets 10 seconds in all; after that the bubble is
         * free again. Before it, a slow model kept the bubble busy (it ignores presses) for as long as
         * the HTTP timeouts allowed, 40 s for one call and more with the retry, and the app looked frozen.
         */
        const val BUDGET_MS = 10_000L

        /** What the panel says when the budget ran out. Derived from [BUDGET_MS] so the two agree. */
        const val TIMEOUT_TEXT = "انتهى الوقت (${BUDGET_MS / 1000} ث)، جرّب مرة ثانية"

        /** A deadline [budgetMs] after now on [clock]. */
        fun start(clock: () -> Long, budgetMs: Long = BUDGET_MS): AnswerDeadline =
            AnswerDeadline(clock, clock() + budgetMs)
    }
}
