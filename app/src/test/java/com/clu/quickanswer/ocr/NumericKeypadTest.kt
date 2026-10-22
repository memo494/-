package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class NumericKeypadTest {
    @Test fun syntheticGameKeypadMapsDigitsInTheDisplayedOrder() {
        val field = PxRect(200, 200, 400, 250)
        fun key(col: Int, row: Int) =
            PxRect(180 + col * 55, 270 + row * 60, 225 + col * 55, 315 + row * 60)
        val found = NumericKeypad.detect(
            field,
            (0..4).map { key(it, 0) } +
                (0..2).flatMap { col -> listOf(key(col, 1), key(col, 2)) }
        )
        assertEquals(('0'..'9').toSet(), found.keys)
        assertEquals(key(0, 0), found['7'])
        assertEquals(key(1, 0), found['8'])
        assertEquals(key(2, 0), found['9'])
        assertEquals(key(3, 0), found['0'])
        assertEquals(key(0, 1), found['4'])
        assertEquals(key(1, 1), found['5'])
        assertEquals(key(2, 1), found['6'])
        assertEquals(key(0, 2), found['1'])
        assertEquals(key(1, 2), found['2'])
        assertEquals(key(2, 2), found['3'])
    }

    @Test fun detectsGreenSubmitOnlyWithinKeypadBounds() {
        val keys = NumericKeypad.detect(
            PxRect(200, 200, 400, 250),
            (0..4).map { col -> PxRect(180 + col * 55, 270, 225 + col * 55, 315) } +
                (0..2).flatMap { col ->
                    listOf(
                        PxRect(180 + col * 55, 330, 225 + col * 55, 375),
                        PxRect(180 + col * 55, 390, 225 + col * 55, 435)
                    )
                }
        )
        assertEquals(10, keys.size)
        val width = 600
        val height = 600
        val pixels = IntArray(width * height) { 0xff333333.toInt() }
        // Larger green action button at the right of the number pad.
        for (y in 328 until 436) for (x in 365 until 463) pixels[y * width + x] = 0xff22cc55.toInt()
        val submit = NumericSubmit.detect(pixels, width, height, keys)
        assertNotNull(submit)
        assertTrue(submit!!.centerX > keys['6']!!.centerX)
        assertNull(NumericSubmit.detect(IntArray(width * height), width, height, keys))
    }

    @Test fun incompleteOrScrambledKeypadIsNotGuessed() {
        val field = PxRect(200, 200, 400, 250)
        fun r(col: Int, row: Int) = PxRect(180 + col * 55, 270 + row * 60, 225 + col * 55, 315 + row * 60)
        val rowTop = (0..4).map { r(it, 0) }
        val rowMiddle = (0..2).map { r(it, 1) }
        val rowBottom = (0..2).map { r(it, 2) }
        val good = rowTop + rowMiddle + rowBottom
        assertEquals(10, NumericKeypad.detect(field, good).size)
        assertTrue(NumericKeypad.detect(field, good - rowTop[3]).isEmpty())
        assertTrue(NumericKeypad.detect(field, good - rowBottom[2]).isEmpty())
        assertTrue(NumericKeypad.detect(field, good + r(2, 4)).isEmpty())
    }
}
