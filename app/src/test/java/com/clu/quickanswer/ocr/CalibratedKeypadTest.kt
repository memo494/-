package com.clu.quickanswer.ocr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for "معايرة لوحة الأرقام": a number pad the user draws once for a game no built-in
 * adapter knows, then typed into only while every key is still there and the field is still empty.
 */
class CalibratedKeypadTest {
    private val w = 1080
    private val h = 2400

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val screenBg = rgb(20, 20, 30)
    private val boxBg = rgb(70, 70, 90)
    private val fieldBg = rgb(235, 235, 235)
    private val keyBg = rgb(60, 110, 160)
    private val submitBg = rgb(40, 160, 70)

    private val question = PxRect(108, 300, 972, 600)
    private val field = PxRect(240, 700, 840, 850)

    /** 1 2 3 / 4 5 6 / 7 8 9 / 0, a phone-style pad (not the layout of any built-in adapter). */
    private fun keyRect(d: Int): PxRect {
        val (col, row) = if (d == 0) 1 to 3 else (d - 1) % 3 to (d - 1) / 3
        val l = 200 + col * 240
        val t = 950 + row * 240
        return PxRect(l, t, l + 200, t + 200)
    }
    private val keys = (0..9).map { keyRect(it) }
    private val submit = PxRect(680, 1670, 880, 1870)

    /** Text-like strokes, 3 px wide every 6 px. */
    private fun light(c: Canvas, l: Int, t: Int, r: Int, b: Int) {
        for (y in t until b) for (x in l until r) {
            if ((x - l) % 6 >= 3) continue
            c.px[y * c.w + x] = rgb(230, 230, 230)
        }
    }

    /** The game's number screen, the field empty unless [typed] strokes are drawn in it. */
    private fun screen(typed: Boolean = false, hint: Boolean = false): Canvas {
        val c = Canvas(w, h, screenBg)
        c.fill(question.left, question.top, question.right, question.bottom, boxBg)
        light(c, question.left + 150, question.top + 100, question.right - 150, question.top + 140)
        c.fill(field.left, field.top, field.right, field.bottom, fieldBg)
        if (hint) for (i in 0 until 4) {
            val l = field.left + 180 + i * 70
            c.fill(l, field.top + 50, l + 30, field.top + 100, rgb(200, 200, 200))
        }
        if (typed) c.fill(field.left + 290, field.top + 40, field.left + 296, field.top + 110, rgb(30, 30, 30))
        for (k in keys + submit) c.fill(k.left, k.top, k.right, k.bottom, if (k == submit) submitBg else keyBg)
        return c
    }

    private fun build(c: Canvas, keys: List<PxRect> = this.keys, submit: PxRect? = this.submit,
                      options: List<PxRect> = emptyList(), numericField: PxRect? = field) =
        ManualTemplate.build(question, options, numericField, c.px, w, h, emptyList(), "t1", 1L, keys, submit)

    // ---- FieldStamp ----

    @Test fun anEmptyFieldStaysEmpty_andATypedDigitFillsIt() {
        val stamp = FieldStamp.of(screen().px, w, h, field)
        assertNotNull(stamp)
        assertEquals(FieldStamp.State.EMPTY, FieldStamp.state(stamp, screen().px, w, h, field))
        assertEquals(FieldStamp.State.FILLED, FieldStamp.state(stamp, screen(typed = true).px, w, h, field))
    }

    @Test fun aPlaceholderIsPartOfTheEmptyLook() {
        val stamp = FieldStamp.of(screen(hint = true).px, w, h, field)
        assertEquals(FieldStamp.State.EMPTY, FieldStamp.state(stamp, screen(hint = true).px, w, h, field))
        assertEquals(FieldStamp.State.FILLED, FieldStamp.state(stamp, screen(hint = false).px, w, h, field))
    }

    @Test fun aBadStampOrFieldIsUnknown() {
        val px = screen().px
        assertEquals(FieldStamp.State.UNKNOWN, FieldStamp.state(null, px, w, h, field))
        assertEquals(FieldStamp.State.UNKNOWN, FieldStamp.state("zz", px, w, h, field))
        val stamp = FieldStamp.of(px, w, h, field)
        assertEquals(FieldStamp.State.UNKNOWN, FieldStamp.state(stamp, px, w, h, PxRect(1000, 0, 1200, 100)))
        assertNull(FieldStamp.of(px, w, h, PxRect(10, 10, 20, 20)))
    }

    // ---- ManualTemplate with keys ----

    @Test fun buildsANumberTemplateWithItsPad() {
        val t = build(screen())!!
        assertTrue(t.isValid)
        assertEquals(Arrangement.NUMERIC, t.arrangement)
        assertEquals(10, t.keys!!.size)
        assertEquals(keys[7], t.keys!![7].toPx(w, h))
        assertEquals(submit, t.submit!!.toPx(w, h))
        assertEquals(11, t.keySignatures!!.size)
        assertTrue(FieldStamp.isValid(t.fieldEmpty))
        // Keys are not regions: the reader's signatures stay question + field.
        assertEquals(2, t.signatures.size)
    }

    @Test fun theConfirmButtonIsOptional() {
        val t = build(screen(), submit = null)!!
        assertNull(t.submit)
        assertEquals(10, t.keySignatures!!.size)
    }

