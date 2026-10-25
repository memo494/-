package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dark field's own gold rim (a 1-2 px line along its inner edge on the owner's phone, 25 Oct
 * 2026) is not typed input: it made every empty field "unknown", so nothing was typed.
 */
class GoldRimFieldTest {
    private val w = 600
    private val h = 200
    private val field = PxRect(100, 50, 500, 150)
    private val dark = 0xff3c3c3c.toInt()
    private val gold = 0xffe0b040.toInt()

    private fun screen(rim: Boolean, digit: Boolean): IntArray {
        val px = IntArray(w * h) { 0xffe8d6b4.toInt() }
        for (y in field.top until field.bottom) for (x in field.left until field.right) px[y * w + x] = dark
        if (rim) {
            for (x in field.left + 40 until field.left + 180) { px[(field.bottom - 14) * w + x] = gold; px[(field.bottom - 13) * w + x] = gold }
            for (y in field.top + 2..field.top + 5) for (x in field.left + 12..field.left + 17) px[y * w + x] = gold
        }
        // A bold "1": 8 px wide, 60 px tall, in the middle.
        if (digit) for (y in field.top + 20 until field.top + 80) for (x in 296 until 304) px[y * w + x] = gold
        return px
    }

    @Test fun anEmptyFieldWithItsRimIsEmpty() {
        assertEquals(NumericFieldInk.State.EMPTY,
            NumericFieldInk.inspect(screen(rim = true, digit = false), w, h, field, NumericFieldReader.Ink.GOLD).state)
    }

    @Test fun aTypedDigitNextToTheRimIsStillSeen() {
        assertEquals(NumericFieldInk.State.FILLED,
            NumericFieldInk.inspect(screen(rim = true, digit = true), w, h, field, NumericFieldReader.Ink.GOLD).state)
    }

    @Test fun aWholeGoldOutlineIsTheFrame_notInput() {
        val px = screen(rim = false, digit = false)
        for (x in field.left + 3 until field.right - 3) { px[(field.top + 3) * w + x] = gold; px[(field.bottom - 4) * w + x] = gold }
        for (y in field.top + 3 until field.bottom - 3) { px[y * w + field.left + 3] = gold; px[y * w + field.right - 4] = gold }
        assertEquals(NumericFieldInk.State.EMPTY,
            NumericFieldInk.inspect(px, w, h, field, NumericFieldReader.Ink.GOLD).state)
        for (y in field.top + 20 until field.top + 80) for (x in 296 until 304) px[y * w + x] = gold
        assertEquals(NumericFieldInk.State.FILLED,
            NumericFieldInk.inspect(px, w, h, field, NumericFieldReader.Ink.GOLD).state)
    }

    @Test fun theRimIsDroppedButADigitIsKept() {
        val px = screen(rim = true, digit = true)
        val mask = BooleanArray(field.width * field.height) { i ->
            NumericFieldReader.isGold(px[(field.top + i / field.width) * w + field.left + i % field.width])
        }
        NumericFieldInk.dropFlatStrokes(mask, field.width, field.height)
        assertEquals(8 * 60, mask.count { it })
    }
}
