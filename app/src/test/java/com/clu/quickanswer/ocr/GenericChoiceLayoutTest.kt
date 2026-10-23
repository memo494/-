package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class GenericChoiceLayoutTest {
    private class Scene(val width: Int, val height: Int, val count: Int, portrait: Boolean) {
        val pixels = IntArray(width * height) { 0xffb7a58d.toInt() }
        val question = PxRect(60, 40, width - 60, if (portrait) 220 else 180)
        val buttons = (0 until count).map { i ->
            if (portrait) PxRect(80, 330 + i * 1000 / (count - 1), width - 80, 425 + i * 1000 / (count - 1))
            else PxRect(90 + i % 2 * 550, 240 + i / 2 * 105, 540 + i % 2 * 550, 325 + i / 2 * 105)
        }
        val words = buttons.map { b -> PxRect(b.left + 75, b.top + 30, b.right - 75, b.top + 59) }

        fun fill(r: PxRect, color: Int) {
            for (y in r.top until r.bottom) for (x in r.left until r.right) pixels[y * width + x] = color
        }

        fun draw(background: Int, foreground: Int) {
            for (i in buttons.indices) {
                fill(buttons[i], background)
                val r = words[i]
                for (y in r.top until r.bottom) for (x in r.left until r.right) {
                    if ((x - r.left) % 12 < 3 || y >= r.bottom - 3) pixels[y * width + x] = foreground
                }
            }
        }

        fun ready() = ChoiceTouchGuard.isReadyFromLayout(pixels, width, height, question, buttons, words)
    }

    @Test fun trustedChoiceRegionsSupportTwoToEightWithoutGameCoordinatesOrAPaletteName() {
        for (n in 2..8) for (portrait in listOf(false, true)) {
            val s = if (portrait) Scene(720, 1440, n, true) else Scene(1200, 720, n, false)
            for ((background, foreground) in listOf(0xff2d4b88.toInt() to 0xfff5f5f5.toInt(),
                    0xffe6ddff.toInt() to 0xff303030.toInt())) {
                s.draw(background, foreground)
                assertTrue("n=$n portrait=$portrait", s.ready())
            }
        }
    }

    @Test fun waitingGreenResultsBlankButtonsAndCoveredWordsDoNotAuthorizeTaps() {
        val s = Scene(1200, 720, 4, false)
        s.draw(0xff777777.toInt(), 0xff999999.toInt())
        assertFalse(s.ready())
        s.draw(0xff22c55e.toInt(), 0xffeeeeee.toInt())
        assertFalse(s.ready())
        s.draw(0xff2d4b88.toInt(), 0xfff5f5f5.toInt())
        s.fill(s.words[2], 0xff2d4b88.toInt())
        assertFalse(s.ready())
    }

    @Test fun anEmbeddedLandscapeImageInsideAPortraitViewerIsDeclined() {
        val s = Scene(1200, 1800, 4, false)
        s.draw(0xff606060.toInt(), 0xffeeeeee.toInt())
        assertFalse(s.ready())
    }

    @Test fun allOptionsNeedIndependentNonOverlappingVisibleGeometry() {
        val s = Scene(1200, 720, 4, false)
        s.draw(0xff606060.toInt(), 0xffeeeeee.toInt())
        assertTrue(s.ready())
        assertFalse(ChoiceTouchGuard.isReadyFromLayout(s.pixels, s.width, s.height, s.question,
            listOf(s.buttons[0], s.buttons[0]) + s.buttons.drop(2), s.words))
        assertFalse(ChoiceTouchGuard.isReadyFromLayout(s.pixels, s.width, s.height, s.question,
            s.buttons, s.words.dropLast(1)))
    }
}
