package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for "manual calibration": a template from boxes the user drew on a capture. */
class ManualTemplateTest {
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

    /** A dark screen with a lighter box (and text in it) at each of [question] and [boxes]. */
    private fun screen(question: PxRect, boxes: List<PxRect>): IntArray {
        val c = Canvas(w, h, screenBg)
        c.fill(question.left, question.top, question.right, question.bottom, boxBg)
        val l = question.left + question.width / 5
        val r = question.right - question.width / 5
        light(c, l, question.top + question.height / 4, r, question.top + question.height / 4 + 30)
        light(c, l, question.top + question.height * 11 / 20, r - question.width / 5, question.top + question.height * 11 / 20 + 30)
        for (b in boxes) {
            c.fill(b.left, b.top, b.right, b.bottom, boxBg)
            val t = b.top + (b.height - 40) / 2
            light(c, b.left + b.width / 4, t, b.right - b.width / 4, t + 40)
        }
        return c.px
    }

    private val question = PxRect(108, 300, 972, 600)

    /** Four stacked buttons, top to bottom. */
    private val stack = List(4) { PxRect(270, 800 + 300 * it, 810, 1020 + 300 * it) }

    private val topLeft = PxRect(108, 800, 529, 1000)
    private val topRight = PxRect(551, 800, 972, 1000)
    private val bottomLeft = PxRect(108, 1050, 529, 1250)
    private val bottomRight = PxRect(551, 1050, 972, 1250)

    private val field = PxRect(302, 720, 778, 840)

    private fun build(
        q: PxRect = question,
        options: List<PxRect> = emptyList(),
        numeric: PxRect? = null,
        px: IntArray = screen(q, options + listOfNotNull(numeric)),
        masks: List<PxRect> = emptyList(),
    ) = ManualTemplate.build(q, options, numeric, px, w, h, masks, "m1", 42L)

    private fun assertRect(expected: PxRect, actual: RelRect) {
        val e = RelRect.fromPx(expected, w, h)
        assertEquals(e.left, actual.left, 1e-5f)
        assertEquals(e.top, actual.top, 1e-5f)
        assertEquals(e.right, actual.right, 1e-5f)
        assertEquals(e.bottom, actual.bottom, 1e-5f)
    }

    // ---------- a stack of buttons ----------

    @Test fun buildsAVerticalTemplate_optionsTopToBottom() {
        // Drawn in a scrambled order.
        val t = build(options = listOf(stack[2], stack[0], stack[3], stack[1]))!!
        assertEquals(Arrangement.VERTICAL, t.arrangement)
        assertEquals(4, t.options.size)
        for (i in 0 until 4) assertRect(stack[i], t.options[i])
        assertRect(question, t.question)
        assertNull(t.numericField)
        assertTrue(t.isValid)
    }

    @Test fun theTemplateIsTrustedManualAndUnbounded() {
        val t = build(options = stack)!!
        assertEquals("m1", t.id)
        assertNull(t.packageName)
        assertNull(t.language)
        assertEquals(TemplateState.TRUSTED, t.state)
        assertEquals(0, t.hits)
        assertEquals(0, t.misses)
        assertEquals("manual", t.source)
        assertFalse(t.bounded)
        assertNull(t.altSignatures)
        assertEquals(42L, t.createdAt)
        assertEquals(42L, t.lastUsedAt)
    }

    @Test fun fingerprintAndSignaturesAreThoseOfTheCapture() {
        val px = screen(question, stack)
        val masks = listOf(PxRect(0, 0, 400, 300))
        val t = build(options = stack, px = px, masks = masks)!!
        assertEquals(ScreenFingerprint.of(px, w, h, masks), t.fingerprint)
        assertTrue(t.fingerprint.contains('.'))
        assertEquals(5, t.signatures.size)
        assertEquals(TemplateReader.signature(px, w, h, question), t.signatures[0])
        for (i in 0 until 4) assertEquals(TemplateReader.signature(px, w, h, stack[i]), t.signatures[1 + i])
    }

    @Test fun theTemplateFitsAndReadsTheScreenItWasDrawnOn() {
        val px = screen(question, stack)
        val t = build(options = stack, px = px)!!
        assertTrue(TemplateReader.fits(t, px, w, h))
        val layout = TemplateReader.layoutFor(t, px, w, h)
        assertNotNull(layout)
        assertEquals(4, layout!!.buttons.size)
        assertTrue(layout.questionLines.isNotEmpty())
        assertTrue(layout.optionLines.all { it.isNotEmpty() })
    }

    @Test fun twoAndEightOptionsAreTheLimits() {
        assertNotNull(build(options = stack.take(2)))
        val eight = List(8) { PxRect(270, 700 + 200 * it, 810, 860 + 200 * it) }
        assertNotNull(build(options = eight))
        assertNull(build(options = stack.take(1)))
        val nine = List(9) { PxRect(270, 700 + 180 * it, 810, 840 + 180 * it) }
        assertNull(build(options = nine))
    }

    // ---------- a grid ----------

