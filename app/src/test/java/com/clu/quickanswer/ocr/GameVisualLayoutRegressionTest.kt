package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Synthetic surfaces only: no real player screenshots belong in this public repository. */
class GameVisualLayoutRegressionTest {
    private val dark = listOf(
        '1' to (922 to 302), '2' to (992 to 302), '3' to (1062 to 302),
        '4' to (922 to 370), '5' to (992 to 370), '6' to (1062 to 370),
        '7' to (922 to 438), '8' to (992 to 438), '9' to (1062 to 438),
        '0' to (992 to 504),
    )
    private val white = listOf(
        '7' to (550 to 364), '8' to (653 to 364), '9' to (758 to 364), '0' to (862 to 364),
        '4' to (550 to 470), '5' to (653 to 470), '6' to (758 to 470),
        '1' to (550 to 569), '2' to (653 to 569), '3' to (758 to 569),
    )

    private class Scene(val width: Int, val height: Int, background: Int) {
        val pixels = IntArray(width * height) { background }

        fun box(x: Int, y: Int, halfW: Int, halfH: Int, color: Int): PxRect {
            val rect = PxRect(
                ((x - halfW) * width / 1536f).toInt(),
                ((y - halfH) * height / 691f).toInt(),
                ((x + halfW) * width / 1536f).toInt(),
                ((y + halfH) * height / 691f).toInt(),
            )
            for (py in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(height)) {
                for (px in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(width)) {
                    pixels[py * width + px] = color
                }
            }
            return rect
        }

        fun detect() = GameVisualLayout.detect(pixels, width, height)
    }

    private fun darkScene(width: Int = 1536, height: Int = 691, missing: Char? = null): Scene {
        val scene = Scene(width, height, 0xffe8d6b4.toInt())
        for ((digit, xy) in dark) if (digit != missing) {
            scene.box(xy.first, xy.second, 30, 26, 0xff908678.toInt())
        }
        return scene
    }

    private fun whiteScene(width: Int = 1536, height: Int = 691, missing: Char? = null): Scene {
        val scene = Scene(width, height, 0xff443333.toInt())
        for ((digit, xy) in white) if (digit != missing) {
            scene.box(xy.first, xy.second, 47, 45, 0xfffefefe.toInt())
        }
        scene.box(904, 518, 72, 82, 0xff20dd30.toInt())
        return scene
    }

    @Test fun oneMissingDigitRejectsEitherNumberPad() {
        for (missing in '0'..'9') {
            assertNull("dark pad without $missing", darkScene(missing = missing).detect())
            assertNull("white pad without $missing", whiteScene(missing = missing).detect())
        }
    }

    @Test fun uniformKeyColorsAreNotMistakenForAKeypad() {
        assertNull(Scene(1536, 691, 0xff908678.toInt()).detect())
        val whitePanel = Scene(1536, 691, 0xfffefefe.toInt())
        whitePanel.box(904, 518, 72, 82, 0xff20dd30.toInt())
        assertNull(whitePanel.detect())
    }

    @Test fun resetButtonsAreAvailableOnlyWhenTheirSurfacesAreVisible() {
        val darkPad = darkScene()
        assertNull(darkPad.detect()!!.clear)
        val clear = darkPad.box(922, 504, 30, 26, 0xff908678.toInt())
        val detectedDark = darkPad.detect()!!
        assertInside(clear, detectedDark.clear!!)
        assertNull(detectedDark.backspace)

        val whitePad = whiteScene()
        assertNull(whitePad.detect()!!.backspace)
        val erase = whitePad.box(966, 364, 47, 45, 0xffa5d4e8.toInt())
        val detectedWhite = whitePad.detect()!!
        assertInside(erase, detectedWhite.backspace!!)
        assertNull(detectedWhite.clear)
    }

    @Test fun phoneAndLargerCaptureSizesKeepAllDigitCentersInsideTheirKeys() {
        for ((width, height) in listOf(1280 to 576, 1536 to 691, 1920 to 864)) {
            for ((scene, keys, kind) in listOf(
                Triple(darkScene(width, height), dark, GameVisualLayout.Kind.NUMERIC_DARK),
                Triple(whiteScene(width, height), white, GameVisualLayout.Kind.NUMERIC_WHITE),
            )) {
                val found = scene.detect()!!
                assertEquals(kind, found.kind)
                assertEquals(('0'..'9').toSet(), found.digits.keys)
                for ((digit, xy) in keys) {
                    val halfW = if (kind == GameVisualLayout.Kind.NUMERIC_DARK) 30 else 47
                    val halfH = if (kind == GameVisualLayout.Kind.NUMERIC_DARK) 26 else 45
                    val key = PxRect(
                        ((xy.first - halfW) * width / 1536f).toInt(),
                        ((xy.second - halfH) * height / 691f).toInt(),
                        ((xy.first + halfW) * width / 1536f).toInt(),
                        ((xy.second + halfH) * height / 691f).toInt(),
                    )
                    assertInside(key, found.digits.getValue(digit))
                    assertTrue(abs(found.digits.getValue(digit).centerX - xy.first * width / 1536f) < 1f)
                    assertTrue(abs(found.digits.getValue(digit).centerY - xy.second * height / 691f) < 1f)
                }
            }
        }
    }

