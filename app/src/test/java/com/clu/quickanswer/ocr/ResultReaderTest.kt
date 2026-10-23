package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic result screens only: grey buttons, white text, green fills and outlines. */
class ResultReaderTest {
    private val w = 1080
    private val h = 2400

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private val background = rgb(40, 40, 60)
    private val grey = rgb(90, 90, 90)
    private val lightGrey = rgb(200, 200, 200)
    private val green = 0xFF22C55E.toInt() // what Clu's own box is drawn in (HIGHLIGHT_COLOR)
    private val gameGreen = 0xFF4CAF50.toInt() // a typical Material green a game might use
    private val white = Canvas.WHITE

    /** Four option buttons like the original game's: 40% of the width, 6% of the height, 8% pitch. */
    private val options = List(4) { i ->
        val top = h * (56 + i * 8) / 100
        PxRect(w * 30 / 100, top, w * 70 / 100, top + h * 6 / 100)
    }

    private fun screen() = Canvas(w, h, background)

    /** A button of [fill] with text-like white strokes (3 px wide every 6 px) across its middle. */
    private fun button(c: Canvas, r: PxRect, fill: Int) {
        c.fill(r.left, r.top, r.right, r.bottom, fill)
        var x = r.left + r.width * 20 / 100
        val end = r.left + r.width * 80 / 100
        while (x < end) {
            c.fill(x, r.top + r.height * 40 / 100, minOf(x + 3, end), r.top + r.height * 60 / 100, white)
            x += 6
        }
    }

    /** Four buttons; [fills] says which colour each gets (grey by default). */
    private fun quiz(fills: Map<Int, Int> = emptyMap()): Canvas {
        val c = screen()
        options.forEachIndexed { i, r -> button(c, r, fills[i] ?: grey) }
        return c
    }

    /** A [stroke]-px frame whose outer edge is [r] grown by [grow] px (negative: drawn inside [r]). */
    private fun frame(c: Canvas, r: PxRect, grow: Int, stroke: Int, color: Int) {
        val l = r.left - grow
        val t = r.top - grow
        val rt = r.right + grow
        val b = r.bottom + grow
        c.fill(l, t, rt, t + stroke, color)
        c.fill(l, b - stroke, rt, b, color)
        c.fill(l, t, l + stroke, b, color)
        c.fill(rt - stroke, t, rt, b, color)
    }

    private fun read(c: Canvas, opts: List<PxRect> = options, masks: List<PxRect> = emptyList()) =
        ResultReader.correctOption(c.px, c.w, c.h, opts, masks)

    // ---------------------------------------------------------------- the colour rule

    @Test
    fun greenRuleBoundaries() {
        assertTrue(ResultReader.isGreen(rgb(50, 90, 60))) // g >= 90, g - r = 40, g - b = 30
        assertFalse(ResultReader.isGreen(rgb(51, 90, 60))) // g - r = 39
        assertFalse(ResultReader.isGreen(rgb(50, 89, 60))) // g = 89
        assertFalse(ResultReader.isGreen(rgb(50, 90, 61))) // g - b = 29
    }

    @Test
    fun greenRuleOnRealisticColours() {
        assertTrue(ResultReader.isGreen(green))
        assertTrue(ResultReader.isGreen(gameGreen))
        assertFalse(ResultReader.isGreen(white))
        assertFalse(ResultReader.isGreen(grey))
        assertFalse(ResultReader.isGreen(Canvas.CREAM)) // the original game's banner
        assertFalse(ResultReader.isGreen(Canvas.BLUE))
        assertFalse(ResultReader.isGreen(Canvas.BLACK))
        assertFalse(ResultReader.isGreen(rgb(220, 60, 60))) // a "wrong" red
    }

    @Test
    fun alphaIsIgnored() {
        assertTrue(ResultReader.isGreen(0x0022C55E))
    }

    // ---------------------------------------------------------------- one green option

    @Test
    fun greyButtonsWithWhiteText_null() {
        assertNull(read(quiz()))
        assertNull(read(Canvas(w, h, background).also { c -> options.forEach { button(c, it, lightGrey) } }))
    }

