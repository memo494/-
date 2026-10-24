package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class TouchFrameMaskTest {
    private val area = PxRect(100, 200, 356, 296)
    private fun stamp() = IntArray(TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT) { if (it % 13 == 0) 40 else 200 }

    @Test fun bubbleIsIgnoredAtTheSamePositionInBlackCaptureAndLiveFrame() {
        val bubble = PxRect(120, 220, 140, 240)
        val captured = stamp()
        val current = stamp()
        for (y in 10 until 20) for (x in 10 until 20) {
            captured[y * TouchFrameGuard.WIDTH + x] = 0
            current[y * TouchFrameGuard.WIDTH + x] = 240
        }
        assertFalse(TouchFrameGuard.matches(captured, current))
        assertTrue(TouchFrameGuard.matches(TouchFrameMask.apply(captured, area, listOf(bubble)),
            TouchFrameMask.apply(current, area, listOf(bubble))))
    }

    @Test fun clippedOverlayOnlyMasksItsIntersectionWithTheQuestion() {
        val masked = TouchFrameMask.apply(stamp(), area, listOf(PxRect(50, 180, 110, 210)))
        assertEquals(-1, masked[0])
        assertEquals(-1, masked[5 * TouchFrameGuard.WIDTH + 5])
        assertNotEquals(-1, masked[8 * TouchFrameGuard.WIDTH + 8])
        assertNotEquals(-1, masked.last())
    }

    @Test fun overlaysOutsideTheQuestionDoNotEraseEvidence() {
        val original = stamp()
        assertArrayEquals(original, TouchFrameMask.apply(original.copyOf(), area,
            listOf(PxRect(0, 0, 99, 199), PxRect(356, 200, 400, 250))))
    }

    @Test fun aPanelCoveringTooMuchOfTheQuestionCannotAuthorizeTouches() {
        val masked = TouchFrameMask.apply(stamp(), area, listOf(PxRect(100, 200, 240, 296)))
        assertFalse(TouchFrameGuard.usable(masked))
        assertFalse(TouchFrameGuard.matches(masked, masked))
    }
}
