package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recogniser's wiring and preprocessing, checked with a fake network. */
class LineRecognizerTest {

    // Dictionary of two letters: class 0 = blank, 1 = alef, 2 = beh, 3 = the space the model appends.
    private val dictionary = listOf("ا", "ب")

    /** Fake network that records its input and "reads" the classes in [emit], one time step each. */
    private class FakeModel(private val emit: List<Int>, private val p: Float = 0.9f) : CtcModel {
        var lastInput: FloatArray? = null
        var lastWidth = -1
        var calls = 0
        override fun run(input: FloatArray, width: Int): CtcLogits {
            calls++
            lastInput = input
            lastWidth = width
            val classes = 4
            val v = FloatArray(emit.size * classes)
            for ((t, c) in emit.withIndex()) for (k in 0 until classes) v[t * classes + k] = if (k == c) p else (1f - p) / 3
            return CtcLogits(emit.size, classes, v)
        }
    }

    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int) = IntArray(w * h) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }

    @Test
    fun decodesToReadingOrder_andReportsConfidence() {
        // The network emits alef, beh in visual order (left to right); Arabic reads right to left.
        val model = FakeModel(listOf(1, 0, 2))
        val line = LineRecognizer(model, dictionary).recognize(solid(100, 30, 0, 0, 0), 100, 30, PxRect(0, 0, 100, 30))
        assertEquals("با", line.text)
        assertEquals(2, line.charCount)
        assertEquals(0.9f, line.confidence, 1e-5f)
    }

    @Test
    fun theLastClassIsASpace() {
        val model = FakeModel(listOf(1, 0, 3, 0, 2))
        val line = LineRecognizer(model, dictionary).recognize(solid(100, 30, 0, 0, 0), 100, 30, PxRect(0, 0, 100, 30))
        assertEquals("ب ا", line.text)
    }

    @Test
    fun inputIsPlanarBgrScaledToMinusOneToOne_at48PxHigh() {
        val model = FakeModel(listOf(1))
        // Crop of 96 x 24 -> 48 high, so 192 wide.
        val img = solid(200, 100, r = 255, g = 51, b = 0)
        LineRecognizer(model, dictionary).recognize(img, 200, 100, PxRect(10, 10, 106, 34))
        assertEquals(192, model.lastWidth)
        val input = model.lastInput!!
        val plane = 48 * 192
        assertEquals(3 * plane, input.size)
        assertEquals("blue plane first", -1f, input[0], 1e-6f)            // b = 0
        assertEquals("green plane second", 51 / 127.5f - 1f, input[plane], 1e-6f) // g = 51
        assertEquals("red plane last", 1f, input[2 * plane + 100], 1e-6f) // r = 255
    }

    @Test
    fun cropIsSampledFromTheRequestedRectOnly() {
        // Left half white, right half black; crop the right half -> all -1.
        val w = 200
        val h = 40
        val img = IntArray(w * h) { if (it % w < 100) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val model = FakeModel(listOf(1))
        LineRecognizer(model, dictionary).recognize(img, w, h, PxRect(100, 0, 200, 40))
        assertTrue(model.lastInput!!.all { it == -1f })
        LineRecognizer(model, dictionary).recognize(img, w, h, PxRect(0, 0, 100, 40))
        assertTrue(model.lastInput!!.all { it == 1f })
    }

    @Test
    fun veryNarrowCropsGetAMinimumWidth() {
        val model = FakeModel(listOf(1))
        LineRecognizer(model, dictionary).recognize(solid(50, 50, 0, 0, 0), 50, 50, PxRect(0, 0, 2, 40))
        assertEquals(8, model.lastWidth)
    }

    @Test
    fun emptyOrOutsideRectsGiveAnEmptyLineWithoutRunningTheNetwork() {
        val model = FakeModel(listOf(1))
        val rec = LineRecognizer(model, dictionary)
        val img = solid(50, 50, 0, 0, 0)
        assertEquals("", rec.recognize(img, 50, 50, PxRect(10, 10, 10, 30)).text)
        assertEquals("", rec.recognize(img, 50, 50, PxRect(60, 60, 80, 80)).text)
        assertEquals(0, model.calls)
        assertEquals(0f, rec.recognize(img, 50, 50, PxRect(10, 10, 10, 30)).confidence, 0f)
    }
}