    @Test
    fun darkButtons_null() {
        val c = screen()
        options.forEach { button(c, it, rgb(20, 20, 30)) }
        assertNull(read(c))
    }

    @Test
    fun oneGreenButtonWithWhiteText_returnsItsIndex() {
        for (i in options.indices) {
            assertEquals("option $i", i, read(quiz(mapOf(i to green))))
            assertEquals("option $i (game green)", i, read(quiz(mapOf(i to gameGreen))))
        }
    }

    @Test
    fun twoGreenButtons_null() {
        assertNull(read(quiz(mapOf(1 to green, 3 to green))))
    }

    @Test
    fun allGreenButtons_null() {
        assertNull(read(quiz(mapOf(0 to green, 1 to green, 2 to green, 3 to green))))
    }

    @Test
    fun twoOptionsOnly_oneGreen() {
        val two = options.take(2)
        assertEquals(1, read(quiz(mapOf(1 to green)), two))
        assertEquals(0, read(quiz(mapOf(0 to green)), two))
    }

    // ---------------------------------------------------------------- Clu's own box

    @Test
    fun greenOutlineAroundButton_interiorGrey_null() {
        // Clu's box: its outer edge 6 dp outside the button, 4 dp thick (HIGHLIGHT_PAD_DP, HIGHLIGHT_STROKE_DP).
        for (stroke in listOf(4, 6, 8, 12)) {
            for (i in options.indices) {
                val c = quiz()
                frame(c, options[i], grow = 18, stroke = stroke, color = green)
                assertNull("stroke $stroke around option $i", read(c))
            }
        }
    }

    @Test
    fun greenOutlineOnTheButtonEdge_interiorGrey_null() {
        // Worst case for the inset: the stroke drawn inside the button's own edge.
        for (stroke in listOf(4, 8, 12)) {
            for (i in options.indices) {
                val c = quiz()
                frame(c, options[i], grow = 0, stroke = stroke, color = green)
                assertNull("stroke $stroke inside option $i", read(c))
            }
        }
    }

    @Test
    fun ourBoxOnOneOption_gameGreenOnAnother_returnsTheGameGreen() {
        val c = quiz(mapOf(2 to gameGreen))
        frame(c, options[0], grow = 18, stroke = 8, color = green) // Clu boxed a wrong option
        assertEquals(2, read(c))
    }

    @Test
    fun ourBoxDrawnOverTheEdge_stillLeavesTheOptionClearlyNotGreen() {
        // Only two options, so the boxed one must itself count as "clearly not green" for the other to
        // be named. A 12 px stroke drawn inside the edge is about 21% of this button: the inset must
        // keep all of it out of the sample.
        val c = quiz(mapOf(1 to gameGreen))
        frame(c, options[0], grow = 0, stroke = 12, color = green)
        assertEquals(1, read(c, options.take(2)))
    }

    @Test
    fun ourBoxOnTheRightOption_gameGreensIt_returnsIt() {
        val c = quiz(mapOf(1 to gameGreen))
        frame(c, options[1], grow = 18, stroke = 8, color = green)
        assertEquals(1, read(c))
    }

    // ---------------------------------------------------------------- fail closed

    @Test
    fun maskOverTheGreenOption_null() {
        val c = quiz(mapOf(1 to green))
        val mask = PxRect(options[1].left + 50, options[1].top + 20, options[1].left + 200, options[1].bottom - 20)
        assertNull(read(c, masks = listOf(mask)))
    }

    @Test
    fun maskOverAnotherOption_null() {
        // The panel may hide what the game did to that option: not sure, so no answer.
        val c = quiz(mapOf(1 to green))
        assertNull(read(c, masks = listOf(options[3])))
    }

    @Test
    fun maskElsewhere_doesNotMatter() {
        val c = quiz(mapOf(1 to green))
        val bubble = PxRect(8, 800, 8 + 150, 800 + 150)
        val panel = PxRect(100, 20, 980, 220)
        assertEquals(1, read(c, masks = listOf(bubble, panel)))
    }