    @Test fun gridIsReadRowByRowRightToLeft() {
        // Drawn in a scrambled order.
        val t = build(options = listOf(bottomLeft, topRight, bottomRight, topLeft))!!
        assertEquals(Arrangement.GRID, t.arrangement)
        assertRect(topRight, t.options[0])
        assertRect(topLeft, t.options[1])
        assertRect(bottomRight, t.options[2])
        assertRect(bottomLeft, t.options[3])
        // Signatures follow the same order: question, then the options as ordered.
        val px = screen(question, listOf(topLeft, topRight, bottomLeft, bottomRight))
        assertEquals(TemplateReader.signature(px, w, h, topRight), t.signatures[1])
        assertEquals(TemplateReader.signature(px, w, h, bottomLeft), t.signatures[4])
    }

    @Test fun buttonsDrawnSlightlyOffShareARow() {
        // The right one 60 px lower: overlap 140 of 200 -> same row.
        val right = PxRect(551, 860, 972, 1060)
        val t = build(options = listOf(topLeft, right))!!
        assertEquals(Arrangement.GRID, t.arrangement)
        assertRect(right, t.options[0])
        assertRect(topLeft, t.options[1])
    }

    @Test fun oneRowOfTwoMakesItAGridEvenWithALoneButton() {
        val lone = PxRect(270, 1100, 810, 1300)
        val t = build(options = listOf(lone, topLeft, topRight))!!
        assertEquals(Arrangement.GRID, t.arrangement)
        assertRect(topRight, t.options[0])
        assertRect(topLeft, t.options[1])
        assertRect(lone, t.options[2])
    }

    // ---------- a number field ----------

    @Test fun buildsANumericTemplate() {
        val px = screen(question, listOf(field))
        val t = build(numeric = field, px = px)!!
        assertEquals(Arrangement.NUMERIC, t.arrangement)
        assertTrue(t.options.isEmpty())
        assertRect(field, t.numericField!!)
        assertEquals(2, t.signatures.size)
        assertEquals(TemplateReader.signature(px, w, h, field), t.signatures[1])
        assertEquals(TemplateState.TRUSTED, t.state)
        assertEquals("manual", t.source)
        assertTrue(t.isValid)
        val layout = TemplateReader.layoutFor(t, px, w, h)
        assertNotNull(layout)
        assertNotNull(layout!!.numericField)
    }

    // ---------- rejections ----------

    @Test fun needsExactlyOneOfOptionsOrAField() {
        assertNull(build()) // nothing but the question
        assertNull(build(options = stack, numeric = field)) // both
    }

    @Test fun declinesATinyRegion() {
        // A sliver: 40 px of 2400 is under 2 % of the height.
        assertNull(build(q = PxRect(108, 300, 972, 340)))
        assertNull(build(options = listOf(stack[0], PxRect(270, 1100, 810, 1140))))
        // Narrow: 20 px of 1080 is under 2 % of the width.
        assertNull(build(options = listOf(stack[0], PxRect(270, 1100, 290, 1300))))
        assertNull(build(numeric = PxRect(302, 720, 322, 840)))
    }

    @Test fun declinesAnEmptyOrBackwardsBox() {
        assertNull(build(q = PxRect(972, 300, 108, 600)))
        assertNull(build(options = listOf(stack[0], PxRect(270, 1100, 810, 1100))))
    }

    @Test fun declinesABoxOutsideTheImage() {
        val px = screen(question, stack) // the capture itself is fine; only the drawn boxes stray
        assertNull(build(options = listOf(stack[0], PxRect(270, 2300, 810, h + 100)), px = px))
        assertNull(build(options = listOf(stack[0], PxRect(-10, 1100, 400, 1300)), px = px))
        assertNull(build(q = PxRect(108, 300, w + 1, 600), options = stack, px = px))
    }

    @Test fun declinesOverlappingOptions() {
        // Half of the second button is under the first one.
        assertNull(build(options = listOf(stack[0], PxRect(270, 910, 810, 1130))))
    }

    @Test fun toleratesAnEdgeTouchOrASmallOverlap() {
        // Touching edges, and 10 px (4.5 % of the smaller height) of overlap.
        assertNotNull(build(options = listOf(stack[0], PxRect(270, 1020, 810, 1240))))
        assertNotNull(build(options = listOf(stack[0], PxRect(270, 1010, 810, 1230))))
    }

    @Test fun declinesAnOptionOrFieldOverTheQuestion() {
        assertNull(build(options = listOf(stack[0], PxRect(270, 450, 810, 700))))
        assertNull(build(numeric = PxRect(302, 400, 778, 700)))
    }

    @Test fun declinesAWrongImageSize() {
        val px = screen(question, stack)
        assertNull(ManualTemplate.build(question, stack, null, px, w, h + 1, emptyList(), "x", 1L))
        assertNull(ManualTemplate.build(question, stack, null, px, 0, 0, emptyList(), "x", 1L))
        assertNull(ManualTemplate.build(question, stack, null, IntArray(10), w, h, emptyList(), "x", 1L))
    }
}
