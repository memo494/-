package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [Reply.isAnswer] decides whether showing a result earns a haptic tick. */
class ReplyTest {

    @Test
    fun isAnswer_aRealAnswerFromTheModel() {
        assertTrue(Reply.answer("ب").isAnswer)
        assertTrue(Reply.answer("سورة طه").isAnswer)
        assertTrue(Reply.answer("42").isAnswer)
        assertTrue(Reply.answer("1. أ\n2. ج").isAnswer)
    }

    @Test
    fun isAnswer_noQuestion_isNotAnAnswer() {
        assertFalse(Reply.answer(ScreenImage.NO_QUESTION).isAnswer)
        assertFalse(Reply.answer("  لا يوجد سؤال.\n").isAnswer)
    }

    @Test
    fun isAnswer_anEmptyReplyIsNotAnAnswer() {
        assertFalse(Reply.answer("").isAnswer)
        assertFalse(Reply.answer("   ").isAnswer)
    }

    @Test
    fun isAnswer_errorsAreNotAnswers_whateverTheirText() {
        assertFalse(Reply.error("تم تجاوز الحد المجاني، انتظر قليلاً").isAnswer)
        assertFalse(Reply.error(ScreenImage.BLACK_SCREEN).isAnswer)
        assertFalse(Reply.empty("لم يتم الحصول على إجابة").isAnswer)
        // Even a text that would pass for an answer: the kind decides, not the words.
        assertFalse(Reply.error("42").isAnswer)
        assertFalse(Reply.empty("ب").isAnswer)
    }

    @Test
    fun factories_setTheKind() {
        assertEquals(Reply.Kind.ANSWER, Reply.answer("ب").kind)
        assertEquals(Reply.Kind.ERROR, Reply.error("x").kind)
        assertEquals(Reply.Kind.EMPTY, Reply.empty("x").kind)
    }
}
