package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Synthetic approximation of the reported 1280x576 gray pad, without player screenshots. */
class GrayNumericKeypadRegressionTest {
    private val centers = listOf(
        '1' to (770 to 250), '2' to (828 to 250), '3' to (885 to 250),
        '4' to (770 to 307), '5' to (828 to 307), '6' to (885 to 307),
        '7' to (770 to 363), '8' to (828 to 363), '9' to (885 to 363),
        '0' to (828 to 419),
    )

    private class Scene(val width: Int = 1280, val height: Int = 576) {
        val pixels = IntArray(width * height) { PARCHMENT }

        fun rect(left: Int, top: Int, right: Int, bottom: Int) = PxRect(
            left * width / 1280, top * height / 576,
            right * width / 1280, bottom * height / 576,
        )

        fun fill(rect: PxRect, color: Int) {
            for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) pixels[y * width + x] = color
        }

        fun key(cx: Int, cy: Int, warmth: Int = 0, light: Int = 120) {
            fill(rect(cx - 27, cy - 25, cx + 27, cy + 25), 0xff414141.toInt())
            val body = rect(cx - 23, cy - 21, cx + 23, cy + 21)
            for (y in body.top until body.bottom) for (x in body.left until body.right) {
                // A bevel-lit gradient with neutral or barely warm corners.
                val value = light + (x - body.left) * 30 / body.width + (y - body.top) * 10 / body.height
                val r = value + warmth
                val g = value
                val b = value - warmth
                pixels[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        fun confirm(color: Int) {
            fill(rect(443, 374, 680, 437), 0xff79766f.toInt())
            fill(rect(449, 380, 674, 431), color)
        }

        fun detect() = GameVisualLayout.detect(pixels, width, height)

        companion object {
            const val PARCHMENT = -1517899 // 0xffe8d6b5
        }
    }

    private fun scene(width: Int = 1280, height: Int = 576, warmth: Int = 0, light: Int = 120): Scene {
        val scene = Scene(width, height)
        for ((_, xy) in centers) scene.key(xy.first, xy.second, warmth, light)
        scene.key(770, 419, warmth, light)
        return scene
    }

    @Test fun neutralGrayGradientAndDarkBevelsMapEveryDigitAndTheClearKey() {
        val scene = scene()
        val found = scene.detect()!!
        assertEquals(GameVisualLayout.Kind.NUMERIC_DARK, found.kind)
        assertEquals(('0'..'9').toSet(), found.digits.keys)
        assertNotNull(found.clear)
        assertNull(found.backspace)
        assertNull(found.submit)
        for ((digit, xy) in centers) {
            assertTrue(abs(found.digits.getValue(digit).centerX - xy.first) <= 4)
            assertTrue(abs(found.digits.getValue(digit).centerY - xy.second) <= 4)
        }
        assertTrue(abs(found.clear!!.centerX - 770) <= 4)
        assertTrue(abs(found.clear.centerY - 419) <= 4)
    }

    @Test fun nearlyNeutralWarmthAndBrightnessWorkAtBothCaptureSizes() {
        for (warmth in listOf(0, 1, 4)) for (light in listOf(90, 120, 155)) {
            for ((width, height) in listOf(1280 to 576, 1920 to 864)) {
                val found = scene(width, height, warmth, light).detect()
                assertNotNull("warmth=$warmth light=$light size=${width}x$height", found)
                assertEquals(GameVisualLayout.Kind.NUMERIC_DARK, found!!.kind)
                assertNotNull(found.clear)
                assertEquals(10, found.digits.size)
            }
        }
    }

    @Test fun eachDigitAndTheResetNeedTheirOwnVisibleKeySurface() {
        for ((digit, xy) in centers) {
            val scene = scene()
            scene.fill(scene.rect(xy.first - 27, xy.second - 25, xy.first + 27, xy.second + 25), Scene.PARCHMENT)
            assertNull("missing $digit", scene.detect())
        }
        val noReset = scene()
        noReset.fill(noReset.rect(743, 394, 797, 444), Scene.PARCHMENT)
        assertNull(noReset.detect()!!.clear)
    }

    @Test fun paleDisabledAndEnabledOrangeConfirmationKeepTheSameCenter() {
        val scene = scene()
        scene.confirm(0xffdfcf93.toInt())
        val before = scene.detect()!!
        assertNotNull(before.submit)
        assertTrue(abs(before.submit!!.centerX - 561) < 2)
        assertTrue(abs(before.submit.centerY - 405) < 2)
        scene.confirm(0xffdda130.toInt())
        val after = scene.detect()!!
        assertEquals(before.submit, after.submit)
        assertTrue(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, before))
        scene.fill(scene.rect(443, 374, 680, 437), Scene.PARCHMENT)
        assertNull(scene.detect()!!.submit)
        assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, before))
    }

    @Test fun parchmentWithoutAControlAndFlatGrayPanelsAreRejected() {
        val scene = scene()
        assertNull(scene.detect()!!.submit)
        // A broad flat panel has the right color at every corner, but none of
        // the middle keys has its own boundary.
        scene.fill(scene.rect(733, 218, 917, 452), 0xff929292.toInt())
        assertNull(scene.detect())
        assertNull(GameVisualLayout.detect(IntArray(1280 * 576) { 0xff929292.toInt() }, 1280, 576))
    }

    @Test fun aFlatGoldPanelWithoutASeparateControlDoesNotBecomeSubmit() {
        val scene = scene()
        scene.fill(scene.rect(420, 350, 710, 455), 0xffb78537.toInt())
        assertNull(scene.detect()!!.submit)
    }

    @Test fun aPressedClearMayAnimateButAnotherMissingDigitStillStopsEntry() {
        val scene = scene()
        scene.confirm(0xffdfcf93.toInt())
        val expected = scene.detect()!!
        scene.fill(scene.rect(743, 394, 797, 444), 0xff141414.toInt())
        assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected))
        assertTrue(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected, expected.clear))
        scene.fill(scene.rect(858, 282, 912, 332), Scene.PARCHMENT)
        assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected, expected.clear))
    }
}
