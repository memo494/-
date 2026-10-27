package com.clu.quickanswer

/**
 * How often the service asks the client to warm its connection ([Answerer.warmUp]). Warming is
 * asked for wherever a question may come next (bubble touch-down, the volume key, auto mode seeing
 * the screen change), so it can be asked for twice a second; the client has its own, longer gates.
 * This only keeps the asks themselves cheap. Main thread only. Pure Kotlin.
 */
class WarmUpThrottle(private val minGapMs: Long) {

    private var last = NEVER

    /** True, and remembers [now], when the last ask was at least [minGapMs] before [now]. */
    fun tryAcquire(now: Long): Boolean {
        if (last != NEVER && now - last < minGapMs) return false
        last = now
        return true
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
