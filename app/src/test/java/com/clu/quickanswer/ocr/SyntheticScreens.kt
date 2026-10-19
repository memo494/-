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

/**
 * Synthetic quiz screens shaped like the game's (the shapes [ColorLayout] insists on): a banner
 * 0.8 of the width and about 4 times wider than high, a stack of white buttons half as wide as the
 * banner, centred, with the pitch of the real game; a number field a third of the banner wide
 * over a keypad.
 */
object SyntheticScreens {

    // Text lines in the banner, in 1/10000 of the height (the same for every size).
    const val LINE1_TOP = 4150
    const val LINE1_BOTTOM = 4300
    const val LINE2_TOP = 4400
    const val LINE2_BOTTOM = 4550

    /**
     * Portrait screen: black bars, cream banner with two text lines, [buttonCount] white buttons with
     * one line each. A button listed in [emptyButtons] gets no text line; one in [hiddenButtons] is
     * not drawn at all (as if something covered it).
     */
    fun quiz(
        w: Int,
        h: Int,
        emptyButtons: Set<Int> = emptySet(),
        hiddenButtons: Set<Int> = emptySet(),
        buttonCount: Int = 4,
    ): Canvas {
        val c = Canvas(w, h, Canvas.BLACK)
        c.fill(0, h / 10, w, h * 9 / 10, Canvas.BLUE) // content area between the letterbox bars
        c.fill(w / 10, h * 40 / 100, w * 9 / 10, h * 49 / 100, Canvas.CREAM) // banner
        c.ink(w * 30 / 100, h * LINE1_TOP / 10000, w * 70 / 100, h * LINE1_BOTTOM / 10000) // question line 1
        c.ink(w * 40 / 100, h * LINE2_TOP / 10000, w * 60 / 100, h * LINE2_BOTTOM / 10000) // question line 2
        for (i in 0 until buttonCount) {
            if (i in hiddenButtons) continue
            val top = h * (56 + i * 8) / 100
            c.fill(w * 30 / 100, top, w * 70 / 100, top + h * 6 / 100, Canvas.WHITE)
            if (i !in emptyButtons) c.ink(w * 40 / 100, top + h * 15 / 1000, w * 60 / 100, top + h * 45 / 1000)
        }
        return c
    }

    /**
     * [quiz] with the buttons replaced by a number-entry box across the banner's lower edge and,
     * unless [keypad] is false, a 3 x 3 keypad under it.
     */
    fun numeric(w: Int, h: Int, keypad: Boolean = true): Canvas {
        val c = quiz(w, h)
        c.fill(0, h * 54 / 100, w, h * 9 / 10, Canvas.BLUE)
        c.fill(w * 38 / 100, h * 465 / 1000, w * 62 / 100, h * 505 / 1000, Canvas.WHITE)
        if (keypad) {
            val key = h * 4 / 100
            val gap = h * 1 / 100
            val left = (w - (3 * key + 2 * gap)) / 2
            for (row in 0 until 3) for (col in 0 until 3) {
                val x = left + col * (key + gap)
                val y = h * 54 / 100 + row * (key + gap)
                c.fill(x, y, x + key, y + key, Canvas.WHITE)
            }
        }
        return c
    }
}