    @Test
    fun maskTouchingTheButtonEdge_null() {
        val c = quiz(mapOf(1 to green))
        val mask = PxRect(options[1].right - 5, options[1].top - 40, options[1].right + 100, options[1].top + 5)
        assertNull(read(c, masks = listOf(mask)))
    }

    @Test
    fun wholeScreenGreenTint_null() {
        val tint = rgb(60, 160, 90)
        val c = Canvas(w, h, tint)
        options.forEach { button(c, it, rgb(70, 170, 100)) }
        assertNull(read(c))
    }

    @Test
    fun tintedBackgroundOnly_greyButtons_null() {
        val c = Canvas(w, h, rgb(60, 160, 90))
        options.forEach { button(c, it, grey) }
        assertNull(read(c))
    }

    @Test
    fun oneGreenButOthersGreenish_null() {
        // 4 options: one green, three with about 20% green each (no option clearly not green).
        val c = quiz(mapOf(0 to green))
        for (i in 1..3) {
            val r = options[i]
            val inner = r.width - 2 * (r.width * 15 / 100)
            c.fill(r.left + r.width * 15 / 100, r.top, r.left + r.width * 15 / 100 + inner * 22 / 100, r.bottom, green)
        }
        assertNull(read(c))
    }

    @Test
    fun oneGreenAndOneClearlyNotGreen_evenIfOthersAreGreenish_returnsTheGreen() {
        val c = quiz(mapOf(0 to green))
        val r = options[1]
        val inner = r.width - 2 * (r.width * 15 / 100)
        c.fill(r.left + r.width * 15 / 100, r.top, r.left + r.width * 15 / 100 + inner * 22 / 100, r.bottom, green)
        assertEquals(0, read(c)) // options 2 and 3 are plain grey
    }

    @Test
    fun smallGreenPatch_notEnough() {
        // About 20% of the interior: below GREEN_SHARE, and nothing else is green.
        val c = quiz()
        val r = options[2]
        val inset = r.width * 15 / 100
        val inner = r.width - 2 * inset
        c.fill(r.left + inset, r.top, r.left + inset + inner * 20 / 100, r.bottom, green)
        assertNull(read(c))
    }

    @Test
    fun sizeableGreenPatch_enough() {
        // About 60% of the interior (a button filled from the left, like a progress bar).
        val c = quiz()
        val r = options[2]
        val inset = r.width * 15 / 100
        val inner = r.width - 2 * inset
        c.fill(r.left + inset, r.top, r.left + inset + inner * 60 / 100, r.bottom, green)
        assertEquals(2, read(c))
    }

    @Test
    fun fewerThanTwoOptions_null() {
        val c = quiz(mapOf(0 to green))
        assertNull(read(c, options.take(1)))
        assertNull(read(c, emptyList()))
    }

    @Test
    fun optionOutsideTheImage_null() {
        val c = quiz(mapOf(1 to green))
        val below = options.toMutableList().also { it[3] = PxRect(it[3].left, h - 40, it[3].right, h + 60) }
        assertNull(read(c, below))
        val left = options.toMutableList().also { it[0] = PxRect(-10, it[0].top, it[0].right, it[0].bottom) }
        assertNull(read(c, left))
        val right = options.toMutableList().also { it[0] = PxRect(it[0].left, it[0].top, w + 1, it[0].bottom) }
        assertNull(read(c, right))
        val above = options.toMutableList().also { it[0] = PxRect(it[0].left, -1, it[0].right, it[0].bottom) }
        assertNull(read(c, above))
    }

    @Test
    fun emptyOrTinyOptionRect_null() {
        val c = quiz(mapOf(1 to green))
        val empty = options.toMutableList().also { it[2] = PxRect(100, 100, 100, 200) }
        assertNull(read(c, empty))
        val inverted = options.toMutableList().also { it[2] = PxRect(300, 500, 200, 400) }
        assertNull(read(c, inverted))
        val tiny = options.toMutableList().also { it[2] = PxRect(100, 100, 102, 102) } // nothing left after the inset
        assertNull(read(c, tiny))
        val few = options.toMutableList().also { it[2] = PxRect(100, 100, 112, 112) } // 4 x 4 samples: too few to judge
        assertNull(read(c, few))
    }

