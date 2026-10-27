package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ScreenImage.contentRows]: which rows of a letterboxed capture are kept. It works on the small
 * lit/dark map, so no Bitmap is needed.
 */
class ScreenImageRowsTest {

    // A 1080 x 1800 capture sampled 120 cells wide is 120 x 200 cells: one map row is 9 source rows,
    // and the margin (srcHeight / 50) is 36 source rows. A band is kept when it is 40..180 map rows.
    private val w = 120
    private val h = 200
    private val srcHeight = 1800

    /**
     * A [w] x [height] map where each of [bands] (map rows) has its first [cells] cells lit; the rest
     * is dark.
     */
    private fun map(vararg bands: IntRange, cells: Int = w, height: Int = h): BooleanArray {
        val lit = BooleanArray(w * height)
        for (band in bands) for (y in band) for (x in 0 until cells) lit[y * w + x] = true
        return lit
    }

    private fun rows(lit: BooleanArray) = ScreenImage.contentRows(lit, w, h, srcHeight)

    @Test
    fun letterboxedMap_keepsTheLitBand_withAMargin_inSourceRows() {
        // Black bars above (rows 0..49) and below (150..199): the band is 50 until 150 on the map,
        // 450 until 1350 in the capture, plus 36 rows each side.
        assertEquals(414 to 1386, rows(map(50 until 150)))
    }

    @Test
    fun scalesByTheSourceHeight_evenWhenItIsNotAMultipleOfTheMap() {
        // 1080 x 1920 sampled at 120 wide: 213 map rows, so a map row is 1920/213 = 9.014 source rows.
        // Band 40 until 170: 360.56 -> 360 and 1532.39 -> 1532, margin 1920 / 50 = 38.
        assertEquals(322 to 1570, ScreenImage.contentRows(map(40 until 170, height = 213), w, 213, 1920))
    }

    @Test
    fun noBars_keepsTheWholeImage() {
        assertNull(rows(map(0 until h)))
        // Nothing lit at all: no band either.
        assertNull(rows(map()))
    }

    @Test
    fun aMapOfFewerThanFiveRows_withNothingLit_stillHasNoBand() {
        // There h / 5 is 0, so the size checks alone would not say "keep the whole image".
        assertNull(ScreenImage.contentRows(BooleanArray(w * 4), w, 4, 400))
    }

    @Test
    fun aBandOfMoreThanNineTenths_isNotWorthCutting() {
        // 9 * 200 / 10 = 180 rows is still cut, one more is not.
        assertEquals(0 to 1656, rows(map(0 until 180)))
        assertNull(rows(map(0 until 181)))
        assertNull(rows(map(10 until 191)))
    }

    @Test
    fun aTinyBand_keepsTheWholeImage() {
        // A lit stripe (a progress bar, a banner) in a dark screen is not the content.
        assertNull(rows(map(90 until 100)))
        // The smallest band is a fifth of the map: 200 / 5 = 40 rows is kept, 39 is not.
        assertNull(rows(map(80 until 119)))
        assertEquals(684 to 1116, rows(map(80 until 120)))
    }

    @Test
    fun bandClampsAtTheTopAndBottomOfTheSource() {
        // The margin would reach above row 0 / below the last row.
        assertEquals(0 to 936, rows(map(0 until 100)))
        assertEquals(864 to srcHeight, rows(map(100 until 200)))
    }

    @Test
    fun theTallestBandWins_andTheFirstOfEqualBandsWins() {
        // A caption strip (30 rows) above the real content (90 rows), and the other way round.
        assertEquals(684 to 1566, rows(map(10 until 40, 80 until 170)))
        assertEquals(54 to 936, rows(map(10 until 100, 120 until 150)))
        // Same height: the first one found stays.
        assertEquals(144 to 756, rows(map(20 until 80, 120 until 180)))
    }

    @Test
    fun aRowIsContentOnlyWhenMoreThanHalfItsCellsAreLit() {
        assertNull(rows(map(50 until 150, cells = w / 2)))
        assertEquals(414 to 1386, rows(map(50 until 150, cells = w / 2 + 1)))
    }

    /**
     * The black-capture check now runs on the same map as the crop (one scan of the capture per
     * request instead of two): under one lit cell in fifty is "black", as before.
     */
    @Test fun aCaptureIsBlackBelowOneLitCellInFifty() {
        val lit = BooleanArray(w * h)
        for (i in 0 until lit.size / 50 - 1) lit[i] = true
        assertEquals(true, ScreenImage.isMostlyDark(lit))
        lit[lit.size / 50 - 1] = true
        assertEquals(false, ScreenImage.isMostlyDark(lit))
    }
}
