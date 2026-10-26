package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One tap on the field or the confirm button gives its whole box. */
class RegionSnapTest {
    private val w = 1200
    private val h = 600
    private val parchment = 0xffe8d6b4.toInt()
    private val wood = 0xff3c3c3c.toInt()
    private val button = 0xffd9b860.toInt()
    private val ink = 0xff4a3010.toInt()

    private fun screen(): IntArray {
        val px = IntArray(w * h) { parchment }
        fun fill(l: Int, t: Int, r: Int, b: Int, c: Int) { for (y in t until b) for (x in l until r) px[y * w + x] = c }
        fill(300, 100, 700, 180, wood)
        fill(300, 220, 700, 300, button)
        fill(440, 240, 560, 280, ink) // the button's word
        return px
    }

    @Test fun aTapOnTheFieldGivesTheField() {
        assertEquals(PxRect(300, 100, 700, 180), RegionSnap.around(screen(), w, h, 500, 140))
    }

    @Test fun aTapOnTheButtonsWordStillGivesTheButton() {
        assertEquals(PxRect(300, 220, 700, 300), RegionSnap.around(screen(), w, h, 500, 260))
    }

    @Test fun aTapOnTheBackgroundGivesNothing() {
        assertNull(RegionSnap.around(screen(), w, h, 100, 500))
    }
}
