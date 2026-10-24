package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class GameVisualLayoutTest {
    private val w = 1536
    private val h = 691

    private fun screen(rgb: Int = 0xffe8d6b4.toInt()): IntArray = IntArray(w * h) { rgb }

    private fun paint(
        pixels: IntArray, centerX: Int, centerY: Int,
        offsetsX: List<Int>, offsetsY: List<Int>, rgb: Int
    ) {
        for (dx in offsetsX) for (dy in offsetsY) {
            val x = centerX + dx
            val y = centerY + dy
            for (py in y - 3..y + 3) for (px in x - 3..x + 3) {
                if (px in 0 until w && py in 0 until h) pixels[py * w + px] = rgb
            }
        }
    }

    @Test fun darkGamePadMapsAllTenDigits() {
        val px = screen()
        val rows = listOf(
            listOf(922 to 302, 992 to 302, 1062 to 302),
            listOf(922 to 370, 992 to 370, 1062 to 370),
            listOf(922 to 438, 992 to 438, 1062 to 438),
            listOf(992 to 504),
        ).flatten()
        for ((x, y) in rows) {
            paint(px, x, y, listOf(-20, 20), listOf(-12, 12), 0xff908678.toInt())
        }
        val detected = GameVisualLayout.detect(px, w, h)!!
        assertEquals(GameVisualLayout.Kind.NUMERIC_DARK, detected.kind)
        assertEquals(('0'..'9').toSet(), detected.digits.keys)
        assertTrue(detected.digits['1']!!.centerY < detected.digits['7']!!.centerY)
        assertTrue(detected.digits['0']!!.centerY > detected.digits['7']!!.centerY)
        assertNull(detected.submit)
    }

    @Test fun numericConfirmButtonMustReallyBeVisible() {
        val px = screen()
        val centers = listOf(
            922 to 302, 992 to 302, 1062 to 302,
            922 to 370, 992 to 370, 1062 to 370,
            922 to 438, 992 to 438, 1062 to 438,
            992 to 504
        )
        for ((x, y) in centers) {
            paint(px, x, y, listOf(-20, 20), listOf(-12, 12),
                0xff908678.toInt())
        }
        assertNull(GameVisualLayout.detect(px, w, h)!!.submit)
        for ((x, y) in listOf(600 to 480, 740 to 480, 605 to 495)) {
            paint(px, x, y, listOf(-5, 5), listOf(-3, 3),
                0xffb78537.toInt())
        }
        val visible = GameVisualLayout.detect(px, w, h)!!
        assertNotNull(visible.submit)
        assertTrue(visible.submit!!.centerY in 450f..520f)
    }

    @Test fun whitePadRequiresGreenSubmit() {
        val px = screen(0xff443333.toInt())
        val rows = listOf(
            listOf(550 to 364, 653 to 364, 758 to 364, 862 to 364),
            listOf(550 to 470, 653 to 470, 758 to 470),
            listOf(550 to 569, 653 to 569, 758 to 569),
        ).flatten()
        for ((x, y) in rows) {
            paint(px, x, y, listOf(-26, 26), listOf(-22, 22), 0xfffefefe.toInt())
        }
        assertNull(GameVisualLayout.detect(px, w, h))
        paint(px, 901, 472, listOf(-35, 35), listOf(-23, 23), 0xff20dd30.toInt())
        val found = GameVisualLayout.detect(px, w, h)!!
        assertEquals(GameVisualLayout.Kind.NUMERIC_WHITE, found.kind)
        assertEquals(('0'..'9').toSet(), found.digits.keys)
        assertNotNull(found.submit)
        assertTrue(found.digits['0']!!.centerX > found.digits['9']!!.centerX)
    }

    @Test fun twoByTwoChoicesFollowArabicRightToLeftOrder() {
        val px = screen(0xffe8d6b4.toInt())
        listOf(637 to 382, 917 to 382, 637 to 466, 917 to 466).forEach { (x, y) ->
            paint(px, x, y, listOf(-25, 25), listOf(-13, 13), 0xff666666.toInt())
        }
        val found = GameVisualLayout.detect(px, w, h)!!
        assertEquals(GameVisualLayout.Kind.CHOICE_GRID, found.kind)
        assertEquals(4, found.options.size)
        assertTrue(found.options[0].centerX > found.options[1].centerX)
        assertTrue(found.options[2].centerX > found.options[3].centerX)
        assertTrue(found.options[0].centerY < found.options[2].centerY)
    }

    @Test fun unrelatedFramesAreRejected() {
        assertNull(GameVisualLayout.detect(screen(), w, h))
        assertNull(GameVisualLayout.detect(screen(0xff333333.toInt()), w, h))
        assertNull(GameVisualLayout.detect(IntArray(1000), 1536, 691))
    }
}
