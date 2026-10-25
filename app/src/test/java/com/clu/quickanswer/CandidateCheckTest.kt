package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract for [CandidateCheck]: does the model's answer confirm what a CANDIDATE template read? */
class CandidateCheckTest {

    private val rects = List(4) { PxRect(100, 100 + it * 60, 400, 150 + it * 60) }
    private val mcq = Question(
        "من هو المطرب المصري صاحب أغنية يا مسافر وحدك؟",
        listOf("أحمد شوقي", "محمد عبدالوهاب", "عمرو دياب", "عمر خيرت"), rects, false, 0.9f,
    )
    private val numeric = Question("في أي سنة تم تأسيس شركة مايكروسوفت؟", emptyList(), emptyList(), true, 0.9f)

    @Test fun anAnswerThatNamesAnOptionConfirms_andGivesItsBox() {
        val r = CandidateCheck.judge(mcq, replyIsAnswer = true, replyText = "محمد عبدالوهاب")
        assertEquals(CandidateCheck.Verdict.CONFIRMED, r.verdict)
        assertEquals("محمد عبدالوهاب", r.resolved!!.answer)
        assertEquals(rects[1], r.resolved!!.rect)
    }

    @Test fun anAnswerThatIsNoneOfTheReadOptionsRefutes() {
        val r = CandidateCheck.judge(mcq, replyIsAnswer = true, replyText = "أم كلثوم")
        assertEquals(CandidateCheck.Verdict.REFUTED, r.verdict)
        assertNull(r.resolved)
    }

    @Test fun aNumberConfirmsANumericRead_wordsRefuteIt() {
        val ok = CandidateCheck.judge(numeric, replyIsAnswer = true, replyText = "1975")
        assertEquals(CandidateCheck.Verdict.CONFIRMED, ok.verdict)
        assertEquals("1975", ok.resolved!!.answer)
        assertNull("no box for a number", ok.resolved!!.rect)
        assertEquals(CandidateCheck.Verdict.REFUTED, CandidateCheck.judge(numeric, true, "Electro").verdict)
    }

    @Test fun aBareNumberOnAChoiceScreenRefutes() {
        // The model answered a number field the template thinks is a choice screen, or the other way round.
        assertEquals(CandidateCheck.Verdict.REFUTED, CandidateCheck.judge(mcq, true, "1975").verdict)
    }

    @Test fun errorsAndNoQuestionSayNothing() {
        for ((isAnswer, text) in listOf(
            false to "خطأ 429",
            false to "تعذّر الاتصال",
            true to ScreenImage.NO_QUESTION,
            true to "",
        )) {
            val r = CandidateCheck.judge(mcq, isAnswer, text)
            assertEquals(text, CandidateCheck.Verdict.UNKNOWN, r.verdict)
            assertNull(r.resolved)
        }
    }

    @Test fun repliesThatAreNotASingleAnswerSayNothing() {
        for (text in listOf("1. أحمد شوقي\n2. عمر خيرت", "أحمد شوقي + عمر خيرت", "1-ب، 2-أ", "غير متأكد", "غير متأكد.")) {
            val r = CandidateCheck.judge(mcq, true, text)
            assertEquals(text, CandidateCheck.Verdict.UNKNOWN, r.verdict)
            assertNull(r.resolved)
        }
        assertEquals(CandidateCheck.Verdict.REFUTED, CandidateCheck.judge(mcq, true, "أم كلثوم").verdict)
    }
}
