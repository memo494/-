package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScreenReader's decisions (what it returns, what it declines), with a fake network and synthetic screens. */
class ScreenReaderTest {

    private val dictionary = listOf("ا", "ب")

    /** Fake network that always reads the same two letters with probability [p]. */
    private fun reader(p: Float = 0.9f, minConfidence: Float = ScreenReader.DEFAULT_MIN_CONFIDENCE): ScreenReader {
        val model = CtcModel { _, _ ->
            val classes = 4
            val emit = listOf(1, 2)
            val v = FloatArray(emit.size * classes)
            for ((t, c) in emit.withIndex()) for (k in 0 until classes) v[t * classes + k] = if (k == c) p else (1f - p) / 3
            CtcLogits(emit.size, classes, v)
        }
        return ScreenReader(LineRecognizer(model, dictionary), minConfidence = minConfidence)
    }

    @Test
    fun multipleChoice_returnsQuestionOptionsAndTheirButtons() {
        val c = SyntheticScreens.quiz(600, 1300)
        val q = reader().read(c.px, c.w, c.h)
        assertNotNull(q)
        q!!
        assertFalse(q.numeric)
        // Two question lines of "با" joined by a space.
        assertEquals("با با", q.questionText)
        assertEquals(listOf("با", "با", "با", "با"), q.options)
        assertEquals(4, q.optionRects.size)
        assertEquals(0.9f, q.meanConfidence, 1e-4f)
        for ((a, b) in q.optionRects.zipWithNext()) assertTrue(a.top < b.top)
    }

    @Test
    fun aButtonWithoutText_isDroppedTogetherWithItsRect() {
        val c = SyntheticScreens.quiz(600, 1300, emptyButtons = setOf(1))
        val q = reader().read(c.px, c.w, c.h)!!
        assertEquals(3, q.options.size)
        assertEquals(3, q.optionRects.size)
        // The rects left are buttons 0, 2 and 3 (button 1 is the one without text).
        val all = ColorLayout().analyze(c.px, c.w, c.h)!!.buttons
        assertEquals(listOf(all[0], all[2], all[3]), q.optionRects)
    }

    @Test
    fun numericQuestion_hasNoOptions() {
        val c = SyntheticScreens.numeric(600, 1300)
        val q = reader().read(c.px, c.w, c.h)
        assertNotNull(q)
        q!!
        assertTrue(q.numeric)
        assertTrue(q.options.isEmpty())
        assertTrue(q.optionRects.isEmpty())
        assertTrue(q.questionText.isNotEmpty())
    }

    @Test
    fun fewerThanTwoReadableOptions_isDeclined() {
        val c = SyntheticScreens.quiz(600, 1300, emptyButtons = setOf(0, 1, 2))
        val reading = reader().analyze(c.px, c.w, c.h)
        assertNull(reading.question)
        assertEquals("fewer than 2 options read", reading.failure)
    }

    @Test
    fun lowConfidence_isDeclined_andTheThresholdIsInclusive() {
        val c = SyntheticScreens.quiz(600, 1300)
        val low = reader(p = 0.6f).analyze(c.px, c.w, c.h)
        assertNull(low.question)
        assertTrue(low.failure!!.startsWith("low confidence"))
        // Exactly the threshold passes (no float rounding trouble at 0.75).
        assertNotNull(reader(p = 0.75f, minConfidence = 0.75f).read(c.px, c.w, c.h))
    }

    @Test
    fun noBannerOrNoText_isDeclined() {
        val none = Canvas(600, 1300, Canvas.BLUE)
        assertEquals("no question banner", reader().analyze(none.px, none.w, none.h).failure)
        val bare = Canvas(600, 1300, Canvas.BLACK)
        bare.fill(60, 520, 540, 676, Canvas.CREAM)
        assertEquals("no question text lines", reader().analyze(bare.px, bare.w, bare.h).failure)
    }

    @Test
    fun readingExposesTimingAndLines_forDiagnostics() {
        val c = SyntheticScreens.quiz(600, 1300)
        val r = reader().analyze(c.px, c.w, c.h)
        assertNotNull(r.layout)
        assertEquals(2, r.questionLines.size)
        assertEquals(4, r.optionLines.size)
        assertTrue(r.layoutMs >= 0 && r.recognizeMs >= 0)
    }
}
