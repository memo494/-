package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.*
import org.junit.Test

class CandidateIndexRegressionTest {
    @Test fun anImagePositionDoesNotPromoteAnUntrustedLayout() {
        for (n in 2..8) {
            val q = Question("ما الجواب الصحيح؟", (1..n).map { "نص الاختيار $it" },
                (0 until n).map { PxRect(100, 100 + it * 60, 400, 150 + it * 60) }, false, 0.95f)
            for (index in 1..n) {
                assertNull(AnswerParser.resolveImageAnswer(q, index.toString()))
                assertEquals(CandidateCheck.Verdict.REFUTED, CandidateCheck.judge(q, true, index.toString()).verdict)
            }
        }
    }

    @Test fun anActualLabelledOptionCanStillConfirmItsLayout() {
        val q = Question("ما الجواب الصحيح؟", listOf("المحرم", "شوال"),
            listOf(PxRect(100, 100, 300, 150), PxRect(100, 170, 300, 220)), false, 0.95f)
        val checked = CandidateCheck.judge(q, true, "2) شوال")
        assertEquals(CandidateCheck.Verdict.CONFIRMED, checked.verdict)
        assertEquals(q.optionRects[1], checked.resolved!!.rect)
    }
}
