package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateReaderTest {
    private val w = 1080
    private val h = 2400

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val screenBg = rgb(20, 20, 30)
    private val boxBg = rgb(70, 70, 90)

    /** Text-like: strokes 3 px wide every 6 px (a filled block would be a fill, not text). */
    private fun light(c: Canvas, l: Int, t: Int, r: Int, b: Int) {
        for (y in t until b) for (x in l until r) {
            if ((x - l) % 6 >= 3) continue
            val v = 220 + x % 16
            c.px[y * c.w + x] = rgb(v, v, v)
        }
    }

    // Layout of the synthetic game at 1080x2400, in 1/1000 of the capture.
    private val question = RelRect(0.10f, 0.125f, 0.90f, 0.25f)
    private val grid = listOf(
        RelRect(0.10f, 1f / 3, 0.49f, 5f / 12),
        RelRect(0.51f, 1f / 3, 0.90f, 5f / 12),
        RelRect(0.10f, 0.4375f, 0.49f, 0.5208f),
        RelRect(0.51f, 0.4375f, 0.90f, 0.5208f),
    )
    private val field = RelRect(0.28f, 0.30f, 0.72f, 0.35f)

    /**
     * A dark screen with a lighter question box holding two light text lines and four lighter
     * boxes with one line each, at [scale] times 1080x2400. A box in [flat] gets no text; one in
     * [recoloured] a different background; one in [darkText] dark text instead of light.
     */
    private fun screen(
        scale: Double = 1.0,
        numeric: Boolean = false,
        flat: Set<Int> = emptySet(),
        recoloured: Map<Int, Int> = emptyMap(),
        darkText: Set<Int> = emptySet(),
        questionText: Boolean = true,
    ): Triple<IntArray, Int, Int> {
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()
        val c = Canvas(sw, sh, screenBg)
        fun px(r: RelRect) = r.toPx(sw, sh)
        val q = px(question)
        c.fill(q.left, q.top, q.right, q.bottom, boxBg)
        if (questionText) {
            val lh = (30 * scale).toInt()
            val l = q.left + q.width / 5
            val r = q.right - q.width / 5
            light(c, l, q.top + q.height / 4, r, q.top + q.height / 4 + lh)
            light(c, l, q.top + q.height * 11 / 20, r - q.width / 5, q.top + q.height * 11 / 20 + lh)
        }
        val boxes = if (numeric) listOf(field) else grid
        for ((i, b) in boxes.withIndex()) {
            val r = px(b)
            c.fill(r.left, r.top, r.right, r.bottom, recoloured[i] ?: boxBg)
            if (i in flat) continue
            val lh = (40 * scale).toInt()
            val l = r.left + r.width / 4
            val rr = r.right - r.width / 4
            val t = r.top + (r.height - lh) / 2
            if (i in darkText) c.ink(l, t, rr, t + lh) else light(c, l, t, rr, t + lh)
        }
        return Triple(c.px, sw, sh)
    }

    private fun template(
        arrangement: Arrangement,
        options: List<RelRect>,
        numericField: RelRect?,
        reference: Triple<IntArray, Int, Int>,
        questionRect: RelRect = question,
    ): LayoutTemplate {
        val (px, sw, sh) = reference
        val bare = LayoutTemplate(
            id = "t1", packageName = "com.example.game", fingerprint = ScreenFingerprint.of(px, sw, sh),
            arrangement = arrangement, question = questionRect, options = options, numericField = numericField,
            signatures = emptyList(), language = "en", state = TemplateState.CANDIDATE, hits = 0, misses = 0,
            source = "model", createdAt = 1L, lastUsedAt = 1L,
        )
        return bare.copy(signatures = TemplateReader.signatures(bare, px, sw, sh))
    }

    private fun gridTemplate() = template(Arrangement.GRID, grid, null, screen())

    @Test
    fun signatureIsTheRingsBackgroundAndUniformity() {
        val (px, sw, sh) = screen()
        val s = TemplateReader.signature(px, sw, sh, question.toPx(sw, sh))
        assertEquals(72.0, s.background.toDouble(), 3.0)
        assertEquals(1f, s.uniformity, 1e-6f)
        val c = Canvas(400, 200, Canvas.WHITE)
        c.ink(50, 80, 350, 120)
        val dark = TemplateReader.signature(c.px, 400, 200, PxRect(0, 0, 400, 200))
        assertEquals(255, dark.background)
        assertEquals(1f, dark.uniformity, 1e-6f)
    }

    @Test
    fun signatureDoesNotDependOnTheTextInside() {
        val withText = screen()
        val bare = screen(questionText = false, flat = setOf(0, 1, 2, 3))
        for (r in listOf(question) + grid) {
            val a = TemplateReader.signature(withText.first, withText.second, withText.third, r.toPx(withText.second, withText.third))
            val b = TemplateReader.signature(bare.first, bare.second, bare.third, r.toPx(bare.second, bare.third))
            assertEquals(a, b)
        }
    }

    @Test
    fun signatureOfARingCrossingAPictureIsLessUniform() {
        val (px, sw, sh) = screen()
        val r = grid[1].toPx(sw, sh)
        // A strip of another colour over the left 35 % of the button, ring included.
        for (y in r.top until r.bottom) for (x in r.left until r.left + r.width * 35 / 100) px[y * sw + x] = rgb(200, 180, 160)
        val s = TemplateReader.signature(px, sw, sh, r)
        assertEquals(72.0, s.background.toDouble(), 3.0) // the median is still the button
        assertTrue("uniformity ${s.uniformity}", s.uniformity in 0.55f..0.75f)
    }

    @Test
    fun signatureOfFlatRegionIsUniform() {
        val (px, sw, sh) = screen()
        val s = TemplateReader.signature(px, sw, sh, PxRect(50, 2000, 300, 2200)) // plain screen background
        assertEquals(1f, s.uniformity, 1e-6f)
    }

    @Test
    fun signatureCopesWithTinyAndOutOfImageRects() {
        val (px, sw, sh) = screen()
        TemplateReader.signature(px, sw, sh, PxRect(100, 100, 103, 102))
        TemplateReader.signature(px, sw, sh, PxRect(100, 100, 100, 100))
        TemplateReader.signature(px, sw, sh, PxRect(-50, -50, sw + 50, sh + 50))
        TemplateReader.signature(px, sw, sh, PxRect(5000, 5000, 6000, 6000))
    }

    @Test
    fun gridTemplateFitsItsOwnScreenAndReadsLines() {
        val t = gridTemplate()
        assertTrue(t.isValid)
        val (px, sw, sh) = screen()
        assertTrue(TemplateReader.fits(t, px, sw, sh))
        val layout = TemplateReader.layoutFor(t, px, sw, sh)
        assertNotNull(layout)
        layout!!
        assertEquals(2, layout.questionLines.size)
        assertEquals(question.toPx(sw, sh), layout.banner)
        assertEquals(4, layout.buttons.size)
        assertEquals(grid.map { it.toPx(sw, sh) }, layout.buttons)
        assertEquals(4, layout.optionLines.size)
        for ((i, lines) in layout.optionLines.withIndex()) {
            assertEquals("option $i", 1, lines.size)
            val b = layout.buttons[i]
            assertTrue(lines[0].left >= b.left && lines[0].right <= b.right && lines[0].top >= b.top && lines[0].bottom <= b.bottom)
        }
        assertFalse(layout.numeric)
    }

    @Test
    fun templateFitsTheSameScreenAtAnotherSize() {
        val t = gridTemplate()
        val (px, sw, sh) = screen(scale = 0.5)
        assertTrue(TemplateReader.fits(t, px, sw, sh))
        val layout = TemplateReader.layoutFor(t, px, sw, sh)!!
        assertEquals(2, layout.questionLines.size)
        assertEquals(listOf(1, 1, 1, 1), layout.optionLines.map { it.size })
    }

    @Test
    fun doesNotFitWhenABackgroundChangesByMoreThanTheTolerance() {
        val t = gridTemplate()
        // 72 -> 130: 58 away; 72 -> 100 is 28 away, still more than the tolerance of 25.
        for (v in listOf(130, 100)) {
            val (px, sw, sh) = screen(recoloured = mapOf(1 to rgb(v, v, v)))
            assertFalse("$v", TemplateReader.fits(t, px, sw, sh))
        }
        // A smaller change is fine: 72 -> 90.
        val (px2, sw2, sh2) = screen(recoloured = mapOf(1 to rgb(90, 90, 90)))
        assertTrue(TemplateReader.fits(t, px2, sw2, sh2))
    }

    @Test
    fun fitsWhateverTheTextColourOrAmountOfText() {
        val t = gridTemplate()
        // Same background, dark text instead of light: the frame is what is compared.
        val (px, sw, sh) = screen(darkText = setOf(2))
        assertTrue(TemplateReader.fits(t, px, sw, sh))
        // A learned button that had text still fits when it is empty now, and the other way round.
        val (px2, sw2, sh2) = screen(flat = setOf(0, 1, 3), questionText = false)
        assertTrue(TemplateReader.fits(t, px2, sw2, sh2))
        val bare = template(Arrangement.GRID, grid, null, screen(flat = setOf(3)))
        val (px3, sw3, sh3) = screen()
        assertTrue(TemplateReader.fits(bare, px3, sw3, sh3))
    }

    @Test
    fun doesNotFitWhenARegionsFrameIsLessUniformThanLearned() {
        val t = gridTemplate()
        val (px, sw, sh) = screen()
        val r = grid[1].toPx(sw, sh)
        // 35 % of the ring in another colour (a picture over the button): uniformity 1 -> ~0.65.
        for (y in r.top until r.bottom) for (x in r.left until r.left + r.width * 35 / 100) px[y * sw + x] = rgb(200, 180, 160)
        assertFalse(TemplateReader.fits(t, px, sw, sh))
        // A template learned on such a region accepts it.
        val learned = template(Arrangement.GRID, grid, null, Triple(px.copyOf(), sw, sh))
        assertTrue(TemplateReader.fits(learned, px, sw, sh))
    }

    @Test
    fun aSecondLookFits_andOnlyWhenItMatches() {
        // The same layout in another state (the game greys its buttons while others play): a
        // template may carry the signatures of both looks.
        val dark = gridTemplate()
        val (px, sw, sh) = screen()
        for (i in px.indices) px[i] = (0xFF shl 24) or ((px[i] and 0xFEFEFE) shr 1) // the whole screen dimmed
        assertFalse(TemplateReader.fits(dark, px, sw, sh))
        val both = dark.copy(altSignatures = TemplateReader.signatures(dark, px, sw, sh))
        assertTrue(TemplateReader.fits(both, px, sw, sh))
        val (normal, nw, nh) = screen()
        assertTrue("the first look still fits", TemplateReader.fits(both, normal, nw, nh))
    }

    @Test
    fun doesNotFitWhenARegionsFrameIsMuchMoreUniformThanLearned() {
        // Learned where the frame crossed two colours (a field over a board); on another screen of
        // the same game the same place is plain (only the banner): that is not this screen.
        val (learnedPx, sw, sh) = screen()
        val r = grid[1].toPx(sw, sh)
        for (y in r.top until r.bottom) for (x in r.left until r.left + r.width * 35 / 100) learnedPx[y * sw + x] = rgb(200, 180, 160)
        val t = template(Arrangement.GRID, grid, null, Triple(learnedPx, sw, sh))
        val (plain, pw, ph) = screen()
        assertFalse(TemplateReader.fits(t, plain, pw, ph))
    }

    @Test
    fun anEmptyNumberFieldFitsATemplateLearnedWithDigitsInIt() {
        val t = template(Arrangement.NUMERIC, emptyList(), field, screen(numeric = true))
        val (px, sw, sh) = screen(numeric = true, flat = setOf(0))
        assertTrue(TemplateReader.fits(t, px, sw, sh))
        val empty = template(Arrangement.NUMERIC, emptyList(), field, screen(numeric = true, flat = setOf(0)))
        val (px2, sw2, sh2) = screen(numeric = true)
        assertTrue(TemplateReader.fits(empty, px2, sw2, sh2))
    }

    @Test
    fun doesNotFitWithTheWrongNumberOfSignatures() {
        val t = gridTemplate().copy(signatures = emptyList())
        val (px, sw, sh) = screen()
        assertFalse(TemplateReader.fits(t, px, sw, sh))
    }

    @Test
    fun optionWithoutTextKeepsAnEmptyListOfLines() {
        val t = gridTemplate()
        val (px, sw, sh) = screen(flat = setOf(1))
        val layout = TemplateReader.layoutFor(t, px, sw, sh)!!
        assertEquals(listOf(1, 0, 1, 1), layout.optionLines.map { it.size })
        assertEquals(4, layout.buttons.size)
    }

    @Test
    fun questionWithoutTextGivesNoLayout() {
        val t = gridTemplate()
        val (px, sw, sh) = screen(questionText = false)
        assertNull(TemplateReader.layoutFor(t, px, sw, sh))
    }

    @Test
    fun numericTemplateGivesTheFieldAndNoOptions() {
        val t = template(Arrangement.NUMERIC, emptyList(), field, screen(numeric = true))
        assertTrue(t.isValid)
        assertEquals(2, t.signatures.size)
        val (px, sw, sh) = screen(numeric = true)
        assertTrue(TemplateReader.fits(t, px, sw, sh))
        val layout = TemplateReader.layoutFor(t, px, sw, sh)!!
        assertTrue(layout.numeric)
        assertEquals(field.toPx(sw, sh), layout.numericField)
        assertTrue(layout.buttons.isEmpty())
        assertTrue(layout.optionLines.isEmpty())
        assertEquals(2, layout.questionLines.size)
        assertEquals(question.toPx(sw, sh), layout.banner)
    }

    @Test
    fun aQuestionRunningPastItsRectangleGivesNoLayout() {
        // The text lines span 20 %..80 % of the question box; a template rectangle of 35 %..65 % cuts them.
        val narrow = RelRect(0.35f, 0.125f, 0.65f, 0.25f)
        val ref = screen()
        val t = template(Arrangement.GRID, grid, null, ref, questionRect = narrow)
        val (px, sw, sh) = ref
        assertTrue(TemplateReader.fits(t, px, sw, sh))
        val r = TemplateReader.layoutForDetailed(t, px, sw, sh)
        assertNull(r.layout)
        assertEquals(TemplateReader.Outcome.QUESTION_CLIPPED, r.outcome)
        assertNull(TemplateReader.layoutFor(t, px, sw, sh))
        // The same for a numeric template.
        val tn = template(Arrangement.NUMERIC, emptyList(), field, screen(numeric = true), questionRect = narrow)
        val (pn, wn, hn) = screen(numeric = true)
        assertEquals(TemplateReader.Outcome.QUESTION_CLIPPED, TemplateReader.layoutForDetailed(tn, pn, wn, hn).outcome)
    }

    @Test
    fun anOptionRunningPastItsRectangleGivesNoLayout() {
        val ref = screen()
        // The option text spans the middle half of each button: halve the first button.
        val cut = grid[0].let { RelRect(it.left, it.top, (it.left + it.right) / 2, it.bottom) }
        val t = template(Arrangement.GRID, listOf(cut) + grid.drop(1), null, ref)
        val (px, sw, sh) = ref
        val r = TemplateReader.layoutForDetailed(t, px, sw, sh)
        assertNull(r.layout)
        assertEquals(TemplateReader.Outcome.OPTION_CLIPPED, r.outcome)
        // A numeric template has no options, so only its question matters.
        assertEquals(TemplateReader.Outcome.OK, TemplateReader.layoutForDetailed(gridTemplate(), px, sw, sh).outcome)
    }

    @Test
    fun layoutForDetailedSaysWhyThereIsNoLayout() {
        val t = gridTemplate()
        val (px, sw, sh) = screen(questionText = false)
        val r = TemplateReader.layoutForDetailed(t, px, sw, sh)
        assertNull(r.layout)
        assertEquals(TemplateReader.Outcome.NO_QUESTION_TEXT, r.outcome)
    }
}
