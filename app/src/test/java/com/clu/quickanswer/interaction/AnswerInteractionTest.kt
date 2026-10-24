package com.clu.quickanswer.interaction

import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.*
import org.junit.Test

class AnswerInteractionTest {
    private val controls = AnswerInteraction.Controls(1200, 1600)
    private fun question(n: Int, english: Boolean = false): Question = Question(
        "Which answer is correct?", (1..n).map { if (english) "Answer $it" else "الإجابة $it" },
        (0 until n).map { PxRect(200, 300 + it * 140, 1000, 390 + it * 140) }, false, 0.95f,
        bannerRect = PxRect(150, 50, 1050, 220))

    @Test fun choicesUseReaderGeometryAtEverySupportedCountInEitherLanguage() {
        for (n in 2..8) for (english in listOf(false, true)) {
            val q = question(n, english)
            val answer = q.options.last()
            val plan = AnswerInteraction.plan(AnswerInteraction.Request(q, answer, answer), controls)!!
            assertEquals(AnswerInteraction.Kind.CHOICE, plan.kind)
            assertEquals(listOf(q.optionRects.last()), plan.taps)
        }
    }

    @Test fun shuffledAnswersFollowTheirCurrentTextInsteadOfAStoredPosition() {
        val q = question(6)
        val shuffled = q.copy(options = q.options.reversed())
        val answer = q.options[0]
        val plan = AnswerInteraction.plan(AnswerInteraction.Request(shuffled, answer, answer), controls)!!
        assertEquals(listOf(q.optionRects.last()), plan.taps)
    }

    @Test fun anExplicitlyRequestedIndexAndAnExactLabelledReplyAreSupported() {
        val q = question(3)
        val expected = listOf(q.optionRects[1])
        assertEquals(expected, AnswerInteraction.plan(AnswerInteraction.Request(q, q.options[1], "2", true), controls)!!.taps)
        for (reply in listOf("2) ${q.options[1]}", "2. ${q.options[1]}", "ب) ${q.options[1]}")) {
            assertEquals(expected, AnswerInteraction.plan(AnswerInteraction.Request(q, q.options[1], reply), controls)!!.taps)
        }
        assertNull(AnswerInteraction.plan(AnswerInteraction.Request(q, q.options[1], "2"), controls))
        assertNull(AnswerInteraction.plan(AnswerInteraction.Request(q, q.options[1], "1) ${q.options[1]}"), controls))
    }

    @Test fun fuzzyNamesDecimalsAndNumericIndexAmbiguityCannotAuthorizeAChoice() {
        val names = listOf("محمد ولي", "عمر", "خالد")
        assertFalse(AnswerInteraction.matchesChoice("محمد علي", names[0], names, false))
        assertFalse(AnswerInteraction.matchesChoice("1.5", "5", listOf("5", "7"), false))
        assertFalse(AnswerInteraction.matchesChoice("2", "4", listOf("2", "4"), true))
        assertFalse(AnswerInteraction.matchesChoice("2", "2", listOf("2", "4"), false))
        assertTrue(AnswerInteraction.matchesChoice("1", "1", listOf("2", "1"), false, literalAnswer = true))
        assertTrue(AnswerInteraction.matchesChoice("لا", "لا", listOf("نعم", "لا"), false))
    }

    @Test fun missingDuplicateOverlappingAndOffscreenOptionsAreDeclined() {
        val q = question(3)
        for (bad in listOf(
            q.copy(options = q.options.dropLast(1)),
            q.copy(options = listOf("same", "same", "other")),
            q.copy(optionRects = listOf(q.optionRects[0], q.optionRects[0], q.optionRects[2])),
            q.copy(optionRects = q.optionRects.dropLast(1) + PxRect(-1, 500, 500, 600)),
            q.copy(meanConfidence = Float.NaN),
        )) assertNull(AnswerInteraction.plan(AnswerInteraction.Request(bad, bad.options[0], bad.options[0]), controls))
    }

    @Test fun wordTargetMustBelongToTheChosenButton() {
        val q = question(2)
        val word = PxRect(400, 325, 650, 360)
        val read = q.copy(optionTextRects = listOf(word, q.optionRects[1]))
        assertEquals(listOf(word), AnswerInteraction.plan(AnswerInteraction.Request(read, q.options[0], q.options[0]), controls)!!.taps)
        val clipped = read.copy(optionTextRects = listOf(PxRect(0, 325, 650, 360), q.optionRects[1]))
        assertEquals(listOf(q.optionRects[0]), AnswerInteraction.plan(AnswerInteraction.Request(clipped, q.options[0], q.options[0]), controls)!!.taps)
    }

    @Test fun numericAdapterResetsAndPreservesEveryLeadingZeroAndRepeatedDigit() {
        val q = Question("كم يساوي؟", emptyList(), emptyList(), true, 0.95f)
        val digits = ('0'..'9').associateWith { d -> PxRect(100 + (d - '0') * 50, 400, 130 + (d - '0') * 50, 430) }
        val clear = PxRect(100, 500, 130, 530)
        val submit = PxRect(700, 500, 800, 560)
        val keys = controls.copy(digits = digits, clear = clear, submit = submit)
        for (number in listOf("0", "0011", "1989", "9999")) {
            val plan = AnswerInteraction.plan(AnswerInteraction.Request(q, number, number), keys)!!
            assertEquals(AnswerInteraction.Kind.NUMBER, plan.kind)
            assertEquals(number, plan.number)
            assertEquals(listOf(clear) + number.map { digits.getValue(it) } + submit, plan.taps)
        }
        for (unsupported in listOf("-3", "3.75", "10000", "word")) {
            assertNull(AnswerInteraction.plan(AnswerInteraction.Request(q, unsupported, unsupported), keys))
        }
        assertNull(AnswerInteraction.plan(AnswerInteraction.Request(q, "1989", "1989"), keys.copy(clear = null)))
    }

    @Test fun unsupportedPatternsAreDeclinedAndANewPlannerDoesNotRequireProviderChanges() {
        val q = question(0)
        val request = AnswerInteraction.Request(q, "word", "word")
        assertNull(AnswerInteraction.plan(request, controls))
        val target = PxRect(100, 100, 150, 150)
        val futurePattern = AnswerInteraction.Pattern { _, _ -> AnswerInteraction.Plan(AnswerInteraction.Kind.CHOICE, listOf(target)) }
        assertEquals(listOf(target), AnswerInteraction.plan(request, controls, listOf(futurePattern))!!.taps)
    }
}
