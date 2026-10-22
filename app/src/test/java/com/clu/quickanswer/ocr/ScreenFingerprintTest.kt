package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFingerprintTest {

    /** The 24x12 luma image of [ref] blown up to [w] x [h] by nearest neighbour. */
    private fun upscaled(ref: UserScreenshotFingerprints.Ref, w: Int, h: Int): IntArray {
        val small = ref.pixels()
        return IntArray(w * h) { small[(it / w * ScreenFingerprint.ROWS / h) * ScreenFingerprint.COLS + (it % w * ScreenFingerprint.COLS / w)] }
    }

    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun syntheticImageGivesTheExpectedString() {
        val w = 240
        val h = 120
        // Cell (c, r) holds level (c + r) % 16.
        val img = IntArray(w * h) { gray((((it % w) / 10 + (it / w) / 10) % 16) * 17) }
        val expected = buildString {
            for (r in 0 until 12) for (c in 0 until 24) append(Integer.toHexString((c + r) % 16))
        }
        assertEquals(expected, ScreenFingerprint.of(img, w, h))
        assertEquals(288, expected.length)
    }

    @Test
    fun colourIsConvertedToLumaAndRounded() {
        // Pure green: luma 587/1000 * 255 = 149.7 -> 149.7 / 17 = 8.8 -> 9.
        val img = IntArray(48 * 24) { 0xFF00FF00.toInt() }
        assertEquals("9".repeat(288), ScreenFingerprint.of(img, 48, 24))
        // Pure blue: 114/1000 * 255 = 29 -> 1.7 -> 2.
        assertEquals("2".repeat(288), ScreenFingerprint.of(IntArray(48 * 24) { 0xFF0000FF.toInt() }, 48, 24))
    }

    @Test
    fun resolutionDoesNotChangeTheFingerprint() {
        for (ref in UserScreenshotFingerprints.references) {
            assertEquals(ref.filename, ref.luma16, ScreenFingerprint.of(ref.pixels(), 24, 12))
            assertEquals(ref.filename, ref.luma16, ScreenFingerprint.of(upscaled(ref, 1080, 2400), 1080, 2400))
        }
    }

    @Test
    fun maskedCellsAreUnknown() {
        val w = 480
        val h = 240 // cells of 20 x 20 px
        val img = IntArray(w * h) { gray(136) }
        // Covers cell (0,0) completely, 20 % of cell (1,0) and 40 % of cell (2,0).
        val masks = listOf(PxRect(0, 0, 20, 20), PxRect(20, 0, 24, 20), PxRect(40, 0, 48, 20))
        val fp = ScreenFingerprint.of(img, w, h, masks)
        assertEquals('.', fp[0])
        assertEquals('8', fp[1])
        assertEquals('.', fp[2])
        assertEquals(2, fp.count { it == '.' })
    }

    @Test
    fun overlappingMasksAreNotCountedTwice() {
        val w = 480
        val h = 240
        val img = IntArray(w * h) { gray(136) }
        // Two masks over the same 20 % of cell (0,0): still 20 %, so the cell is known.
        val masks = listOf(PxRect(0, 0, 4, 20), PxRect(0, 0, 4, 20))
        assertEquals(0, ScreenFingerprint.of(img, w, h, masks).count { it == '.' })
    }

    @Test
    fun distanceIgnoresUnknownCells() {
        val a = "0".repeat(144) + "5".repeat(144)
        val b = "2".repeat(144) + "5".repeat(144)
        assertEquals(1.0, ScreenFingerprint.distance(a, b), 1e-9)
        // Unknown in one of them: those cells are left out.
        val c = ".".repeat(144) + "7".repeat(144)
        assertEquals(2.0, ScreenFingerprint.distance(a, c), 1e-9)
        assertEquals(0.0, ScreenFingerprint.distance(a, a), 0.0)
    }

    @Test
    fun distanceIsInfiniteWithTooLittleToCompare() {
        val a = "3".repeat(288)
        assertEquals(Double.POSITIVE_INFINITY, ScreenFingerprint.distance(a, "3".repeat(287)), 0.0)
        // Known in both: 143 of 288 cells, fewer than half.
        val b = ".".repeat(145) + "3".repeat(143)
        assertEquals(Double.POSITIVE_INFINITY, ScreenFingerprint.distance(a, b), 0.0)
        // Exactly half is enough.
        val c = ".".repeat(144) + "3".repeat(144)
        assertEquals(0.0, ScreenFingerprint.distance(a, c), 0.0)
    }

    private fun refs(prefix: String) = UserScreenshotFingerprints.references.filter { it.type.startsWith(prefix) }

    private fun d(a: UserScreenshotFingerprints.Ref, b: UserScreenshotFingerprints.Ref) =
        ScreenFingerprint.distance(a.luma16, b.luma16)

    private fun pairs(xs: List<UserScreenshotFingerprints.Ref>) =
        xs.flatMapIndexed { i, a -> xs.drop(i + 1).map { a to it } }

    @Test
    fun realScreensOfOneGameAreTheSameGame() {
        val darkNumeric = refs("dark_numeric")
        val whiteNumeric = refs("white_numeric")
        val darkGame = refs("dark")
        assertEquals(2, darkNumeric.size)
        assertEquals(2, whiteNumeric.size)
        assertEquals(5, darkGame.size)
        for (group in listOf(darkNumeric, whiteNumeric, darkGame)) {
            for ((a, b) in pairs(group)) {
                assertTrue("${a.filename} ${b.filename} ${d(a, b)}", d(a, b) <= ScreenFingerprint.SAME_GAME_MAX)
            }
        }
    }

    @Test
    fun realScreensOfDifferentGamesAreNot() {
        val dark = refs("dark")
        val white = refs("white")
        val gallery = refs("gallery")
        assertEquals(3, white.size)
        assertEquals(1, gallery.size)
        for (a in dark) for (b in white) {
            assertTrue("${a.filename} ${b.filename} ${d(a, b)}", d(a, b) > ScreenFingerprint.SAME_GAME_MAX)
        }
        for (a in dark + white) for (g in gallery) {
            assertTrue("${a.filename} ${g.filename} ${d(a, g)}", d(a, g) > ScreenFingerprint.SAME_GAME_MAX)
        }
    }

    @Test
    fun realWhiteNumericAndWhiteVerticalAreDifferentGames() {
        // The closest pair of different games (2.06 / 2.15): the margin to SAME_GAME_MAX is thin.
        for (a in refs("white_numeric")) for (b in refs("white_vertical")) {
            assertTrue("${a.filename} ${b.filename} ${d(a, b)}", d(a, b) > ScreenFingerprint.SAME_GAME_MAX)
        }
    }
}
