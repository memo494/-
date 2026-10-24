package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

/** Exercise discovery AND readiness together, rather than feeding an already known button map. */
class ChoiceDetectionPipelineTest {
    private val width = 1536
    private val height = 691
    private val parchment = 0xffead6b5.toInt()

    private data class Scene(val pixels: IntArray, val buttons: List<PxRect>, val printed: List<PxRect>)

    private fun scene(dx: Int, dy: Int, photo: Boolean = false, waiting: Boolean = false): Scene {
        val pixels = IntArray(width * height) { parchment }
        fun fill(rect: PxRect, color: Int) {
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) pixels[y * width + x] = color
        }
        if (photo) fill(PxRect(120, 230, 495, 535), 0xff285e92.toInt())
        val centers = listOf(917 + dx to 382 + dy, 637 + dx to 382 + dy,
            917 + dx to 466 + dy, 637 + dx to 466 + dy)
        val buttons = centers.map { (x, y) -> PxRect(x - 132, y - 34, x + 132, y + 34) }
        val printed = centers.mapIndexed { index, (x, y) ->
            val halfW = listOf(112, 88, 92, 105)[index]
            PxRect(x - halfW, y - 14, x + halfW, y + 14)
        }
        for (i in buttons.indices) {
            fill(buttons[i], if (waiting) 0xffafa99c.toInt() else 0xff686868.toInt())
            val word = printed[i]
            for (y in word.top until word.bottom) for (x in word.left until word.right) {
                if ((x - word.left) % 13 in 0..3 || y in word.bottom - 6 until word.bottom - 3) {
                    pixels[y * width + x] = if (waiting) 0xffd8d3c7.toInt() else 0xfff1f1f1.toInt()
                }
            }
        }
        return Scene(pixels, buttons, printed)
    }

    @Test fun bothHudPositionsAndPictureQuestionsDiscoverAllFourCompleteWords() {
        for ((dx, dy, photo) in listOf(Triple(31, 24, false), Triple(31, 24, true), Triple(0, -4, false))) {
            val s = scene(dx, dy, photo)
            val found = OptionWordDetector.detectChoices(s.pixels, width, height)
            assertNotNull("dx=$dx dy=$dy photo=$photo", found)
            found!!
            assertEquals(4, found.words.size)
            assertTrue(ChoiceTouchGuard.isReady(s.pixels, width, height, found.buttons, found.words))
            for (i in found.words.indices) {
                val word = found.words[i]
                val printed = s.printed[i]
                assertTrue("left/right letters must not be clipped", word.left <= printed.left && word.right >= printed.right - 4)
                assertTrue("top/bottom dots must not be clipped", word.top <= printed.top && word.bottom >= printed.bottom)
            }
        }
    }

    @Test fun readableWaitingChoicesStillCannotAuthorizeAnyTap() {
        val s = scene(31, 24, photo = true, waiting = true)
        val found = OptionWordDetector.detectChoices(s.pixels, width, height)
        if (found != null) assertFalse(ChoiceTouchGuard.isReady(s.pixels, width, height, found.buttons, found.words))
    }

    @Test fun portraitPhotoViewerNeverProducesAnAutomaticChoiceLayout() {
        assertNull(OptionWordDetector.detectChoices(IntArray(576 * 1280), 576, 1280))
    }
}
