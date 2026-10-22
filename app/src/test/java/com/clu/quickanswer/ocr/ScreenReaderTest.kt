package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScreenReader's decisions (what it returns, what it declines), with a fake network and synthetic screens. */
class ScreenReaderTest {

    /**
     * Fake network. Call number n (0 = first question line, then the rest of the question lines, then
     * the options top to bottom) reads [length] letters, alternating the first two classes of
     * [dictionary], each with probability [probability].
     */
    private fun fakeModel(
        probability: (call: Int) -> Float = { 0.9f },
        length: (call: Int) -> Int = { 5 + it },
    ): CtcModel {
        var call = 0
        return CtcModel { _, _ ->
            val n = call++
            val classes = 4
            val p = probability(n)
            val emit = List(length(n)) { 1 + it % 2 }
            val v = FloatArray(emit.size * classes)
            for ((t, c) in emit.withIndex()) for (k in 0 until classes) v[t * classes + k] = if (k == c) p else (1f - p) / 3
            CtcLogits(emit.size, classes, v)
        }
    }

    private fun reader(
        p: Float = 0.9f,
        minConfidence: Float = ScreenReader.DEFAULT_MIN_CONFIDENCE,
        dictionary: List<String> = listOf("ا", "ب"),
        probability: (Int) -> Float = { p },
        length: (Int) -> Int = { 5 + it },
    ): ScreenReader =
        ScreenReader(LineRecognizer(fakeModel(probability, length), dictionary), minConfidence = minConfidence)

    private fun read(c: Canvas, reader: ScreenReader = reader(), masks: List<PxRect> = emptyList()) =
        reader.analyze(c.px, c.w, c.h, masks)

    @Test
    fun multipleChoice_returnsQuestionOptionsAndTheirButtons() {
        val c = SyntheticScreens.quiz(600, 1300)
        val reading = read(c)
        val q = reading.question
        assertNotNull(reading.failure, q)
        q!!
        assertFalse(q.numeric)
        // Two question lines of 5 and 6 letters, then options of 7 to 10 letters.
        assertEquals(2, reading.questionLines.size)
        assertEquals(reading.questionLines.joinToString(" ") { it.text }, q.questionText)
        assertEquals(listOf(7, 8, 9, 10), q.options.map { it.length })
        assertEquals(4, q.optionRects.size)
        assertEquals(0.9f, q.meanConfidence, 1e-4f)
        for ((a, b) in q.optionRects.zipWithNext()) assertTrue(a.top < b.top)
    }

    @Test
    fun aButtonWithoutText_declinesTheWholeReading() {
        // Dropping it would leave a question whose right answer might be the option that was dropped.
        for (empty in 0..3) {
            val c = SyntheticScreens.quiz(600, 1300, emptyButtons = setOf(empty))
            val reading = read(c)
            assertNull("button $empty", reading.question)
            assertEquals("a button has no readable text", reading.failure)
        }
    }

    @Test
    fun numericQuestion_hasNoOptions() {
        val c = SyntheticScreens.numeric(600, 1300)
        val q = read(c).question
        assertNotNull(q)
        q!!
        assertTrue(q.numeric)
        assertTrue(q.options.isEmpty())
        assertTrue(q.optionRects.isEmpty())
        assertTrue(q.questionText.isNotEmpty())
    }

    @Test
    fun aNumberFieldWithoutAKeypad_isNotAQuestion() {
        val c = SyntheticScreens.numeric(600, 1300, keypad = false)
        assertEquals("no keypad under the number field", read(c).failure)
    }

    @Test
    fun fewerThanTwoOptions_isDeclined() {
        val c = SyntheticScreens.quiz(600, 1300, buttonCount = 1)
        val reading = read(c)
        assertNull(reading.question)
        assertEquals("fewer than 2 options read", reading.failure)
    }

    @Test
    fun lowConfidence_isDeclined_andTheThresholdIsInclusive() {
        val c = SyntheticScreens.quiz(600, 1300)
        val low = read(c, reader(p = 0.6f))
        assertNull(low.question)
        assertTrue(low.failure!!.startsWith("low confidence"))
        // Exactly the threshold passes (no float rounding trouble at 0.75).
        assertNotNull(read(c, reader(p = 0.75f, minConfidence = 0.75f)).question)
    }

    @Test
    fun cleanOptions_doNotHideAGarbledQuestion() {
        val c = SyntheticScreens.quiz(600, 1300)
        // The two question lines read at 0.7 each (fine alone), the options at 0.98: 0.91 overall.
        val r = reader(probability = { if (it < 2) 0.7f else 0.98f })
        val reading = read(c, r)
        assertNull(reading.question)
        assertTrue(reading.failure!!, reading.failure!!.startsWith("low question confidence"))
    }

    @Test
    fun aSingleUnreliableQuestionLine_isDeclined() {
        val c = SyntheticScreens.quiz(600, 1300)
        val r = reader(probability = { if (it == 0) 0.5f else 0.98f })
        assertEquals("a question line is unreliable", read(c, r).failure)
    }

