package com.clu.quickanswer.ocr

/** A drawable ARGB test image, plus a synthetic quiz screen, so the layout and reader can be tested without photos. */
class Canvas(val w: Int, val h: Int, bg: Int) {
    val px = IntArray(w * h) { bg }

    fun fill(l: Int, t: Int, r: Int, b: Int, c: Int) {
        for (y in t until b) for (x in l until r) px[y * w + x] = c
    }

    /**
     * "Text": dark ink whose level varies a little from column to column, like anti-aliased
     * glyphs. (Otsu on a perfectly two-level image puts its threshold ON the dark level, so
     * nothing would be darker than it.)
     */
    fun ink(l: Int, t: Int, r: Int, b: Int) {
        for (y in t until b) for (x in l until r) {
            val v = 16 + x % 16
            px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val CREAM = 0xFFFAEEC8.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLUE = 0xFF405080.toInt()
    }
}

object SyntheticScreens {

    /**
     * Portrait screen: black bars, cream banner with two text lines, four white buttons with one
     * line each. A button listed in [emptyButtons] gets no text line.
     */
    fun quiz(w: Int, h: Int, emptyButtons: Set<Int> = emptySet()): Canvas {
        val c = Canvas(w, h, Canvas.BLACK)
        c.fill(0, h / 10, w, h * 9 / 10, Canvas.BLUE) // content area between the letterbox bars
        c.fill(w / 10, h * 40 / 100, w * 9 / 10, h * 52 / 100, Canvas.CREAM) // banner
        c.ink(w * 30 / 100, h * 4350 / 10000, w * 70 / 100, h * 4600 / 10000) // question line 1
        c.ink(w * 40 / 100, h * 4750 / 10000, w * 60 / 100, h * 5000 / 10000) // question line 2
        for (i in 0 until 4) {
            val top = h * (56 + i * 8) / 100
            c.fill(w * 25 / 100, top, w * 75 / 100, top + h * 6 / 100, Canvas.WHITE)
            if (i !in emptyButtons) c.ink(w * 40 / 100, top + h * 15 / 1000, w * 60 / 100, top + h * 45 / 1000)
        }
        return c
    }

    /** [quiz] with the buttons replaced by a number-entry box across the banner's lower edge. */
    fun numeric(w: Int, h: Int): Canvas {
        val c = quiz(w, h)
        c.fill(0, h * 54 / 100, w, h * 85 / 100, Canvas.BLUE)
        c.fill(w * 25 / 100, h * 49 / 100, w * 75 / 100, h * 56 / 100, Canvas.WHITE)
        return c
    }
}