    @Test
    fun pixelsShorterThanTheImage_null() {
        val c = quiz(mapOf(1 to green))
        assertNull(ResultReader.correctOption(c.px.copyOf(c.px.size / 2), w, h, options, emptyList()))
        assertNull(ResultReader.correctOption(c.px, 0, h, options, emptyList()))
        assertNull(ResultReader.correctOption(c.px, w, 0, options, emptyList()))
    }

    // ---------------------------------------------------------------- optionsUsable

    @Test
    fun optionsUsable_plainScreen() {
        assertTrue(ResultReader.optionsUsable(w, h, options, emptyList()))
    }

    @Test
    fun optionsUsable_falseWhenCoveredOutsideOrTooFew() {
        assertFalse(ResultReader.optionsUsable(w, h, options, listOf(options[2])))
        assertFalse(ResultReader.optionsUsable(w, h, options, listOf(PxRect(0, 0, w, h))))
        assertFalse(ResultReader.optionsUsable(w, h / 2, options, emptyList())) // options below the image
        assertFalse(ResultReader.optionsUsable(w, h, options.take(1), emptyList()))
        assertFalse(ResultReader.optionsUsable(0, 0, options, emptyList()))
    }

    // ---------------------------------------------------------------- ResultConfirmer

    private fun assertWait(s: ResultConfirmer.Step) = assertTrue("expected Wait, got $s", s === ResultConfirmer.Step.Wait)
    private fun assertGiveUp(s: ResultConfirmer.Step) = assertTrue("expected GiveUp, got $s", s === ResultConfirmer.Step.GiveUp)
    private fun assertAct(index: Int, s: ResultConfirmer.Step) {
        assertTrue("expected Act($index), got $s", s is ResultConfirmer.Step.Act)
        assertEquals(index, (s as ResultConfirmer.Step.Act).index)
    }

    @Test
    fun confirmer_sameIndexTwiceInARow_acts() {
        val v = ResultConfirmer()
        assertWait(v.offer(true, 2))
        assertAct(2, v.offer(true, 2))
    }

    @Test
    fun confirmer_aChangedIndexStartsOver() {
        val v = ResultConfirmer()
        assertWait(v.offer(true, 1))
        assertWait(v.offer(true, 2))
        assertAct(2, v.offer(true, 2))
    }

    @Test
    fun confirmer_aCheckWithNothingGreenBreaksTheStreak() {
        val v = ResultConfirmer()
        assertWait(v.offer(true, 1))
        assertWait(v.offer(true, null))
        assertWait(v.offer(true, 1))
        assertAct(1, v.offer(true, 1))
    }

    @Test
    fun confirmer_anUnusableCheckBreaksTheStreak() {
        val v = ResultConfirmer()
        assertWait(v.offer(true, 3))
        assertWait(v.offer(false, null))
        assertWait(v.offer(true, 3))
        assertAct(3, v.offer(true, 3))
    }

    @Test
    fun confirmer_threeUnusableInARow_givesUp() {
        val v = ResultConfirmer()
        assertWait(v.offer(false, null))
        assertWait(v.offer(false, null))
        assertGiveUp(v.offer(false, null))
    }

    @Test
    fun confirmer_aUsableCheckResetsTheUnusableCount() {
        val v = ResultConfirmer()
        assertWait(v.offer(false, null))
        assertWait(v.offer(false, null))
        assertWait(v.offer(true, null))
        assertWait(v.offer(false, null))
        assertWait(v.offer(false, null))
        assertGiveUp(v.offer(false, null))
    }

    @Test
    fun confirmer_neverActsOnAnIndexReportedForUnusableOptions() {
        // Defensive: an index with usable = false is ignored.
        val v = ResultConfirmer()
        assertWait(v.offer(false, 1))
        assertWait(v.offer(false, 1))
    }
}