    @Test
    fun aSingleUnreliableOptionLine_isDeclined_evenWhenTheMeanIsHigh() {
        val c = SyntheticScreens.quiz(600, 1300)
        // Calls 0-1 are the question lines, 2-5 the options; the third option reads at 0.5, the rest at 0.98:
        // the overall (0.93) and question (0.98) means are fine.
        for (bad in 2..5) {
            val r = reader(probability = { if (it == bad) 0.5f else 0.98f })
            val reading = read(c, r)
            assertNull("option ${bad - 2}", reading.question)
            assertEquals("an option line is unreliable", reading.failure)
        }
        // At the floor it passes; just under it does not.
        assertNotNull(read(c, reader(probability = { if (it == 3) 0.62f else 0.98f })).question)
        assertEquals("an option line is unreliable", read(c, reader(probability = { if (it == 3) 0.58f else 0.98f })).failure)
    }

    @Test
    fun aQuestionThatIsNotArabic_orTooShort_isDeclined() {
        val c = SyntheticScreens.quiz(600, 1300)
        assertEquals("question is not Arabic", read(c, reader(dictionary = listOf("a", "b"))).failure)
        assertEquals("question too short", read(c, reader(length = { 2 + it })).failure)
    }

    @Test
    fun twoOptionsThatReadTheSame_areDeclined() {
        val c = SyntheticScreens.quiz(600, 1300)
        assertEquals("two options read the same", read(c, reader(length = { if (it < 2) 5 + it else 8 })).failure)
    }

    @Test
    fun anOptionThatIsNotArabic_isDeclined() {
        // Same letters for the question; the options come out as Latin letters.
        val c = SyntheticScreens.quiz(600, 1300)
        var call = 0
        val model = CtcModel { _, _ ->
            val n = call++
            val emit = List(5 + n) { if (n < 2) 1 + it % 2 else 3 + it % 2 }
            val v = FloatArray(emit.size * 6)
            for ((t, cl) in emit.withIndex()) for (k in 0 until 6) v[t * 6 + k] = if (k == cl) 0.9f else 0.02f
            CtcLogits(emit.size, 6, v)
        }
        val r = ScreenReader(LineRecognizer(model, listOf("ا", "ب", "x", "y")))
        assertEquals("an option is not Arabic text", read(c, r).failure)
    }

    @Test
    fun aStrayLastLine_isNotPartOfTheQuestion() {
        // A third, small mark at the bottom of the banner reads as one unsure letter: dropped, so the
        // question (and its memory key) is the two real lines.
        val c = SyntheticScreens.quiz(600, 1300)
        c.ink(250, 600, 350, 613)
        val r = reader(probability = { if (it == 2) 0.45f else 0.9f }, length = { if (it == 2) 1 else 5 + it })
        val reading = read(c, r)
        assertNotNull(reading.failure, reading.question)
        assertEquals(2, reading.questionLines.size)
    }

    @Test
    fun anOverlayOverTheQuestionOrAButton_declines_butNotOverTheBannersMargin() {
        val c = SyntheticScreens.quiz(600, 1300)
        val layout = ColorLayout().analyze(c.px, c.w, c.h)!!
        val button = layout.buttons[2]
        val line = layout.questionLines[0]
        // The bubble on button 3, on the first question line, one pitch below the last button.
        val onButton = PxRect(button.left + 20, button.top + 10, button.left + 80, button.top + 60)
        val onLine = PxRect(line.left + 5, line.top, line.left + 60, line.bottom)
        val last = layout.buttons.last()
        val below = PxRect(last.left, last.bottom + 10, last.left + 50, last.bottom + 50)
        for (mask in listOf(onButton, onLine, below)) {
            val reading = read(c, masks = listOf(mask))
            assertNull("mask $mask", reading.question)
            assertEquals("an overlay window covers the question", reading.failure)
        }
        // The banner's empty margin, the side of the screen and the area far below are free to cover.
        val banner = layout.banner
        val margin = PxRect(banner.left, banner.top, banner.left + 30, banner.top + 40)
        val side = PxRect(0, 700, 60, 800)
        val far = PxRect(200, 1250, 400, 1290)
        assertNotNull(read(c, masks = listOf(margin, side, far)).question)
    }

    @Test
    fun noBannerOrNoText_isDeclined() {
        val none = Canvas(600, 1300, Canvas.BLUE)
        assertEquals("no question banner", read(none).failure)
        val bare = Canvas(600, 1300, Canvas.BLACK)
        bare.fill(60, 520, 540, 637, Canvas.CREAM)
        assertEquals("no question text lines", read(bare).failure)
    }

    @Test
    fun readingExposesTimingAndLines_forDiagnostics() {
        val c = SyntheticScreens.quiz(600, 1300)
        val r = read(c)
        assertNotNull(r.layout)
        assertEquals(2, r.questionLines.size)
        assertEquals(4, r.optionLines.size)
        assertTrue(r.layoutMs >= 0 && r.recognizeMs >= 0)
    }
}
