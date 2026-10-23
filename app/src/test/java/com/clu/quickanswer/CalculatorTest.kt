package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {

    private fun assertValue(expected: String, text: String) = assertEquals(text, expected, Calculator.evaluate(text))

    private fun assertDeclined(text: String) = assertNull(text, Calculator.evaluate(text))

    @Test
    fun evaluate_answersTheQuestionsThePlanNames() {
        assertValue("48", "كم يساوي 12 × 4 ؟")
        assertValue("42", "١٢ + ٣٠")
        assertValue("-3", "ما ناتج 7 - 10")
        assertValue("3.75", "15 ÷ 4")
        assertValue("3.3333", "10 / 3")
        assertValue("1024", "2 ^ 10")
        assertValue("14", "(3 + 4) × 2")
    }

    @Test
    fun evaluate_followsTheUsualPrecedence() {
        assertValue("11", "3 + 4 × 2")
        assertValue("14", "(3 + 4) × 2")
        assertValue("50", "2 + 3 × 4 ^ 2")
        assertValue("5", "10 - 2 - 3") // left to right: (10 - 2) - 3
        assertValue("2", "100 / 10 / 5")
        assertValue("512", "2 ^ 3 ^ 2") // right to left: 2 ^ (3 ^ 2)
        assertValue("-4", "-2 ^ 2") // the power first, then the sign
        assertValue("0.5", "2 ^ -1")
        assertValue("35", "7 × (2 + 3)")
    }

    @Test
    fun evaluate_readsUnaryMinus() {
        assertValue("3", "-5 + 8")
        assertValue("-6", "2 × -3")
        assertValue("8", "5 - -3")
        assertValue("-3", "(-1) + (-2)")
    }

    @Test
    fun evaluate_readsEveryWayOfWritingAnOperator() {
        for (text in listOf("12 × 4", "12 x 4", "12 X 4", "12 * 4", "12×4", "12x4")) {
            assertValue("48", text)
        }
        assertValue("6", "10 - 4")
        assertValue("6", "10 − 4") // minus sign
        assertValue("6", "10 – 4") // en dash, as OCR often reads a minus
        assertValue("2.5", "10 ÷ 4")
        assertValue("2.5", "10 / 4")
        assertValue("2.5", "10/4")
    }

    @Test
    fun evaluate_readsArabicIndicAndPersianDigitsAndTheArabicDecimalSeparator() {
        assertValue("42", "٦ × ٧")
        assertValue("48", "۱۲ × ۴")
        assertValue("5", "٢٫٥ × 2")
        assertValue("5", "2.5 × 2")
        assertValue("10", "٢٫٥ × ٤")
    }

    @Test
    fun evaluate_stripsTheFramingWords() {
        for (text in listOf(
            "كم يساوي 6 × 7", "كم ناتج 6 × 7", "ما ناتج 6 × 7", "ما هو ناتج 6 × 7", "ما هي نتيجة 6 × 7",
            "ما نتيجة 6 × 7", "ما حاصل 6 × 7", "ما هو حاصل 6 × 7", "احسب 6 × 7", "أوجد 6 × 7", "اوجد 6 × 7",
            "ناتج 6 × 7", "6 × 7 يساوي", "6 × 7 =", "6 × 7 = ؟", "6 × 7 ؟", "6 × 7 ?", "6 × 7 ...", "6 × 7...",
            "6 × 7 = ...", "كم يساوي: 6 × 7؟", "ما ناتج ٦ × ٧ ؟", "كم يساوي ٦ × ٧ =",
        )) {
            assertValue("42", text)
        }
    }

    @Test
    fun evaluate_framingIsMatchedAsWholeWordsAndIgnoresHamzaAndTehMarbuta() {
        assertValue("42", "أحسب 6 × 7")
        assertValue("42", "ما هي نتيجه 6 × 7") // heh for teh marbuta, as OCR and typists write it
        assertValue("42", "ما هو ناتج ٦ × ٧")
        assertValue("42", "‏كم يساوي 6 × 7‏") // right-to-left marks around the text
        assertDeclined("احسبوا 6 × 7") // a longer word that merely starts with a framing word
    }

    @Test
    fun evaluate_readsTheArabicOperatorWords() {
        assertValue("11", "5 زائد 6")
        assertValue("5", "9 ناقص 4")
        assertValue("42", "6 في 7")
        assertValue("42", "6 ضرب 7")
        assertValue("42", "6 مضروب في 7")
        assertValue("42", "6 ضرب في 7")
        assertValue("4", "8 على 2")
        assertValue("4", "8 مقسوم على 2")
        assertValue("3", "9 تقسيم 3")
        assertValue("2.5", "10 تقسيم على 4")
        assertValue("20", "(2 زائد 3) ضرب 4")
        assertValue("84", "6 في 7 في 2")
        assertValue("11", "ما هي نتيجة 5 زائد 6")
        assertValue("4", "ما هو ناتج 8 على 2 ؟")
        assertValue("42", "كم يساوي ٦ في ٧")
    }

    @Test
    fun evaluate_fiWordIsAMultiplicationOnlyBetweenTwoNumbers() {
        assertDeclined("في 6 7")
        assertDeclined("6 في")
        assertDeclined("في 6 × 7")
        assertDeclined("6 × في 7")
        assertDeclined("في أي سنة 1975")
        assertDeclined("1975 في أي سنة")
        assertDeclined("في أي سنة 1975 × 2")
    }

    @Test
    fun evaluate_formatsWholeValuesWithoutADecimalPart() {
        assertValue("10", "2.5 × 4")
        assertValue("1", "0.5 + 0.5")
        assertValue("0", "5 - 5")
        assertValue("0", "0 × -5") // not "-0"
        assertValue("0", "0.1 + 0.2 - 0.3") // binary floating point leaves 5.5e-17 here
        assertValue("1000000000000", "1000000 × 1000000")
    }

    @Test
    fun evaluate_roundsToFourDecimalsAndDropsTrailingZeros() {
        assertValue("3.3333", "10 / 3")
        assertValue("0.6667", "2 ÷ 3")
        assertValue("-0.3333", "-1 ÷ 3")
        assertValue("0.3", "0.1 + 0.2")
        assertValue("2.75", "1.5 + 1.25")
        assertValue("0.125", "1 ÷ 8")
        assertValue("0.005", "5 ÷ 1000")
        assertValue("1.4142", "2 ^ 0.5")
        assertValue("3", "2.99999 × 1") // rounds up to a whole number
        assertValue("100", "100.00004 × 1")
    }

    @Test
    fun evaluate_declinesAValueThatRoundsToZeroButIsNotZero() {
        assertDeclined("1 ÷ 100000")
        assertDeclined("-1 ÷ 100000")
    }

    @Test
    fun evaluate_declinesDivisionByZeroAndNonFiniteResults() {
        assertDeclined("5 ÷ 0")
        assertDeclined("5 / 0")
        assertDeclined("0 / 0")
        assertDeclined("8 على 0")
        assertDeclined("كم يساوي 1 ÷ (3 - 3) ؟")
        assertDeclined("0 ^ -1")
        assertDeclined("(-8) ^ 0.5") // not a real number
        assertDeclined("9 ^ 9 ^ 9")
        assertDeclined("2 ^ 10000")
    }

    @Test
    fun evaluate_declinesValuesThatDoubleCannotHoldExactly() {
        assertValue("1125899906842624", "2 ^ 50")
        assertDeclined("2 ^ 54")
        assertDeclined("99999999999 × 99999999999")
        assertDeclined("99999999999 × 99999999999 ÷ 99999999999") // an intermediate result is already too big
        assertDeclined("123456789 × 987654321 × 1000")
    }

    @Test
    fun evaluate_declinesQuestionsThatAreNotArithmetic() {
        for (text in listOf(
            "كم عدد أيام الأسبوع؟",
            "في أي سنة 1975",
            "في أي سنة تم تأسيس شركة مايكروسوفت؟",
            "كم عدد الحكام الموجودين على ارض الملعب في رياضة كرة السلة؟",
            "ما عاصمة فرنسا؟",
            "من هو مؤسس شركة آبل عام 1976؟",
            "كم يساوي عدد أيام السنة؟",
            "ما هو ناتج التفاعل بين الصوديوم والماء؟",
            "ما هي نتيجة مباراة 3 - 1",
            "كم عدد لاعبي كرة القدم في الملعب 11 × 2",
            "ما ناتج الجمع 5 + 3",
            "5 ناتج + 3", // a framing word inside the expression is not framing
            "What is 5 + 3?",
            "2x + 3",
            "x + 3 = 5",
            "3 + 4 جنيه",
            "12 × 4 سم",
            "احسب",
            "كم يساوي ؟",
        )) {
            assertDeclined(text)
        }
    }

    @Test
    fun evaluate_declinesTextThatIsNotOneExpression() {
        assertDeclined("")
        assertDeclined("   ")
        assertDeclined("7") // one number
        assertDeclined("-7") // one number with a sign
        assertDeclined("(7)")
        assertDeclined("كم يساوي 7 ؟")
        assertDeclined("12 34") // two numbers, no operator
        assertDeclined("2020 2021")
        assertDeclined("5 +")
        assertDeclined("+ 5")
        assertDeclined("× 5 3")
        assertDeclined("5 × × 3")
        assertDeclined("5 + + 3")
        assertDeclined("(3 + 4")
        assertDeclined("3 + 4)")
        assertDeclined("3 + ()")
        assertDeclined("()")
        assertDeclined("3 (4)") // implicit multiplication is not read
        assertDeclined("2.5.3 + 1")
        assertDeclined("3 + 4 = 7") // a statement to judge, not a question to compute
        assertDeclined("1,5 + 2") // a decimal comma or a thousands comma: ambiguous
        assertDeclined("1,000 + 2")
        assertDeclined("5 \$ 3")
        assertDeclined("5 % 3")
        assertDeclined("5 & 3")
        assertDeclined("[5 + 3]")
    }

    @Test
    fun evaluate_declinesTooLongTextAndTooManyNumbers() {
        val exactly80 = "1" + " ".repeat(76) + "+ 1"
        assertEquals(80, exactly80.length)
        assertValue("2", exactly80)
        val long = "1" + " ".repeat(77) + "+ 1"
        assertEquals(81, long.length)
        assertDeclined(long)
        assertDeclined("ما هو ناتج " + "1 + 1" + " ".repeat(70) + "؟")

        assertValue("78", (1..12).joinToString(" + "))
        assertDeclined((1..13).joinToString(" + "))
    }

    // ---- answer ----

    private val rects = List(4) { PxRect(100, 100 + it * 50, 400, 140 + it * 50) }

    private fun mcq(text: String, vararg options: String, optionRects: List<PxRect> = rects) =
        Question(text, options.toList(), optionRects, false, 0.9f)

    private fun numeric(text: String) = Question(text, emptyList(), emptyList(), true, 0.9f)

    @Test
    fun answer_aNumericQuestionGetsTheNumberAndNoRect() {
        val r = Calculator.answer(numeric("كم يساوي 12 × 4 ؟"))!!
        assertEquals("48", r.answer)
        assertNull(r.rect)
        assertEquals("3.75", Calculator.answer(numeric("15 ÷ 4"))?.answer)
        assertEquals("-3", Calculator.answer(numeric("ما ناتج 7 - 10"))?.answer)
    }

    @Test
    fun answer_aMultipleChoiceQuestionGetsTheMatchingOptionAndItsButton() {
        val r = Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "40", "48", "52", "36"))!!
        assertEquals("48", r.answer)
        assertEquals(rects[1], r.rect)
        val last = Calculator.answer(mcq("5 زائد 31", "40", "48", "52", "36"))!!
        assertEquals("36", last.answer)
        assertEquals(rects[3], last.rect)
    }

    @Test
    fun answer_matchesOptionsWrittenWithArabicIndicDigitsAndKeepsTheirText() {
        val r = Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "٤٠", "٤٨", "٥٢", "٣٦"))!!
        assertEquals("٤٨", r.answer)
        assertEquals(rects[1], r.rect)
    }

    @Test
    fun answer_matchesDecimalAndNegativeOptions() {
        val decimal = Calculator.answer(mcq("15 ÷ 4", "3.75", "4", "3.5", "3"))!!
        assertEquals("3.75", decimal.answer)
        assertEquals(rects[0], decimal.rect)
        val arabicDecimal = Calculator.answer(mcq("15 ÷ 4", "٤", "٣٫٧٥", "٣٫٥", "٣"))!!
        assertEquals("٣٫٧٥", arabicDecimal.answer)
        assertEquals(rects[1], arabicDecimal.rect)
        val negative = Calculator.answer(mcq("ما ناتج 7 - 10", "3", "-3", "10", "-10"))!!
        assertEquals("-3", negative.answer)
        assertEquals(rects[1], negative.rect)
        val rounded = Calculator.answer(mcq("10 / 3", "3", "3.3333", "3.5", "4"))!!
        assertEquals("3.3333", rounded.answer)
    }

    @Test
    fun answer_aMultipleChoiceQuestionWithNoMatchingOptionIsLeftToTheModel() {
        assertNull(Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "40", "50", "52", "36")))
        assertNull(Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "أربعون", "ثمانية وأربعون", "اثنان", "ستة")))
        assertNull(Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "48 سم", "40 سم", "52 سم", "36 سم"))) // units: not a number alone
        assertNull(Calculator.answer(mcq("كم يساوي 12 × 4 ؟")))
    }

    @Test
    fun answer_twoOptionsWithTheSameValueAreLeftToTheModel() {
        assertNull(Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "40", "48", "٤٨", "36")))
        assertNull(Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "48", "48.0", "52", "36")))
        assertNull(Calculator.answer(mcq("ما ناتج 7 - 10", "3", "-3", "−3", "10")))
    }

    @Test
    fun answer_noButtonWhenTheRectsAreMissing() {
        val r = Calculator.answer(mcq("كم يساوي 12 × 4 ؟", "40", "48", "52", "36", optionRects = emptyList()))!!
        assertEquals("48", r.answer)
        assertNull(r.rect)
    }

    @Test
    fun answer_aQuestionThatIsNotArithmeticIsLeftToTheModel() {
        assertNull(Calculator.answer(numeric("كم عدد أيام الأسبوع؟")))
        assertNull(Calculator.answer(mcq("في أي سنة تم تأسيس شركة مايكروسوفت؟", "1975", "1985", "1995", "1965")))
        assertNull(Calculator.answer(mcq("كم عدد الحكام الموجودين على ارض الملعب في رياضة كرة السلة؟", "2", "3", "4", "5")))
        assertNull(Calculator.answer(numeric("5 ÷ 0")))
    }
}
