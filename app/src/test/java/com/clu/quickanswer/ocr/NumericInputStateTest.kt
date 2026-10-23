package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class NumericInputStateTest {
    @Test fun darkPlaceholderAndAnimatedBackgroundCannotBecomeGoldEnteredDigits() {
        val w = 200; val h = 80
        val field = PxRect(10, 10, 190, 70)
        val detected = GameVisualLayout.Detected(GameVisualLayout.Kind.NUMERIC_DARK,
            questionArea = PxRect(0, 0, 200, 10), field = field)
        val pixels = IntArray(w * h) { 0xff414141.toInt() }
        fun write(color: Int) {
            for (y in 25 until 55) for (x in 55 until 145) if (x % 12 < 3) pixels[y * w + x] = color
        }
        write(0xff303030.toInt())
        assertFalse(GameVisualLayout.inputHasText(pixels, w, h, detected, field))
        write(0xff777777.toInt())
        assertFalse(GameVisualLayout.inputHasText(pixels, w, h, detected, field))
        write(0xfff5bc28.toInt())
        assertTrue(GameVisualLayout.inputHasText(pixels, w, h, detected, field))
        assertFalse(GameVisualLayout.inputHasText(pixels, w, h, detected, PxRect(-1, 0, 20, 20)))
    }
}
