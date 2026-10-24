package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class OptionWordDetectorTest {
    private val w = 1536
    private val h = 691

    private fun pixels() = IntArray(w * h) { 0xffead6b5.toInt() }

    private fun printWord(image: IntArray, x: Int, y: Int, width: Int = 85) {
        // Several distinct bright strokes imitate white Arabic text on a dark button.
        for (xx in x until x + width) for (yy in y until y + 27) {
            if ((xx / 5 + yy / 4) % 3 != 0) image[yy * w + xx] = 0xfff5f5f5.toInt()
        }
    }

    @Test fun detectsPrintedTextInRightToLeftReadingOrder() {
        val image = pixels()
        printWord(image, 601, 389)
        printWord(image, 894, 390, 108)
        printWord(image, 609, 474)
        printWord(image, 902, 475, 94)
        val result = OptionWordDetector.detect(image, w, h)!!
        assertEquals(4, result.size)
        assertTrue(result[0].centerX > result[1].centerX)
        assertTrue(result[2].centerX > result[3].centerX)
        assertTrue(result[0].centerY < result[2].centerY)
        assertTrue(result.all { it.width >= 40 && it.height >= 17 })
        assertEquals(4, result.toSet().size)
    }

    @Test fun rejectsMissingOptionOrMismatchedRows() {
        val image = pixels()
        printWord(image, 610, 389)
        printWord(image, 900, 389)
        printWord(image, 610, 474)
        assertNull(OptionWordDetector.detect(image, w, h))
        printWord(image, 900, 510)
        assertNull(OptionWordDetector.detect(image, w, h))
    }

    @Test fun ignoresCreamBackgroundAndNumericKeypads() {
        assertNull(OptionWordDetector.detect(pixels(), w, h))
        assertNull(OptionWordDetector.detect(IntArray(30), w, h))
        assertNull(OptionWordDetector.detect(IntArray(320 * 300), 320, 300))
    }
}
