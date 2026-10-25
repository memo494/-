package com.clu.quickanswer

import com.clu.quickanswer.ocr.OcrFixtures
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun optionNumber_readsABareNumber() {
        assertEquals(2, AnswerParser.optionNumber("2", 4))
        assertEquals(2, AnswerParser.optionNumber("الخيار 2", 4))
        assertEquals(2, AnswerParser.optionNumber("Option 2:", 4))
        assertEquals(2, AnswerParser.optionNumber("(2)", 4))
        assertEquals(2, AnswerParser.optionNumber("2)", 4))
        assertEquals(2, AnswerParser.optionNumber("٢", 4))
        assertEquals(3, AnswerParser.optionNumber(" 3.\n", 4))
        assertNull(AnswerParser.optionNumber("5", 4))
        assertNull(AnswerParser.optionNumber("0", 4))
        assertNull(AnswerParser.optionNumber("لا أعرف", 4))
    }

    @Test
    fun optionNumber_isNotTakenFromProse_orFromTheOptionsText() {
        for (reply in listOf(
            "ليس 1 بل 2", "الخيار 1 خاطئ، الصحيح 2", "Option 1 is wrong, 2 is right", "من بين 4 خيارات الصحيح 2",
            "2) سورة طه", "القرن 3", "3 أيام", QuestionPrompt.NONE,
        )) {
            assertNull(reply, AnswerParser.optionNumber(reply, 4))
        }
    }

    @Test
    fun resolve_declinesProseThatNamesSeveralOptions() {
        for (reply in listOf("ليس 1 بل 2", "الخيار 1 خاطئ، الصحيح 2", "Option 1 is wrong, 2 is right", "من بين 4 خيارات الصحيح 2", "لا يوجد سؤال", "لا أعرف", QuestionPrompt.NONE)) {
            assertNull(reply, AnswerParser.resolve(mcq, reply))
        }
        assertNull(AnswerParser.resolve(numeric, QuestionPrompt.NONE))
    }

    private fun mcqOf(vararg options: String) =
        Question("سؤال طويل بما يكفي للاختبار", options.toList(), List(options.size) { PxRect(0, it * 10, 100, it * 10 + 8) }, false, 0.9f)

    @Test
    fun resolve_anOptionTextWithASmallNumberIsNotTakenForAnOptionNumber() {
        val centuries = mcqOf("القرن 2", "القرن 3", "القرن 4", "القرن 5")
        assertEquals("القرن 3", AnswerParser.resolve(centuries, "القرن 3")?.answer)
        val days = mcqOf("3 أيام", "5 أيام", "7 أيام", "10 أيام")
        assertEquals("3 أيام", AnswerParser.resolve(days, "3 أيام")?.answer)
        val stages = mcqOf("المرحلة 3", "المرحلة 1", "المرحلة 4", "المرحلة 2")
        assertEquals("المرحلة 2", AnswerParser.resolve(stages, "المرحلة 2")?.answer)
        // The number alone still means the option number here: nothing else reads "2" or "3".
        assertEquals("القرن 3", AnswerParser.resolve(centuries, "2")?.answer)
    }

    @Test
    fun resolve_aLabelAndATextThatDisagree_isDeclined_butAgreeingOnesMap() {
        val centuries = mcqOf("القرن 2", "القرن 3", "القرن 4", "القرن 5")
        assertNull(AnswerParser.resolve(centuries, "3) القرن 2"))
        assertEquals("القرن 2", AnswerParser.resolve(centuries, "1) القرن 2")?.answer)
        assertEquals("سورة طه", AnswerParser.resolve(mcq, "2) سورة طه")?.answer)
        assertEquals("سورة طه", AnswerParser.resolve(mcq, "٢ - سورة طه")?.answer)
        assertNull(AnswerParser.resolveImageAnswer(centuries, "3) القرن 2"))
    }

    @Test
    fun resolve_optionsThatAreNumbers_doNotMixUpTheIndexAndTheValue() {
        val q = mcqOf("4", "6", "5", "3")
        // "3" could be the third option (5) or the option whose value is 3: ask again with the picture.
        assertNull(AnswerParser.resolve(q, "3"))
        assertEquals("6", AnswerParser.resolve(q, "2")?.answer)
        // A value that cannot be an index is clearly the value.
        assertEquals("6", AnswerParser.resolve(q, "6")?.answer)
        // When the index and the value agree there is nothing to mix up.
        assertEquals("2", AnswerParser.resolve(mcqOf("1", "2", "3", "4"), "2")?.answer)
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
    fun number_isNullWhenAScaleWordFollows_orThePartsAreSplit() {
        // The digits alone would be off by a thousand or a million.
        for (reply in listOf("5 ملايين", "٣٠٠ ألف", "1.5 billion", "2 مليار نسمة", "3 million", "7k")) {
            assertNull(reply, AnswerParser.number(reply))
        }
        // A comma that is a decimal comma, not a thousands separator.
        assertNull(AnswerParser.number("1,5"))
        assertNull(AnswerParser.number("12,34"))
    }

    @Test
    fun number_readsSpaceSeparatedThousands() {
        assertEquals("8000000", AnswerParser.number("8 000 000"))
        assertEquals("8000000", AnswerParser.number("8\u00A0000\u00A0000"))
        assertEquals("1234", AnswerParser.number("1 234 شخص"))
        assertEquals("-5", AnswerParser.number("-5"))
        assertEquals("80", AnswerParser.number("80 كم"))
    }

    @Test
    fun numberOnly_acceptsExactlyOneNumber() {
        assertEquals("7", AnswerParser.numberOnly("٧"))
        assertEquals("1234", AnswerParser.numberOnly("1,234"))
        assertEquals("8000000", AnswerParser.numberOnly("8 000 000"))
        assertEquals("-5", AnswerParser.numberOnly(" -5 "))
        for (reply in listOf("1. 7", "7 - 8", "7 8", "خطأ 429", "gemini-3-flash-preview: 429", "1,5", "5 ملايين", "")) {
            assertNull(reply, AnswerParser.numberOnly(reply))
        }
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

    @Test
    fun prompt_offersAWayOut_forAScreenThatIsNotAQuestion() {
        // Without it every false positive of the reader would come back as a confident answer.
        assertTrue(QuestionPrompt.build(mcq).contains("reply exactly: NONE"))
        assertTrue(QuestionPrompt.build(numeric).contains("reply exactly: NONE"))
    }

    @Test
    fun aQuestionAboutAPicture_isRecognised() {
        assertTrue(QuestionPrompt.refersToPicture("ما اسم صاحب هذه الصورة؟"))
        assertTrue(QuestionPrompt.refersToPicture("ما هو الشعار الظاهر؟"))
        assertFalse(QuestionPrompt.refersToPicture("ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟"))
        assertFalse(QuestionPrompt.refersToPicture("ما عاصمة فرنسا؟"))
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

    @Test
    fun numberOnly_readsArabicIndicDigits() {
        assertEquals("123", AnswerParser.numberOnly("١٢٣"))
    }

    @Test
    fun resolveImageAnswer_ignoresALoneFirstLabel() {
        val q = mcqOf("أ", "ب", "القلب", "د")
        assertEquals("القلب", AnswerParser.resolveImageAnswer(q, "1. القلب")?.answer)
        assertEquals(q.optionRects[2], AnswerParser.resolveImageAnswer(q, "1. القلب")?.rect)
    }

    @Test
    fun stripLoneFirstLabel_onlyStripsASingleLine() {
        assertEquals("42", AnswerParser.numberOnly(AnswerParser.stripLoneFirstLabel("1) 42")))
        assertEquals("1. أ\n2. ب", AnswerParser.stripLoneFirstLabel("1. أ\n2. ب"))
        assertEquals("2. أ", AnswerParser.stripLoneFirstLabel("2. أ"))
    }

    @Test
    fun resolve_aMismatchedLabelIsStillDeclined() {
        val centuries = mcqOf("القرن 1", "القرن 2", "القرن 3", "القرن 4")
        assertNull(AnswerParser.resolve(centuries, "3) القرن 2"))
    }
}
