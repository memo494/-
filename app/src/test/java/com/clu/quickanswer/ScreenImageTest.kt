package com.clu.quickanswer

import org.junit.Assert.assertEquals
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

    private val prompts = listOf(ScreenImage.GAME_PROMPT, ScreenImage.GENERAL_PROMPT)

    @Test
    fun prompts_tellTheModelToReplyWithExactlyTheNoQuestionText() {
        for (p in prompts) assertTrue(p.endsWith("exactly: " + ScreenImage.NO_QUESTION))
    }

    @Test
    fun prompts_keepTheAnswerRulesTheGamesNeed() {
        // A choice is matched against the options read on the device, a number goes to the keypad.
        for (p in prompts) {
            assertTrue(p.contains("ASCII digits 0-9"))
            assertTrue(p.contains("exact printed"))
            assertTrue(p.contains("bubble"))
            assertTrue(p.contains("'" + ScreenImage.NO_QUESTION + "'"))
            assertFalse(p.contains("numbered as on the screen"))
        }
    }

    @Test
    fun gamePrompt_keepsTheCentralQuestionRule() {
        assertTrue(ScreenImage.GAME_PROMPT.contains("side panels"))
        assertTrue(ScreenImage.GAME_PROMPT.contains("never numbered"))
    }

    @Test
    fun generalPrompt_definesAQuestion_andNeverNumbersOneAnswer() {
        assertTrue(ScreenImage.GENERAL_PROMPT.contains("never numbered"))
        assertTrue(ScreenImage.GENERAL_PROMPT.contains("not questions"))
    }

    @Test
    fun gamePrompt_isShorterThanTheGeneralOne() {
        assertTrue(ScreenImage.GAME_PROMPT.length < ScreenImage.GENERAL_PROMPT.length)
    }

    @Test
    fun scaledSize_fitsTheLongSideAndKeepsTheShape() {
        assertEquals(512 to 256, ScreenImage.scaledSize(1024, 512, 512))
        assertEquals(256 to 512, ScreenImage.scaledSize(512, 1024, 512))
        assertEquals(512 to 256, ScreenImage.scaledSize(2048, 1024, ScreenImage.PICTURE_MAX_SIDE))
        // The owner's picture area on the 2712 x 1220 captures: 590 x 470.
        val (w, h) = ScreenImage.scaledSize(590, 470, 512)
        assertEquals(512, w)
        assertEquals(407, h)
    }

    @Test
    fun scaledSize_neverEnlarges_andNeverReachesZero() {
        assertEquals(400 to 300, ScreenImage.scaledSize(400, 300, 512))
        assertEquals(512 to 512, ScreenImage.scaledSize(512, 512, 512))
        assertEquals(512 to 1, ScreenImage.scaledSize(4000, 1, 512))
    }

    @Test
    fun pictureConstants() {
        // About 512 px is plenty for a photo of a landmark or a character, and keeps the request small.
        assertEquals(512, ScreenImage.PICTURE_MAX_SIDE)
        assertEquals(80, ScreenImage.PICTURE_QUALITY)
    }
}
