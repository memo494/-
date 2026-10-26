package com.clu.quickanswer.ocr

import com.clu.quickanswer.ocr.KeypadFinder.Glyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "اكتشاف المفاتيح تلقائيًا": the ten keys from the digits OCR read on a capture. */
class KeypadFinderTest {
    private val w = 2576
    private val h = 1159

    /** A digit 40 px high centred at ([x], [y]). */
    private fun g(text: String, x: Int, y: Int, height: Int = 40) = Glyph(text, PxRect(x - 10, y - height / 2, x + 10, y + height / 2))

    /** 1 2 3 / 4 5 6 / 7 8 9 / C 0 ⌫ with keys 116 px apart, like the parchment game. */
    private val phonePad: List<Glyph> = buildList {
        val labels = listOf("123", "456", "789")
        for ((row, line) in labels.withIndex()) for ((col, c) in line.withIndex()) add(g(c.toString(), 1495 + col * 116, 457 + row * 114))
        add(g("C", 1495, 799)); add(g("0", 1611, 799))
    }
    private fun centre(r: PxRect) = r.centerX.toInt() to r.centerY.toInt()

    @Test fun findsEveryKeyOfAPhoneStylePad() {
        val keys = KeypadFinder.find(phonePad, w, h)!!
        assertEquals(10, keys.size)
        assertEquals(1495 to 457, centre(keys[1]))
        assertEquals(1727 to 685, centre(keys[9]))
        assertEquals(1611 to 799, centre(keys[0]))
        // A square around the digit, bigger than the digit, still on the key.
        assertTrue(keys[5].height in 60..70)
    }

    @Test fun findsACalculatorOrderAndARowOfTen() {
        val calc = buildList {
            for ((row, line) in listOf("789", "456", "123").withIndex()) for ((col, c) in line.withIndex()) add(g(c.toString(), 400 + col * 100, 300 + row * 100))
            add(g("0", 500, 600))
        }
        assertEquals(400 to 300, centre(KeypadFinder.find(calc, w, h)!![7]))
        val row = (0..9).map { g(((it + 1) % 10).toString(), 300 + it * 100, 900) }
        assertEquals(1200 to 900, centre(KeypadFinder.find(row, w, h)!![0]))
    }

    @Test fun otherDigitsOnTheScreenAreIgnored() {
        // A score "0 / 4" on the left, power-up counters "6" and "7" on the right, a "0000" hint.
        val noisy = phonePad + listOf(g("0", 230, 245, 36), g("4", 300, 245, 36), g("6", 2160, 530, 30), g("7", 2160, 870, 30),
            Glyph("0000", PxRect(960, 590, 1180, 650)))
        assertNotNull(KeypadFinder.find(noisy, w, h))
    }

    @Test fun runsOfDigitsReadAsOneWordAreSplit() {
        val merged = listOf(
            Glyph("123", PxRect(400, 280, 640, 320)), Glyph("456", PxRect(400, 380, 640, 420)),
            Glyph("789", PxRect(400, 480, 640, 520)), g("0", 520, 600),
        )
        val keys = KeypadFinder.find(merged, w, h)!!
        assertEquals(440 to 300, centre(keys[1]))
        assertEquals(600 to 500, centre(keys[9]))
    }

    @Test fun arabicIndicDigitsCount() {
        val arabic = phonePad.map { glyph -> glyph.copy(text = glyph.text.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")) }
        assertNotNull(KeypadFinder.find(arabic, w, h))
    }

    @Test fun declinesAMissingKey_aDuplicate_orTwoPads() {
        assertNull(KeypadFinder.find(phonePad.filterNot { it.text == "5" }, w, h))
        assertNull(KeypadFinder.find(phonePad + g("5", 1611 + 116, 799), w, h))
        val second = phonePad.map { it.copy(box = PxRect(it.box.left - 1200, it.box.top, it.box.right - 1200, it.box.bottom)) }
        assertNull(KeypadFinder.find(phonePad + second, w, h))
        assertNull(KeypadFinder.find(emptyList(), w, h))
    }

    @Test fun aPartialPadGivesTheDigitsThatWereRead() {
        val some = KeypadFinder.partial(phonePad.filterNot { it.text == "5" || it.text == "0" }, w, h)!!
        assertNull(some[5])
        assertNull(some[0])
        assertEquals(1495 to 457, centre(some[1]!!))
        assertNull(KeypadFinder.partial(phonePad.take(2), w, h))
    }

    @Test fun aTapOnAKeySetsTheDigitPrintedOnIt() {
        val (digit, key) = KeypadFinder.digitAt(phonePad, w, h, 1611 + 20, 571 - 15)!!
        assertEquals(5, digit)
        assertEquals(1611 to 571, centre(key))
        assertNull(KeypadFinder.digitAt(phonePad, w, h, 300, 300))
    }
}