    @Test fun translatedKeypadKeepsTheCorrectRowsAndResetPositions() {
        val scene = Scene(1536, 691, 0xffe8d6b4.toInt())
        for ((_, xy) in dark) scene.box(xy.first + 6, xy.second - 6, 30, 26, 0xff908678.toInt())
        val clear = scene.box(928, 498, 30, 26, 0xff908678.toInt())
        val found = scene.detect()!!
        assertEquals(GameVisualLayout.Kind.NUMERIC_DARK, found.kind)
        assertInside(clear, found.clear!!)
        assertTrue(found.digits.getValue('1').centerY < found.digits.getValue('4').centerY)
        assertTrue(found.digits.getValue('4').centerY < found.digits.getValue('7').centerY)
        assertTrue(found.digits.getValue('7').centerY < found.digits.getValue('0').centerY)
    }

    @Test fun translatedWhitePadKeepsZeroAndEraseInTheTopRow() {
        val scene = Scene(1280, 576, 0xff443333.toInt())
        for ((_, xy) in white) scene.box(xy.first + 6, xy.second - 6, 47, 45, 0xfffefefe.toInt())
        scene.box(910, 512, 72, 82, 0xff20dd30.toInt())
        val erase = scene.box(972, 358, 47, 45, 0xffa5d4e8.toInt())
        val found = scene.detect()!!
        assertEquals(GameVisualLayout.Kind.NUMERIC_WHITE, found.kind)
        assertInside(erase, found.backspace!!)
        assertNotNull(found.submit)
        assertTrue(found.digits.getValue('0').centerX > found.digits.getValue('9').centerX)
        assertTrue(found.digits.getValue('0').centerY < found.digits.getValue('6').centerY)
    }

    @Test fun truncatedAndInvalidDimensionsNeverAuthorizeCoordinates() {
        assertNull(GameVisualLayout.detect(IntArray(1536 * 691 - 1), 1536, 691))
        assertNull(GameVisualLayout.detect(IntArray(0), Int.MAX_VALUE, Int.MAX_VALUE / 2))
        assertNull(GameVisualLayout.detect(IntArray(0), 0, 0))
        assertNull(GameVisualLayout.detect(IntArray(0), 1536, -1))
        assertNull(GameVisualLayout.detect(IntArray(1000), 10, 100))
    }

    @Test fun onlyThePreviouslyPressedDigitMayKeepItsHighlightBetweenTaps() {
        for ((scene, centers) in listOf(darkScene() to dark, whiteScene() to white)) {
            val expected = scene.detect()!!
            val (digit, xy) = centers[1]
            scene.box(xy.first, xy.second, 30, 26, 0xff101010.toInt())
            assertNull(scene.detect())
            assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected))
            assertTrue(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height,
                expected, expected.digits.getValue(digit)))
            assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height,
                expected, expected.digits.getValue('0')))
            val (_, other) = centers[2]
            scene.box(other.first, other.second, 30, 26, 0xff101010.toInt())
            assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height,
                expected, expected.digits.getValue(digit)))
        }
    }

    @Test fun confirmationAndResetControlsMustRemainVisibleDuringEntry() {
        val scene = whiteScene()
        scene.box(966, 364, 47, 45, 0xffa5d4e8.toInt())
        val expected = scene.detect()!!
        assertTrue(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected))
        scene.box(966, 364, 47, 45, 0xff101010.toInt())
        assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected))
        assertTrue(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected, expected.backspace))
        scene.box(904, 518, 72, 82, 0xff443333.toInt())
        assertFalse(GameVisualLayout.stillVisible(scene.pixels, scene.width, scene.height, expected, expected.backspace))
    }

    private fun assertInside(key: PxRect, target: PxRect) {
        assertTrue("x=${target.centerX} must be in $key", target.centerX >= key.left && target.centerX < key.right)
        assertTrue("y=${target.centerY} must be in $key", target.centerY >= key.top && target.centerY < key.bottom)
    }
}
