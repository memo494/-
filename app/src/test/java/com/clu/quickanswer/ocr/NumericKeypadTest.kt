package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test
import javax.imageio.ImageIO

class NumericKeypadTest {
    @Test fun actualScreenFixtureMapsTheUnusualDigitOrder() {
        val img = ImageIO.read(javaClass.getResource("/ocr/s1.jpg"))
        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        val layout = ColorLayout().analyze(pixels, img.width, img.height)!!
        assertTrue(layout.numeric)
        assertEquals(('0'..'9').toSet(), layout.digitKeys.keys)
        assertNotNull("game screenshot has a large green submit button", layout.numericSubmit)
        val pad = layout.digitKeys
        assertTrue(pad['7']!!.centerY < pad['4']!!.centerY)
        assertTrue(pad['4']!!.centerY < pad['1']!!.centerY)
        assertTrue(pad['9']!!.centerX < pad['0']!!.centerX)
        assertEquals(pad['7']!!.centerX, pad['4']!!.centerX)
        assertEquals(pad['4']!!.centerX, pad['1']!!.centerX)
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
