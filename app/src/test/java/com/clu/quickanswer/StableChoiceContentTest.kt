package com.clu.quickanswer

import com.clu.quickanswer.ocr.ChoiceTouchGuard
import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class StableChoiceContentTest {
    private val w = 1280; private val h = 576
    private val buttons = listOf(PxRect(680, 310, 900, 370), PxRect(445, 310, 665, 370),
        PxRect(680, 380, 900, 440), PxRect(445, 380, 665, 440))
    private fun paint(p: IntArray, area: PxRect, color: Int) {
        for (y in area.top until area.bottom) for (x in area.left until area.right) p[y * w + x] = color
    }
    private fun screen(): IntArray = IntArray(w * h) { 0xffe8d6b4.toInt() }.also { p ->
        for (top in listOf(226, 257)) for (y in top until top + 18) for (x in 440 until 942)
            if (x % 12 < 3 || (y - top) % 16 >= 13) p[y * w + x] = 0xff195a78.toInt()
        // The category's blue animated icon is above the caption and far narrower than a question.
        paint(p, PxRect(850, 178, 875, 198), 0xff195a78.toInt())
    }
    private fun stamp(p: IntArray, r: PxRect): IntArray = IntArray(TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT) { i ->
        val x = r.left + (i % TouchFrameGuard.WIDTH) * r.width / TouchFrameGuard.WIDTH
        val y = r.top + (i / TouchFrameGuard.WIDTH) * r.height / TouchFrameGuard.HEIGHT
        val c = p[y * w + x]
        ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
    }

    @Test fun categoryTurnBadgeTimerAndAvatarsDoNotBecomeQuestionEvidence() {
        val before = screen()
        val content = ChoiceTouchGuard.stableContent(before, w, h, buttons)!!
        assertEquals(PxRect(438, 224, 944, 277), content.question)
        assertNull(content.picture)
        val after = before.copyOf()
        for (r in listOf(PxRect(630, 130, 710, 191), PxRect(770, 171, 911, 204),
                PxRect(350, 470, 970, 500), PxRect(55, 50, 180, 220))) paint(after, r, 0xff101010.toInt())
        assertEquals(content, ChoiceTouchGuard.stableContent(after, w, h, buttons))
        assertTrue(TouchFrameGuard.matches(stamp(before, content.question), stamp(after, content.question)))
        // The last word of the second line is still protected.
        paint(after, PxRect(900, 257, 940, 275), 0xffe8d6b4.toInt())
        assertFalse(TouchFrameGuard.matches(stamp(before, content.question), stamp(after, content.question)))
    }

    @Test fun pictureEvidenceIsSeparateAndStillDetectsAChangedPhoto() {
        val p = screen()
        for (y in 210 until 430) for (x in 120 until 400) p[y * w + x] =
            if ((x + y) % 14 < 7) 0xff202020.toInt() else 0xffdddddd.toInt()
        val content = ChoiceTouchGuard.stableContent(p, w, h, buttons)!!
        val photo = content.picture!!
        val changed = p.copyOf().also { paint(it, PxRect(240, 250, 350, 350), 0xff777777.toInt()) }
        assertTrue(TouchFrameGuard.matches(stamp(p, content.question), stamp(changed, content.question)))
        assertFalse(TouchFrameGuard.matches(stamp(p, photo), stamp(changed, photo)))
    }

    @Test fun missingCaptionNeverFallsBackToAWholeHudRectangle() {
        val blank = IntArray(w * h) { 0xffe8d6b4.toInt() }
        paint(blank, PxRect(850, 178, 875, 198), 0xff195a78.toInt())
        assertNull(ChoiceTouchGuard.stableContent(blank, w, h, buttons))
    }

    @Test fun bubbleBoundsTouchingOnlyThePhotoMountDoNotObscureItsContent() {
        val photo = PxRect(121, 210, 401, 430)
        assertFalse(TouchFrameMask.obscuresPicture(photo, listOf(PxRect(38, 178, 124, 266))))
        assertTrue(TouchFrameMask.obscuresPicture(photo, listOf(PxRect(140, 220, 230, 310))))
        assertTrue(TouchFrameMask.obscuresPicture(photo, listOf(PxRect(119, 210, 140, 260))))
    }
}
