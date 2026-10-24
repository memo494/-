package com.clu.quickanswer.interaction

import com.clu.quickanswer.AnswerParser
import com.clu.quickanswer.TouchFrameGuard
import kotlin.math.abs

/** A completed Android gesture is insufficient: the visible field must contain every expected digit. */
object NumericEntryCheck {
    /** Flat empty fields are valid baselines too; identical low-contrast placeholders are not changes. */
    fun fieldChanged(reference: IntArray, current: IntArray): Boolean {
        if (reference.size != TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT || current.size != reference.size ||
            reference.any { it !in -1..255 } || current.any { it !in -1..255 } ||
            !TouchFrameGuard.hasSharedCoverage(reference, current)) return false
        var count = 0
        var sum = 0L
        var changed = 0
        for (i in reference.indices) {
            if (reference[i] < 0 || current[i] < 0) continue
            val diff = abs(reference[i] - current[i])
            sum += diff
            count++
            if (diff > 24) changed++
        }
        // One narrow digit (especially "1") occupies very little of a wide field. Require actual
        // high-contrast changed strokes without a whole-field mean that would reject that digit.
        return changed >= 8 && sum > count / 4L
    }

    fun matches(expected: String, observed: String, confidence: Float, changed: Boolean): Boolean {
        if (!changed || expected.length !in 1..4 || expected.any { it !in '0'..'9' } ||
            !confidence.isFinite() || confidence !in 0.75f..1f) return false
        return AnswerParser.numberOnly(observed) == expected
    }
}