    @Test fun declinesAPartialPad_aButtonWithoutKeys_andKeysOnAChoiceScreen() {
        assertNull(build(screen(), keys = keys.take(9)))
        assertNull(build(screen(), keys = emptyList(), submit = submit))
        val opts = listOf(PxRect(108, 700, 529, 850), PxRect(551, 700, 972, 850))
        assertNull(build(screen(), options = opts, numericField = null))
    }

    @Test fun declinesOverlappingKeys_orAKeyOverTheField() {
        assertNull(build(screen(), keys = keys.toMutableList().also { it[2] = keys[1] }))
        assertNull(build(screen(), keys = keys.toMutableList().also { it[5] = PxRect(240, 700, 440, 850) }))
    }

    @Test fun aTemplateWithoutKeysIsUnchanged() {
        val t = ManualTemplate.build(question, emptyList(), field, screen().px, w, h, emptyList(), "t0", 1L)!!
        assertNull(t.keys)
        assertNull(t.fieldEmpty)
        assertTrue(t.isValid)
    }

    // ---- JSON ----

    @Test fun keysSurviveJson_andOldFilesStillLoad() {
        val t = build(screen())!!
        val back = LayoutTemplate.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, back)
        val old = t.toJson().apply { remove("keys"); remove("sub"); remove("ksig"); remove("fe") }
        val loaded = LayoutTemplate.fromJson(old)!!
        assertNull(loaded.keys)
        assertTrue(loaded.isValid)
    }

    @Test fun aBrokenPadInJsonIsDropped() {
        val t = build(screen())!!
        assertNull(LayoutTemplate.fromJson(t.toJson().put("fe", "nothex")))
        assertFalse(t.copy(keySignatures = t.keySignatures!!.take(3)).isValid)
        assertFalse(t.copy(keys = t.keys!!.take(9)).isValid)
    }

    // ---- At answer time ----

    @Test fun locatesEveryDigitAndTheConfirmButton() {
        val t = build(screen())!!
        val found = CalibratedKeypad.locate(t, screen().px, w, h)!!
        assertEquals(('0'..'9').toSet(), found.digits.keys)
        for (d in 0..9) assertEquals(keys[d], found.digits.getValue('0' + d))
        assertEquals(submit, found.submit)
        assertEquals(field, found.field)
        assertEquals(FieldStamp.State.EMPTY, CalibratedKeypad.fieldState(found, screen().px, w, h))
        assertEquals(FieldStamp.State.FILLED, CalibratedKeypad.fieldState(found, screen(typed = true).px, w, h))
    }

    @Test fun declinesWhenSomethingCoversThePad() {
        val t = build(screen())!!
        val covered = screen().apply { fill(150, 1150, 950, 1500, rgb(250, 250, 250)) }
        assertNull(CalibratedKeypad.locate(t, covered.px, w, h))
    }

    @Test fun aKeyJustPressedMayLightUp_butNotAnother() {
        val t = build(screen())!!
        val found = CalibratedKeypad.locate(t, screen().px, w, h)!!
        val k = keys[5]
        val lit = screen().apply { fill(k.left, k.top, k.right, k.bottom, rgb(250, 220, 90)) }
        assertTrue(CalibratedKeypad.stillVisible(found, lit.px, w, h, pressed = k))
        assertFalse(CalibratedKeypad.stillVisible(found, lit.px, w, h, pressed = keys[1]))
    }

    @Test fun findUsesOnlyTrustedTemplatesWithAPad() {
        val t = build(screen())!!
        val px = screen().px
        assertNotNull(CalibratedKeypad.find(listOf(t), px, w, h))
        assertNull(CalibratedKeypad.find(listOf(t.copy(state = TemplateState.CANDIDATE)), px, w, h))
        val plain = ManualTemplate.build(question, emptyList(), field, px, w, h, emptyList(), "t0", 1L)!!
        assertNull(CalibratedKeypad.find(listOf(plain), px, w, h))
    }

    /** A thin, low-contrast "1" still makes the field not empty (finer grid, review 26 Oct 2026). */
    @Test fun aThinLowContrastDigitIsSeen() {
        val stamp = FieldStamp.of(screen().px, w, h, field)
        val faint = screen().apply { fill(field.left + 300, field.top + 45, field.left + 304, field.top + 105, rgb(195, 195, 195)) }
        assertEquals(FieldStamp.State.FILLED, FieldStamp.state(stamp, faint.px, w, h, field))
    }

    @Test fun aStampOfTheOldGridIsStillRead() {
        val old = "eb".repeat(16 * 4) // the empty field's lightness (235) in every old cell
        assertTrue(FieldStamp.isValid(old))
        assertEquals(FieldStamp.State.EMPTY, FieldStamp.state(old, screen().px, w, h, field))
        assertEquals(FieldStamp.State.FILLED, FieldStamp.state(old, screen(typed = true).px, w, h, field))
    }

    /** The confirm button may light up once a digit is typed: only the keys are compared then. */
    @Test fun aLitConfirmButtonDoesNotStopTyping() {
        val t = build(screen())!!
        val found = CalibratedKeypad.locate(t, screen().px, w, h)!!
        val lit = screen().apply { fill(submit.left, submit.top, submit.right, submit.bottom, rgb(250, 160, 30)) }
        assertFalse(CalibratedKeypad.stillVisible(found, lit.px, w, h, pressed = null))
        assertTrue(CalibratedKeypad.stillVisible(found, lit.px, w, h, pressed = keys[4], checkSubmit = false))
    }
}
