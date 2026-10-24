package com.clu.quickanswer.interaction

import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.*
import org.junit.Test

class DirectNumberEntryTest {
    private val digits = ('0'..'9').associateWith { d -> PxRect(100 + (d - '0') * 50, 400, 130 + (d - '0') * 50, 430) }
    private val clear = PxRect(100, 500, 130, 530)
    private val submit = PxRect(700, 500, 800, 560)
    private val q = Question("كم يساوي؟", emptyList(), emptyList(), true, 0.95f)

    @Test fun aVerifiedEmptyFieldGetsOnlyDigitsAndConfirmationWithNeitherResetControl() {
        for (backspace in listOf(false, true)) for (number in listOf("4", "71", "1355", "0011")) {
            val controls = AnswerInteraction.Controls(1200, 800, digits,
                clear = clear.takeUnless { backspace }, backspace = clear.takeIf { backspace },
                submit = submit, fieldIsEmpty = true)
            val plan = AnswerInteraction.plan(AnswerInteraction.Request(q, number, number), controls)!!
            assertEquals(number.map { digits.getValue(it) } + submit, plan.taps)
            assertFalse(plan.taps.contains(clear))
        }
    }

    @Test fun directEntryDoesNotNeedAResetKeyButStillNeedsEveryVerifiedDigit() {
        val controls = AnswerInteraction.Controls(1200, 800, digits, submit = submit, fieldIsEmpty = true)
        assertEquals(listOf(digits.getValue('4'), submit),
            AnswerInteraction.plan(AnswerInteraction.Request(q, "4", "4"), controls)!!.taps)
        assertNull(AnswerInteraction.plan(AnswerInteraction.Request(q, "4", "4"), controls.copy(fieldIsEmpty = false)))
        assertNull(AnswerInteraction.plan(AnswerInteraction.Request(q, "4", "4"), controls.copy(digits = digits - '0')))
    }
}
