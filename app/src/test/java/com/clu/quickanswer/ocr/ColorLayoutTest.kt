package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout and line cutting on synthetic screens, so no model or photo is needed. */
class ColorLayoutTest {

    private val black = Canvas.BLACK
    private val cream = Canvas.CREAM
    private val white = Canvas.WHITE

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected +-$tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun findsBannerButtonsAndTightLines_onAPortraitScreen() {
        val c = SyntheticScreens.quiz(600, 1300)
        val layout = ColorLayout().analyze(c.px, c.w, c.h)
        assertNotNull(layout)
        layout!!
        assertFalse(layout.numeric)

        val b = layout.banner
        assertNear(60, b.left, 4); assertNear(520, b.top, 4); assertNear(540, b.right, 4); assertNear(676, b.bottom, 4)

        assertEquals(4, layout.buttons.size)
        for ((i, btn) in layout.buttons.withIndex()) {
            val expectedTop = 1300 * (56 + i * 8) / 100
            assertNear(150, btn.left, 6)
            assertNear(expectedTop, btn.top, 6)
            assertNear(450, btn.right, 6)
            assertNear(expectedTop + 78, btn.bottom, 6)
        }
        // top-to-bottom, no overlap
        for ((a, n) in layout.buttons.zipWithNext()) assertTrue(a.bottom <= n.top)

        assertEquals(2, layout.questionLines.size)
        assertEquals(4, layout.optionLines.size)
        for (lines in layout.optionLines) assertEquals(1, lines.size)

        // Lines are tight vertically: the ink bar plus a few pixels, never the 25 % padding.
        val q1 = layout.questionLines[0]
        val inkTop = 1300 * 4350 / 10000
        val inkBottom = 1300 * 4600 / 10000
        assertTrue("top ${q1.top} vs $inkTop", q1.top in (inkTop - 4)..inkTop)
        assertTrue("bottom ${q1.bottom} vs $inkBottom", q1.bottom in inkBottom..(inkBottom + 4))
        val o1 = layout.optionLines[0][0]
        assertTrue(o1.top >= layout.buttons[0].top && o1.bottom <= layout.buttons[0].bottom)
        assertTrue(layout.questionLines[0].top < layout.questionLines[1].top)
    }

    @Test
    fun worksOnALandscapeScreen_andAtOtherSizes() {
        // Same proportions at three sizes; the scale-dependent constants must follow the image.
        for (scale in listOf(1.0, 0.6, 1.5)) {
            val w = (1300 * scale).toInt()
            val h = (600 * scale).toInt()
            val c = Canvas(w, h, black)
            c.fill(w * 20 / 100, h * 3 / 100, w * 80 / 100, h * 30 / 100, cream)
            c.ink(w * 35 / 100, h * 10 / 100, w * 65 / 100, h * 18 / 100)
            for (i in 0 until 4) {
                val top = h * (35 + i * 16) / 100
                c.fill(w * 35 / 100, top, w * 65 / 100, top + h * 13 / 100, white)
                c.ink(w * 43 / 100, top + h * 4 / 100, w * 57 / 100, top + h * 9 / 100)
            }
            val layout = ColorLayout().analyze(c.px, w, h)
            assertNotNull("scale $scale", layout)
            layout!!
            assertEquals("scale $scale", 4, layout.buttons.size)
            assertEquals("scale $scale", 1, layout.questionLines.size)
            for (lines in layout.optionLines) assertEquals("scale $scale", 1, lines.size)
        }
    }

    @Test
    fun aWhiteBoxOverlappingTheBannerBottom_meansANumericQuestion() {
        val c = SyntheticScreens.numeric(600, 1300)
        val layout = ColorLayout().analyze(c.px, c.w, c.h)!!
        assertTrue(layout.numeric)
        assertNotNull(layout.numericField)
        assertTrue(layout.buttons.isEmpty())
        assertTrue(layout.optionLines.isEmpty())
        // The question lines stop above the box.
        assertTrue(layout.questionLines.isNotEmpty())
        for (l in layout.questionLines) assertTrue(l.bottom <= layout.numericField!!.top)
    }

    @Test
    fun returnsNull_whenThereIsNoCreamBanner() {
        val c = Canvas(400, 800, 0xFF336699.toInt())
        assertNull(ColorLayout().analyze(c.px, c.w, c.h))
        val white = Canvas(400, 800, white)
        assertNull(ColorLayout().analyze(white.px, white.w, white.h))
    }

    @Test
    fun bannerWithoutText_hasNoLines() {
        val c = Canvas(600, 1300, black)
        c.fill(60, 520, 540, 676, cream)
        val layout = ColorLayout().analyze(c.px, c.w, c.h)!!
        assertTrue(layout.questionLines.isEmpty())
        assertTrue(layout.buttons.isEmpty())
    }

    @Test
    fun otsuSeparatesTwoGreyLevels() {
        val values = IntArray(1000) { if (it % 2 == 0) 40 else 220 }
        val t = ImageOps.otsu(values)
        assertTrue("threshold $t", t in 40 until 220)
    }

    @Test
    fun connectedBlobs_areEightConnectedAndInRasterOrder() {
        // Two diagonal pixels form one 8-connected blob; the far pixel is another.
        val w = 6
        val h = 4
        val m = BooleanArray(w * h)
        m[0 * w + 0] = true
        m[1 * w + 1] = true
        m[3 * w + 5] = true
        val blobs = ImageOps.blobs(m, w, h)
        assertEquals(2, blobs.size)
        assertEquals(2, blobs[0].area)
        assertEquals(2, blobs[0].width)
        assertEquals(2, blobs[0].height)
        assertEquals(1, blobs[1].area)
        assertEquals(5, blobs[1].left)
    }

    @Test
    fun close_bridgesGapsOfTwoPixelsButNotFour() {
        val w = 40
        val h = 13
        val m = BooleanArray(w * h)
        fun block(x0: Int, x1: Int) { for (y in 3..9) for (x in x0..x1) m[y * w + x] = true }
        block(3, 8)
        block(11, 16) // 2 px gap (columns 9, 10)
        block(21, 26)
        block(31, 36) // 4 px gap (columns 27..30) to the block before
        m[6 * w + 5] = false // a hole inside the first block
        val closed = ImageOps.close(m, w, h, 3)
        assertTrue("hole filled", closed[6 * w + 5])
        assertTrue("2 px gap bridged", closed[6 * w + 9] && closed[6 * w + 10])
        assertFalse("4 px gap stays open", closed[6 * w + 28] || closed[6 * w + 29])
        assertFalse("outside the blobs stays empty", closed[0 * w + 20] || closed[12 * w + 20])
    }
}
