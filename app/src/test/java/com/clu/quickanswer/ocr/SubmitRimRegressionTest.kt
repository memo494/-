package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class SubmitRimRegressionTest {
    private val w = 1280; private val h = 576
    private val button = PxRect(511, 390, 611, 418)
    private val detected = GameVisualLayout.Detected(GameVisualLayout.Kind.NUMERIC_DARK,
        submit = button, questionArea = PxRect(400, 215, 700, 285))
    private fun paint(p: IntArray, r: PxRect, color: Int) {
        for (y in r.top until r.bottom) for (x in r.left until r.right) p[y * w + x] = color
    }

    @Test fun disabledAndEnabledButtonsHaveTheirOwnRim() {
        for (fill in listOf(0xffdfcf93.toInt(), 0xffdda130.toInt())) {
            val p = IntArray(w * h) { 0xffe8d6b4.toInt() }
            paint(p, PxRect(443, 374, 680, 437), 0xff79766f.toInt())
            paint(p, PxRect(449, 380, 674, 431), fill)
            assertTrue(GameVisualLayout.hasSubmitControl(p, w, h, detected))
        }
    }

    @Test fun warmParchmentAndTheInputShadowAreNotAConfirmationButton() {
        val p = IntArray(w * h) { 0xffeed4b1.toInt() }
        paint(p, PxRect(440, 300, 680, 362), 0xff414141.toInt())
        paint(p, PxRect(440, 444, 680, 460), 0xff777777.toInt())
        assertFalse(GameVisualLayout.hasSubmitControl(p, w, h, detected))
        // Even orange paint with no enclosing control is insufficient.
        paint(p, PxRect(420, 350, 710, 455), 0xffdda130.toInt())
        assertFalse(GameVisualLayout.hasSubmitControl(p, w, h, detected))
    }
}
