package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class ScreenImageFocusTest {
    @Test fun retryImagePreservesThePinnedPhotoAndAllFourButtons() {
        val crop = ScreenImage.focusQuizBounds(1280, 576, false)
        val photo = PxRect(100, 190, 425, 452)
        val choices = PxRect(418, 285, 904, 442)
        for (region in listOf(photo, choices)) {
            assertTrue(crop.left <= region.left && crop.top <= region.top)
            assertTrue(crop.right >= region.right && crop.bottom >= region.bottom)
        }
    }

    @Test fun whiteQuestionBannerAtTheTopRemainsInTheRetry() {
        assertEquals(0, ScreenImage.focusQuizBounds(1280, 576, true).top)
    }
}
