package com.clu.quickanswer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionPromptLanguageTest {
    @Test fun englishPictureQuestionsAreNotReusedFromTextOnlyCache() {
        assertTrue(QuestionPrompt.refersToPicture("Who is the person in this PHOTO?"))
        assertTrue(QuestionPrompt.refersToPicture("What is shown in the IMAGE?"))
        assertTrue(QuestionPrompt.refersToPicture("ما اسم صاحب الصورة؟"))
        assertFalse(QuestionPrompt.refersToPicture("What is the capital of Jordan?"))
    }
}
