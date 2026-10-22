package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
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
        c.ink(500, 594, 508, 598) // dot 2 px above the line
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
    fun linesStayInsideTheInsetBox() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(box.left + 10, 600, box.right - 10, 630) // runs into the inset margin
        val line = TextLines.find(c.px, w, h, box).single()
        assertTrue(line.left >= box.left + (box.width * 0.04).toInt())
        assertTrue(line.right <= box.right - (box.width * 0.04).toInt())
        assertTrue(line.top >= box.top + (box.height * 0.06).toInt())
    }

    @Test
    fun emptyOrOutOfImageBoxHasNoLines() {
        val c = Canvas(w, h, Canvas.WHITE)
        c.ink(300, 600, 780, 630)
        assertTrue(TextLines.find(c.px, w, h, PxRect(10, 10, 10, 50)).isEmpty())
        assertTrue(TextLines.find(c.px, w, h, PxRect(2000, 3000, 2100, 3100)).isEmpty())
    }
}
