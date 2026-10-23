package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TextLinesTest {
    private val w = 1080
    private val h = 2400
    private val box = PxRect(100, 500, 980, 800)

    private fun rgb(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Light "text": ink whose level varies a little from column to column, like anti-aliased glyphs. */
    private fun light(c: Canvas, l: Int, t: Int, r: Int, b: Int) {
        for (y in t until b) for (x in l until r) c.px[y * c.w + x] = rgb(220 + x % 16)
    }

    /** Text-like ink: vertical strokes 3 px wide every 6 px, as tall as the line (a filled block is a fill, not text). */
    private fun strokes(c: Canvas, l: Int, t: Int, r: Int, b: Int) {
        var x = l
        while (x < r) { c.ink(x, t, minOf(x + 3, r), b); x += 6 }
    }

    private fun assertNear(expected: PxRect, actual: PxRect, tol: Int = 4) {
        assertTrue("expected $expected, got $actual", abs(expected.left - actual.left) <= tol &&
            abs(expected.top - actual.top) <= tol && abs(expected.right - actual.right) <= tol &&
            abs(expected.bottom - actual.bottom) <= tol)
    }

    // Ink 30 px high on a 1080x2400 capture: padding is 0.25 * 30 + 2 = 9 px sideways, 2 px up and down.
    private fun padded(l: Int, t: Int, r: Int, b: Int) = PxRect(l - 9, t - 2, r + 9, b + 2)

    @Test
    fun twoDarkLinesOnLight() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        c.ink(300, 680, 700, 710)
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(2, lines.size)
        assertNear(padded(300, 600, 780, 630), lines[0])
        assertNear(padded(300, 680, 700, 710), lines[1])
    }

    @Test
    fun twoLightLinesOnDark() {
        val c = Canvas(w, h, rgb(30))
        light(c, 300, 600, 780, 630)
        light(c, 300, 680, 700, 710)
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(2, lines.size)
        assertNear(padded(300, 600, 780, 630), lines[0])
        assertNear(padded(300, 680, 700, 710), lines[1])
    }

    @Test
    fun lightLineOnMidGreyBox() {
        // A lighter box on a dark screen: the background is the box, not the screen around it.
        val c = Canvas(w, h, rgb(10))
        c.fill(box.left, box.top, box.right, box.bottom, rgb(80))
        light(c, 300, 600, 780, 630)
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(1, lines.size)
        assertNear(padded(300, 600, 780, 630), lines[0])
    }

    @Test
    fun flatRegionHasNoLines() {
        assertTrue(TextLines.find(Canvas(w, h, rgb(30)).px, w, h, box).isEmpty())
        assertTrue(TextLines.find(Canvas(w, h, Canvas.WHITE).px, w, h, box).isEmpty())
    }

    @Test
    fun gentleGradientIsNotText() {
        val c = Canvas(w, h, rgb(0))
        for (y in 0 until h) for (x in 0 until w) c.px[y * w + x] = rgb(100 + (x * 20 / w))
        assertTrue(TextLines.find(c.px, w, h, box).isEmpty())
    }

    @Test
    fun smallDotJustAboveALineMergesIntoIt() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        for (k in 0 until 3) c.ink(400 + k * 90, 594, 408 + k * 90, 598) // three dots 2 px above the line: sparser than a line row, still part of it
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(1, lines.size)
        assertTrue("top ${lines[0].top}", lines[0].top <= 594 - 2 + 1)
    }

    @Test
    fun farAwayMarkIsNotMerged() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        c.ink(500, 580, 508, 584) // 16 px above: a stray mark, shorter than 0.35 of the line
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(1, lines.size)
        assertNear(padded(300, 600, 780, 630), lines[0])
    }

    @Test
    fun boxBorderAndOutsideInkAreIgnored() {
        val c = Canvas(w, h, Canvas.WHITE)
        // A frame along the box edge and text just outside it.
        c.ink(box.left, box.top, box.right, box.top + 4)
        c.ink(box.left, box.bottom - 4, box.right, box.bottom)
        c.ink(box.left, box.top, box.left + 4, box.bottom)
        c.ink(box.right - 4, box.top, box.right, box.bottom)
        c.ink(300, 400, 780, 430)
        c.ink(300, 650, 780, 680)
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(1, lines.size)
        assertNear(padded(300, 650, 780, 680), lines[0])
    }

    @Test
    fun aLineSlightlyWiderThanTheBoxIsFollowedAndNotClipped() {
        val c = Canvas(w, h, Canvas.WHITE)
        strokes(c, box.left - 20, 600, box.right + 20, 630) // ends inside the 5 % search margin (44 px)
        val r = TextLines.findDetailed(c.px, w, h, box)
        val line = r.lines.single()
        assertFalse(r.clipped)
        assertNear(padded(box.left - 20, 600, box.right + 20 - 3, 630), line)
    }

    @Test
    fun aLineRunningPastTheBoxEdgeIsClipped() {
        for ((l, r) in listOf((box.left + 100) to (w - 5), 5 to (box.right - 100), 5 to (w - 5))) {
            val c = Canvas(w, h, Canvas.WHITE)
            // Words 20 px apart, continuing out of the search margin on one or both sides.
            var x = l
            while (x < r) { strokes(c, x, 600, minOf(x + 60, r), 630); x += 80 }
            val res = TextLines.findDetailed(c.px, w, h, box)
            assertEquals("$l..$r", 1, res.lines.size)
            assertTrue("$l..$r should be clipped", res.clipped)
            // find() gives the same lines.
            assertEquals(res.lines, TextLines.find(c.px, w, h, box))
        }
    }

    @Test
    fun aBoundedBoxReadsOnlyInsideItself_aNeighbourIsNotAContinuation() {
        // A line that ends a few px before the box edge, and a block of the game right after it (a
        // keypad button, a note): unbounded that looks like the line going on, bounded it is ignored.
        val c = Canvas(w, h, Canvas.WHITE)
        strokes(c, 300, 600, box.right - 10, 630)
        strokes(c, box.right + 8, 560, box.right + 90, 700)
        assertTrue(TextLines.findDetailed(c.px, w, h, box).clipped)
        val r = TextLines.findDetailed(c.px, w, h, box, bounded = true)
        assertFalse(r.clipped)
        val line = r.lines.single()
        assertTrue("stays inside the box: $line", line.left >= box.left && line.right <= box.right)
    }

    @Test
    fun aBoundedBoxStillSeesTheScreenEdge() {
        val c = Canvas(w, h, Canvas.WHITE)
        val edgeBox = PxRect(0, 500, 600, 800)
        strokes(c, 0, 600, 400, 630)
        assertTrue(TextLines.findDetailed(c.px, w, h, edgeBox, bounded = true).clipped)
    }

    @Test
    fun textInsideTheBoxIsNotClipped() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        c.ink(300, 680, 700, 710)
        assertFalse(TextLines.findDetailed(c.px, w, h, box).clipped)
    }

    @Test
    fun aSeparateMarkInTheSearchMarginIsNotPartOfTheLine() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        c.ink(990, 600, 1010, 630) // 210 px (> 1.2 line heights) after the text, beyond the box, inside the margin
        val r = TextLines.findDetailed(c.px, w, h, box)
        assertFalse(r.clipped)
        assertNear(padded(300, 600, 780, 630), r.lines.single())
    }

    @Test
    fun aPictureEdgeBesideTheBoxDoesNotMergeIntoTheLines() {
        // A dark column (the shadow or edge of a picture) runs along the outside of the box, inside
        // the search margin (56..100), close to the text or a little further.
        for (textLeft in listOf(110, 160)) {
            val c = Canvas(w, h, Canvas.WHITE)
            c.ink(60, 300, 94, 1000)
            strokes(c, textLeft, 600, 780, 630)
            val r = TextLines.findDetailed(c.px, w, h, box)
            assertEquals("text at $textLeft", 1, r.lines.size)
            assertFalse("text at $textLeft", r.clipped)
            assertNear(padded(textLeft, 600, 780 - 3, 630), r.lines[0])
        }
    }

    @Test
    fun aSpeckDoesNotBridgeTheGapToInkBeyondTheSearchRange() {
        val c = Canvas(w, h, Canvas.WHITE)
        strokes(c, 600, 600, 960, 630)
        c.ink(975, 600, 977, 630) // a 2 px speck 15 px after the text (closer than 0.8 line heights)
        strokes(c, 995, 600, 1070, 630) // more ink 18 px after the speck, past the search range (to 1024)
        val r = TextLines.findDetailed(c.px, w, h, box)
        assertFalse(r.clipped)
        assertNear(padded(600, 600, 960 - 3, 630), r.lines.single())
        // The same ink without the speck is a word gap: the line goes on, and is clipped.
        val d = Canvas(w, h, Canvas.WHITE)
        strokes(d, 600, 600, 960, 630)
        strokes(d, 975, 600, 1070, 630)
        assertTrue(TextLines.findDetailed(d.px, w, h, box).clipped)
    }

    @Test
    fun aBarInsideTheBoxBesideTheTextIsNotPartOfTheLine() {
        val c = Canvas(w, h, Canvas.WHITE)
        strokes(c, 300, 600, 780, 630)
        c.ink(800, box.top, 812, box.bottom) // a frame element running the whole height of the box
        val r = TextLines.findDetailed(c.px, w, h, box)
        assertFalse(r.clipped)
        assertNear(padded(300, 600, 780 - 3, 630), r.lines.single())
    }

    @Test
    fun twoLinesJoinedByDescendersAreSplit() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630) // line 1
        c.ink(320, 641, 760, 671) // line 2, 11 px below
        // Descenders of line 1 and ascenders of line 2 fill the gap: ~15 % of a line's ink per row.
        var x = 310
        while (x < 770) { c.ink(x, 630, x + 3, 641); x += 20 }
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(2, lines.size)
        assertTrue("first ${lines[0]}", lines[0].top <= 600 && lines[0].bottom in 630..645)
        assertTrue("second ${lines[1]}", lines[1].top in 628..642 && lines[1].bottom >= 671)
        assertTrue(lines[0].bottom <= lines[1].top + 6)
    }

    @Test
    fun aTallBandNextToNormalOnesIsSplit() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 520, 780, 550) // a normal line
        c.ink(300, 640, 780, 670) // two glued lines below it
        c.ink(320, 681, 760, 711)
        var x = 310
        while (x < 770) { c.ink(x, 670, x + 3, 681); x += 20 }
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(3, lines.size)
        assertTrue(lines[0].bottom < lines[1].top)
    }

    @Test
    fun aDecorationUnderASingleLineIsNotASecondLine() {
        // A swoosh half as tall as the text, 4 px under it: a dense row, but not a line.
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        c.ink(320, 634, 760, 644)
        assertEquals(1, TextLines.find(c.px, w, h, box).size)
    }

    @Test
    fun aSingleLineWithDotsAndDescendersIsNotSplit() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 604, 780, 630) // body
        var x = 310
        while (x < 770) { c.ink(x, 630, x + 3, 638); x += 40 } // descenders
        x = 320
        while (x < 770) { c.ink(x, 597, x + 5, 602); x += 40 } // dots above (2 px over the body)
        val lines = TextLines.find(c.px, w, h, box)
        assertEquals(1, lines.size)
        assertTrue("top ${lines[0].top}", lines[0].top <= 597)
        assertTrue("bottom ${lines[0].bottom}", lines[0].bottom >= 638)
    }

    @Test
    fun aBevelJustInsideTheInsetIsAStrayMarkNotALine() {
        val c = Canvas(w, h, Canvas.WHITE)
        // 12 px inside the 9 px row inset: 4 px thick, full width, top and bottom of the button.
        c.ink(box.left, box.top + 12, box.right, box.top + 16)
        c.ink(box.left, box.bottom - 16, box.right, box.bottom - 12)
        c.ink(500, 620, 640, 650) // short text: far fewer ink pixels per row than the bevel
        val r = TextLines.findDetailed(c.px, w, h, box)
        assertEquals(1, r.lines.size)
        assertFalse(r.clipped)
        assertNear(padded(500, 620, 640, 650), r.lines[0])
    }

    @Test
    fun bevelAtTheButtonBorderIsIgnored() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(box.left, box.top, box.right, box.top + 6)
        c.ink(box.left, box.bottom - 6, box.right, box.bottom)
        c.ink(box.left, box.top, box.left + 6, box.bottom)
        c.ink(box.right - 6, box.top, box.right, box.bottom)
        c.ink(500, 620, 640, 650)
        val r = TextLines.findDetailed(c.px, w, h, box)
        assertFalse(r.clipped)
        assertNear(padded(500, 620, 640, 650), r.lines.single())
    }

    @Test
    fun emptyOrOutOfImageBoxHasNoLines() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        assertTrue(TextLines.find(c.px, w, h, PxRect(10, 10, 10, 50)).isEmpty())
        assertTrue(TextLines.find(c.px, w, h, PxRect(2000, 3000, 2100, 3100)).isEmpty())
    }
}
