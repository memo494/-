package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt that goes with a question's picture: [QuestionPrompt.buildPicture]. */
class QuestionPromptPictureTest {

    private fun choice() = Question(
        "ما هو اسم الشخصية الخيالية الاتية ؟",
        listOf("سوبرمان", "باتمان", "سبايدرمان", "هالك"),
        List(4) { PxRect(0, 0, 1, 1) }, numeric = false, meanConfidence = 0.95f,
    )

    private fun numeric() = Question(
        "كم عدد الأشخاص في الصورة ؟", emptyList(), emptyList(), numeric = true, meanConfidence = 0.95f,
    )

    /** The lines [QuestionPrompt.buildPicture] adds to [QuestionPrompt.build]'s. */
    private fun extraLines(q: Question): List<String> {
        val rest = QuestionPrompt.build(q).lines().toMutableList()
        val extra = ArrayList<String>()
        for (line in QuestionPrompt.buildPicture(q).lines()) if (!rest.remove(line)) extra += line
        assertTrue("every line of build() is kept", rest.isEmpty())
        return extra
    }

    @Test fun aPictureQuestionIsAskedLikeAnyOther_plusOneLineAboutThePicture() {
        for (q in listOf(choice(), numeric())) {
            val extra = extraLines(q)
            assertEquals(extra.toString(), 1, extra.size)
            val line = extra.single()
            assertTrue(line, line.contains("picture", ignoreCase = true))
            assertTrue(line, line.contains("attached", ignoreCase = true))
            assertTrue("says the answer depends on it: $line", line.contains("depends", ignoreCase = true))
        }
    }

    @Test fun theAnswerFormatIsTheSame_optionNumberOnly_orDigitsOnly() {
        val mc = QuestionPrompt.buildPicture(choice())
        assertTrue(mc.contains("ONLY the number of the correct option"))
        assertTrue(mc.contains("No option text, no explanation"))
        val num = QuestionPrompt.buildPicture(numeric())
        assertTrue(num.contains("ONLY digits (0-9)"))
        assertFalse("a numeric question lists no options", num.contains("Options:"))
    }

    @Test fun theNoneEscapeAndTheOcrNoteStay() {
        for (q in listOf(choice(), numeric())) {
            val p = QuestionPrompt.buildPicture(q)
            assertTrue(p.contains("reply exactly: ${QuestionPrompt.NONE}"))
            assertTrue(p.contains("read from a screenshot by OCR"))
        }
    }

    @Test fun theQuestionAndItsOptionsAreInIt_numbered() {
        val p = QuestionPrompt.buildPicture(choice())
        assertTrue(p.contains("Question: ما هو اسم الشخصية الخيالية الاتية ؟"))
        assertTrue(p.contains("1) سوبرمان"))
        assertTrue(p.contains("4) هالك"))
    }

    @Test fun thePictureLineComesBeforeTheQuestion_andPlainPromptsDoNotMentionAPicture() {
        val p = QuestionPrompt.buildPicture(choice())
        assertTrue(p.indexOf("picture", ignoreCase = true) < p.indexOf("Question:"))
        // The text-only prompt stays as it was: no picture is attached to it.
        assertFalse(QuestionPrompt.build(choice()).contains("picture", ignoreCase = true))
    }

    @Test fun theLastLineIsStillTheLastOption_noTrailingBlankLines() {
        assertEquals(QuestionPrompt.buildPicture(choice()).trimEnd(), QuestionPrompt.buildPicture(choice()))
        assertTrue(QuestionPrompt.buildPicture(choice()).endsWith("4) هالك"))
    }
}
