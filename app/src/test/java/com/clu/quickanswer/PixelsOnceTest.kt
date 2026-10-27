package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** One question's capture is copied to an array once, shared by the reading and the taps. */
class PixelsOnceTest {
    @Test fun theCaptureIsReadOnceForEveryUse() {
        var reads = 0
        val pixels = PixelsOnce { reads++; IntArray(16) }
        val forReading = pixels.get()
        val forTouches = pixels.get()
        assertSame(forReading, forTouches)
        assertEquals(1, reads)
    }

    @Test fun nothingIsReadWhenNobodyAsks() {
        var reads = 0
        PixelsOnce { reads++; IntArray(16) }
        assertEquals(0, reads)
    }
}
