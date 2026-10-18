package com.clu.quickanswer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenImageTest {

    @Test
    fun isNoQuestion_acceptsTheExactReply_andHarmlessVariants() {
        assertTrue(ScreenImage.isNoQuestion("لا يوجد سؤال"))
        assertTrue(ScreenImage.isNoQuestion("  لا يوجد سؤال\n"))
        assertTrue(ScreenImage.isNoQuestion("لا يوجد سؤال."))
        assertTrue(ScreenImage.isNoQuestion(ScreenImage.NO_QUESTION))
    }

    @Test
    fun isNoQuestion_rejectsRealAnswersAndErrors() {
        assertFalse(ScreenImage.isNoQuestion(""))
        assertFalse(ScreenImage.isNoQuestion("ب"))
        assertFalse(ScreenImage.isNoQuestion("1. لا يوجد سؤال\n2. أ"))
        assertFalse(ScreenImage.isNoQuestion("لا يوجد سؤال هنا لكن الإجابة: 7"))
        assertFalse(ScreenImage.isNoQuestion(ScreenImage.BLACK_SCREEN))
        assertFalse(ScreenImage.isNoQuestion("لا يوجد اتصال"))
    }

    @Test
    fun prompt_tellsTheModelToReplyWithExactlyTheNoQuestionText() {
        assertTrue(ScreenImage.PROMPT.endsWith("exactly: " + ScreenImage.NO_QUESTION))
    }
}
