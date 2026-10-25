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

    @Test
    fun prompt_tellsTheModelToReplyWithExactlyTheNoQuestionText() {
        assertTrue(ScreenImage.PROMPT.endsWith("exactly: " + ScreenImage.NO_QUESTION))
    }

    @Test
    fun prompt_isForAnyScreen_notOnlyTheGames() {
        val p = ScreenImage.PROMPT
        for (screen in listOf("app", "website", "document", "worksheet", "portrait", "any other language")) {
            assertTrue(screen, p.contains(screen))
        }
        assertFalse(p.contains("parchment"))
    }

    @Test
    fun prompt_keepsTheAnswerRulesTheGamesNeed() {
        val p = ScreenImage.PROMPT
        // A choice is matched against the options read on the device, a number goes to the keypad.
        assertTrue(p.contains("exact printed words of the correct option"))
        assertTrue(p.contains("ONLY the number in ASCII digits 0-9"))
        assertTrue(p.contains("each answer on its own line"))
        assertTrue(p.contains("Ignore our floating bubble"))
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
