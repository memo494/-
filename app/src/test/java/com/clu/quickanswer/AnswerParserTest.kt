package com.clu.quickanswer

import com.clu.quickanswer.ocr.OcrFixtures
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerParserTest {

    private val rects = List(4) { PxRect(100, 100 + it * 50, 400, 140 + it * 50) }
    private val mcq = Question(
        "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟",
        listOf("سورة البقرة", "سورة طه", "سورة الإخلاص", "سورة النور"), rects, false, 0.9f
    )
    private val numeric = Question("كم عدد أيام الأسبوع؟", emptyList(), emptyList(), true, 0.9f)

    @Test
    fun optionNumber_readsShortReplies() {
        assertEquals(2, AnswerParser.optionNumber("2", 4))
        assertEquals(2, AnswerParser.optionNumber("الخيار 2", 4))
        assertEquals(2, AnswerParser.optionNumber("2) سورة طه", 4))
        assertEquals(2, AnswerParser.optionNumber("٢", 4))
        assertEquals(3, AnswerParser.optionNumber(" 3.\n", 4))
        assertNull(AnswerParser.optionNumber("5", 4))
        assertNull(AnswerParser.optionNumber("0", 4))
        assertNull(AnswerParser.optionNumber("لا أعرف", 4))
    }

    @Test
    fun number_readsDigitsInAnyScript() {
        assertEquals("123", AnswerParser.number("123"))
        assertEquals("123", AnswerParser.number("١٢٣"))
        assertEquals("1234", AnswerParser.number("حوالي 1,234 شخص"))
        assertEquals("2.5", AnswerParser.number("٢٫٥"))
        assertNull(AnswerParser.number("لا يوجد سؤال"))
    }

    @Test
    fun resolve_mapsTheNumberToTheOptionTextAndButton() {
        val r = AnswerParser.resolve(mcq, "2")
        assertNotNull(r)
        assertEquals("سورة طه", r!!.answer)
        assertEquals(rects[1], r.rect)
    }

    @Test
    fun resolve_acceptsTheOptionTextInsteadOfItsNumber() {
        assertEquals("سورة النور", AnswerParser.resolve(mcq, "سورة النور")?.answer)
    }

    @Test
    fun resolve_numericGivesTheNumberAndNoBox() {
        val r = AnswerParser.resolve(numeric, "٧")
        assertEquals("7", r?.answer)
        assertNull(r?.rect)
    }

    @Test
    fun resolveStored_findsTheOptionAfterAShuffle() {
        val shuffled = mcq.copy(options = mcq.options.reversed(), optionRects = rects.reversed())
        val r = AnswerParser.resolveStored(shuffled, "سورة طه")
        assertEquals("سورة طه", r?.answer)
        assertEquals(rects[1], r?.rect)
    }

    @Test
    fun resolveImageAnswer_matchesALabelledReply_butNotAnError() {
        assertEquals("سورة طه", AnswerParser.resolveImageAnswer(mcq, "2) سورة طه")?.answer)
        assertNull(AnswerParser.resolveImageAnswer(mcq, "gemini-3-flash-preview: خطأ 429"))
        assertNull(AnswerParser.resolveImageAnswer(numeric, "7"))
    }

    @Test
    fun prompt_listsNumberedOptions_orAsksForDigits() {
        val p = QuestionPrompt.build(mcq)
        assertTrue(p.contains("1) سورة البقرة"))
        assertTrue(p.contains("4) سورة النور"))
        assertTrue(p.contains("number of the correct option"))
        assertTrue(QuestionPrompt.build(numeric).contains("ONLY digits"))
    }

    // The whole on-device path on real screenshots: read, then map the model's option number.
    @Test
    fun realScreenshots_mapToTheRightOptionAndButton() {
        val s2 = OcrFixtures.image("s2")
        val q2 = OcrFixtures.reader.read(s2.argb, s2.width, s2.height)
        assertNotNull(q2)
        val r2 = AnswerParser.resolve(q2!!, "2")
        assertNotNull(r2)
        assertTrue(AnswerMemory.keyOf(r2!!.answer) == AnswerMemory.keyOf("سورة طه"))
        assertEquals(q2.optionRects[1], r2.rect)

        val s3 = OcrFixtures.image("s3")
        val q3 = OcrFixtures.reader.read(s3.argb, s3.width, s3.height)
        assertNotNull(q3)
        val r3 = AnswerParser.resolve(q3!!, "3")
        assertTrue(AnswerMemory.keyOf(r3!!.answer) == AnswerMemory.keyOf("عودة الحوت للبحر مفعما بالحياة"))
        val box = r3.rect!!
        assertTrue(box.width > 0 && box.height > 0 && box.right <= s3.width && box.bottom <= s3.height)

        // A second reading of the same screen hits the memory.
        val memory = AnswerMemory()
        memory.put(q3.questionText, q3.options, q3.numeric, r3.answer)
        val again = OcrFixtures.reader.read(s3.argb, s3.width, s3.height)!!
        assertEquals(r3.answer, memory.lookup(again.questionText, again.options.shuffled(), again.numeric)?.answer)
    }
}
