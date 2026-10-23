package com.clu.quickanswer.ocr

/**
 * Decides when a layout request ([com.clu.quickanswer.Answerer.layout]) may go out. Learning runs in
 * the background on the user's own quota (decision 10), so it must stay rare: one request at a
 * time, [minIntervalMs] between starts, at most [maxPerDay] starts in any 24 hours, and a game whose
 * last request taught nothing (the model's boxes did not read back) is left alone for
 * [retryAfterMs]: asking again at once would most likely fail the same way.
 *
 * "The same game" is [ScreenFingerprint.SAME_GAME_MAX], so one failure pauses every screen type of
 * that game, which is what keeps a game the reader cannot handle from eating the quota.
 * Times are from a monotonic clock (SystemClock.elapsedRealtime in the service). Thread-safe.
 */
class LearningGate(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxPerDay: Int = MAX_PER_DAY,
    private val retryAfterMs: Long = RETRY_AFTER_MS,
) {
    private var inFlight = false
    private val starts = ArrayDeque<Long>()
    private val failures = ArrayList<Pair<String, Long>>()

    /**
     * True when a request for the screen with [fingerprint] may start at [now]; it then counts as
     * started and running until [finish].
     */
    @Synchronized
    fun tryStart(fingerprint: String, now: Long): Boolean {
        if (inFlight) return false
        while (starts.isNotEmpty() && now - starts.first() >= DAY_MS) starts.removeFirst()
        if (starts.isNotEmpty() && now - starts.last() < minIntervalMs) return false
        if (starts.size >= maxPerDay) return false
        failures.removeAll { now - it.second >= retryAfterMs }
        if (failures.any { ScreenFingerprint.distance(it.first, fingerprint) <= ScreenFingerprint.SAME_GAME_MAX }) return false
        inFlight = true
        starts.addLast(now)
        return true
    }

    /** The request started for [fingerprint] is over; [learned] tells whether a template was kept. */
    @Synchronized
    fun finish(fingerprint: String, learned: Boolean, now: Long) {
        if (!inFlight) return
        inFlight = false
        if (!learned) {
            failures.add(fingerprint to now)
            while (failures.size > MAX_FAILURES_KEPT) failures.removeAt(0)
        }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        // A request is a full screenshot at the user's model: about as costly as one image answer.
        // 20 a day is a few new games' screens plus retries, well inside the free tier's daily limit.
        const val MIN_INTERVAL_MS = 60_000L
        const val MAX_PER_DAY = 20
        const val RETRY_AFTER_MS = 10 * 60_000L

        /** Failures remembered at once; more than this many games in 10 minutes does not happen. */
        private const val MAX_FAILURES_KEPT = 20
    }
}
