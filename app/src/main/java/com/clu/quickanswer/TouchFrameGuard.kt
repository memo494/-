package com.clu.quickanswer

import kotlin.math.abs

/** Compare the question itself, so changing timers/input digits cannot authorize an old answer. */
internal object TouchFrameGuard {
    const val WIDTH = 128
    const val HEIGHT = 48
    private const val MIN_SAMPLES = (WIDTH * HEIGHT * 7 + 9) / 10

    /** -1 is a captured/current overlay mask; missing evidence must never count as a black pixel. */
    fun usable(stamp: IntArray): Boolean {
        if (stamp.size != WIDTH * HEIGHT) return false
        var count = 0
        var darkest = 255
        var brightest = 0
        for (value in stamp) {
            if (value !in -1..255) return false
            if (value == -1) continue
            count++
            if (value < darkest) darkest = value
            if (value > brightest) brightest = value
        }
        return count >= MIN_SAMPLES && brightest - darkest >= 24
    }

    /** An overlay move cannot turn insufficient shared evidence into permission for another tap. */
    fun hasSharedCoverage(reference: IntArray, current: IntArray): Boolean =
        reference.size == WIDTH * HEIGHT && current.size == reference.size &&
            reference.indices.count { reference[it] in 0..255 && current[it] in 0..255 } >= MIN_SAMPLES

    /**
     * Why [matches] said no, for the diagnostic log: the mean difference, how many 16x8 tiles changed
     * and where the first one is (x,y as a share of the region, 0..100), and how many samples were
     * compared. A dark block in one place is our own window; a change everywhere is a new question.
     */
    fun explain(reference: IntArray, current: IntArray): String {
        if (!usable(reference)) return "مرجع غير صالح"
        if (!usable(current)) return "لقطة غير صالحة"
        var total = 0L
        var compared = 0
        var tiles = 0
        var first = ""
        var darker = 0
        for (top in 0 until HEIGHT step 8) for (left in 0 until WIDTH step 16) {
            var changed = 0
            for (y in top until top + 8) for (x in left until left + 16) {
                val index = y * WIDTH + x
                if (reference[index] == -1 || current[index] == -1) continue
                val difference = abs(reference[index] - current[index])
                total += difference
                compared++
                if (difference > 24) changed++
                if (current[index] < reference[index] - 24) darker++
            }
            if (changed >= 3) {
                if (tiles == 0) first = "${left * 100 / WIDTH},${top * 100 / HEIGHT}"
                tiles++
            }
        }
        val mean = if (compared == 0) 0 else (total / compared).toInt()
        return "فرق=$mean مربعات=$tiles/48 أولها=$first أغمق=${darker * 100 / maxOf(1, compared)}% عيّنات=$compared"
    }

    fun matches(reference: IntArray, current: IntArray): Boolean {
        if (!usable(reference) || !usable(current)) return false
        var total = 0L
        var compared = 0
        // A changed word can disappear in a whole-banner average. Check local tiles as well.
        for (top in 0 until HEIGHT step 8) for (left in 0 until WIDTH step 16) {
            var changed = 0
            for (y in top until top + 8) for (x in left until left + 16) {
                val index = y * WIDTH + x
                if (reference[index] == -1 || current[index] == -1) continue
                val difference = abs(reference[index] - current[index])
                total += difference
                compared++
                if (difference > 24) changed++
            }
            if (changed >= 3) return false
        }
        return compared >= MIN_SAMPLES && total <= compared * 2L
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
        if (previous != null && width == screenWidth && height == screenHeight) {
            if (!TouchFrameGuard.hasSharedCoverage(previous, stamp) ||
                TouchFrameGuard.matches(previous, stamp)
            ) return false
        }
        attempted = stamp.copyOf()
        width = screenWidth
        height = screenHeight
        return true
    }

    fun reset() { attempted = null }
}
