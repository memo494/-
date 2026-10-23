package com.clu.quickanswer

import org.junit.Assert.*
import org.junit.Test

class TouchFrameMaskRegressionTest {
    private val size = TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT

    private fun question(): IntArray = IntArray(size) { 220 }.also { pixels ->
        // Several dark strokes ensure the retained question has real content contrast.
        for (y in 12 until 20) for (x in 44 until 84) if (x % 6 < 2) {
            pixels[y * TouchFrameGuard.WIDTH + x] = 40
        }
    }

    private fun mask(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int) {
        for (y in top until bottom) for (x in left until right) {
            pixels[y * TouchFrameGuard.WIDTH + x] = -1
        }
    }

    @Test fun maskedBubbleDoesNotCompareItsBlackBaselineWithOrangeLivePixels() {
        val baseline = question().also { mask(it, 4, 8, 17, 22) }
        val live = question().also { pixels ->
            // Live bubble colour and capture variation are irrelevant under either frame's mask.
            for (y in 8 until 22) for (x in 4 until 17) pixels[y * TouchFrameGuard.WIDTH + x] = 165
        }
        assertTrue(TouchFrameGuard.usable(baseline))
        assertTrue(TouchFrameGuard.matches(baseline, live))
        assertTrue(TouchFrameGuard.matches(live, baseline))
    }

    @Test fun sameQuestionWithMovedBubbleAndSmallRenderingNoiseStillMatches() {
        val baseline = question().also { mask(it, 3, 8, 16, 22) }
        val live = question().map { it + 1 }.toIntArray().also { mask(it, 95, 25, 108, 39) }
        assertTrue(TouchFrameGuard.matches(baseline, live))
        assertTrue(TouchFrameGuard.hasSharedCoverage(baseline, live))
    }

    @Test fun aChangedWordOutsideTheMaskStillCancels() {
        val baseline = question().also { mask(it, 3, 8, 16, 22) }
        val live = baseline.copyOf().also { pixels ->
            for (x in 44 until 48) pixels[14 * TouchFrameGuard.WIDTH + x] = 120
        }
        assertFalse(TouchFrameGuard.matches(baseline, live))
    }

    @Test fun twoDifferentMasksNeedAtLeastSeventyPercentJointCoverage() {
        val baseline = question().also { mask(it, 0, 0, 32, TouchFrameGuard.HEIGHT) }
        val live = question().also { mask(it, 96, 0, 128, TouchFrameGuard.HEIGHT) }
        assertTrue(TouchFrameGuard.usable(baseline))
        assertTrue(TouchFrameGuard.usable(live))
        assertFalse(TouchFrameGuard.hasSharedCoverage(baseline, live))
        assertFalse(TouchFrameGuard.matches(baseline, live))
    }

    @Test fun insufficientVisibleContentAndInvalidSampleValuesAreRejected() {
        val tooMuchMask = question().also { mask(it, 0, 0, 40, TouchFrameGuard.HEIGHT) }
        assertFalse(TouchFrameGuard.usable(tooMuchMask))
        assertFalse(TouchFrameGuard.matches(question(), tooMuchMask))
        assertFalse(TouchFrameGuard.usable(IntArray(size) { -1 }))
        assertFalse(TouchFrameGuard.usable(IntArray(size) { 220 }.also { mask(it, 0, 0, 15, 10) }))
        for (bad in listOf(-2, 256, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val invalid = question().also { it[0] = bad }
            assertFalse("sample=$bad", TouchFrameGuard.usable(invalid))
            assertFalse("sample=$bad", TouchFrameGuard.matches(question(), invalid))
            assertFalse("sample=$bad", TouchFrameGuard.matches(invalid, question()))
        }
    }

    @Test fun meanDifferenceBudgetUsesComparedSamplesOnly() {
        val baseline = question().also { mask(it, 0, 0, 32, TouchFrameGuard.HEIGHT) }
        val live = baseline.map { if (it == -1) -1 else it + 3 }.toIntArray()
        assertFalse(TouchFrameGuard.matches(baseline, live))
    }

    @Test fun duplicateAttemptWithADifferentBubbleMaskCannotReplay() {
        val attempts = TouchAttemptGuard()
        val first = question().also { mask(it, 3, 8, 16, 22) }
        val moved = question().also { mask(it, 95, 25, 108, 39) }
        assertTrue(attempts.tryBegin(first, 1280, 576))
        assertFalse(attempts.tryBegin(moved, 1280, 576))
        val changed = moved.copyOf().also { pixels ->
            for (x in 44 until 48) pixels[14 * TouchFrameGuard.WIDTH + x] = 120
        }
        assertTrue(attempts.tryBegin(changed, 1280, 576))
        assertFalse(attempts.tryBegin(changed.copyOf(), 1280, 576))
    }

    @Test fun uncomparableOverlayMovementDoesNotPermitAPotentialDuplicate() {
        val attempts = TouchAttemptGuard()
        val first = question().also { mask(it, 0, 0, 32, TouchFrameGuard.HEIGHT) }
        val moved = question().also { mask(it, 96, 0, 128, TouchFrameGuard.HEIGHT) }
        assertTrue(attempts.tryBegin(first, 1280, 576))
        assertFalse(attempts.tryBegin(moved, 1280, 576))
        attempts.reset()
        assertTrue(attempts.tryBegin(moved, 1280, 576))
    }
}
