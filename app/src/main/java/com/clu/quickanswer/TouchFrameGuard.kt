package com.clu.quickanswer

import kotlin.math.abs

/** Compare the question itself, so changing timers/input digits cannot authorize an old answer. */
internal object TouchFrameGuard {
    const val WIDTH = 128
    const val HEIGHT = 48

    fun usable(stamp: IntArray): Boolean = stamp.size == WIDTH * HEIGHT &&
        stamp.all { it in 0..255 } && stamp.max() - stamp.min() >= 24

    fun matches(reference: IntArray, current: IntArray): Boolean {
        if (!usable(reference) || !usable(current)) return false
        var total = 0L
        // A changed word can disappear in a whole-banner average. Check local tiles as well.
        for (top in 0 until HEIGHT step 8) for (left in 0 until WIDTH step 16) {
            var changed = 0
            for (y in top until top + 8) for (x in left until left + 16) {
                val index = y * WIDTH + x
                val difference = abs(reference[index] - current[index])
                total += difference
                if (difference > 24) changed++
            }
            if (changed >= 3) return false
        }
        return total <= reference.size * 2L
    }
}

/** A partial gesture is consumed too: never replay digits/submit automatically on the same question. */
internal class TouchAttemptGuard {
    private var attempted: IntArray? = null
    private var width = 0
    private var height = 0

    fun tryBegin(stamp: IntArray, screenWidth: Int, screenHeight: Int): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0 || !TouchFrameGuard.usable(stamp)) return false
        val previous = attempted
        if (previous != null && width == screenWidth && height == screenHeight &&
            TouchFrameGuard.matches(previous, stamp)
        ) return false
        attempted = stamp.copyOf()
        width = screenWidth
        height = screenHeight
        return true
    }

    fun reset() { attempted = null }
}
