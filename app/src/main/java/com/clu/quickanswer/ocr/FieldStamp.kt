package com.clu.quickanswer.ocr

import kotlin.math.abs

/**
 * What a number field looked like EMPTY on the screen the user calibrated, so a game whose ink
 * colour Clu does not know can still be checked before typing. The stamp is a grid of mean
 * lightness inside the field (its frame left out); a placeholder such as a grey "0000" is part of
 * the empty look, so it is never mistaken for typed digits.
 *
 * Fail closed: [state] is EMPTY only when every cell is still close to the stamp. A typed digit
 * changes the cells it covers far beyond the tolerance; an animated or covered field, or a wrong
 * size, is UNKNOWN or FILLED, and both stop the typing.
 *
 * Pure Kotlin, no android.*.
 */
object FieldStamp {
    enum class State { EMPTY, FILLED, UNKNOWN }

    /**
     * Grid of a new stamp: wide, because digits sit side by side, and fine enough that a thin "1"
     * covers a good share of a cell (with 16 x 4 cells a low-contrast "1" could stay under the
     * tolerance, review 26 Oct 2026).
     */
    const val COLS = 32
    const val ROWS = 8

    /**
     * Largest change of a cell's mean lightness (0..255) that still counts as the same empty field.
     * The capture is lossless, so an unchanged cell moves by 1-3 at most (anti-aliasing of the
     * hint); a thin typed "1" with a contrast of 40 changes the cells it crosses by over 12.
     */
    const val CELL_TOLERANCE = 8

    /** The grid and tolerance of stamps saved before 26 Oct 2026; still read, never written. */
    private const val OLD_COLS = 16
    private const val OLD_ROWS = 4
    private const val OLD_TOLERANCE = 14

    /** Share of the field's width/height left out on each side: its frame, bevel or shadow. */
    private const val INSET = 0.12f

    /** Smallest field side, in pixels, that a stamp is made for. */
    private const val MIN_SIDE = 24

    /** The stamp of [field] on this capture (hex, two digits per cell), or null when it cannot have one. */
    fun of(argb: IntArray, width: Int, height: Int, field: PxRect): String? {
        val cells = cells(argb, width, height, field, COLS, ROWS) ?: return null
        return buildString(cells.size * 2) { for (v in cells) append(HEX[v shr 4]).append(HEX[v and 15]) }
    }

    /** Whether [field] still looks as it did empty ([stamp], from [of]). */
    fun state(stamp: String?, argb: IntArray, width: Int, height: Int, field: PxRect): State {
        val stored = decode(stamp) ?: return State.UNKNOWN
        val old = stored.size == OLD_COLS * OLD_ROWS
        val now = cells(argb, width, height, field, if (old) OLD_COLS else COLS, if (old) OLD_ROWS else ROWS)
            ?: return State.UNKNOWN
        val tolerance = if (old) OLD_TOLERANCE else CELL_TOLERANCE
        return if (stored.indices.all { abs(stored[it] - now[it]) <= tolerance }) State.EMPTY else State.FILLED
    }

    fun isValid(stamp: String?): Boolean = decode(stamp) != null

    private fun decode(stamp: String?): IntArray? {
        if (stamp == null) return null
        val n = stamp.length / 2
        if (stamp.length % 2 != 0 || n != COLS * ROWS && n != OLD_COLS * OLD_ROWS) return null
        return IntArray(n) { i ->
            val hi = stamp[i * 2].digitToIntOrNull(16) ?: return null
            val lo = stamp[i * 2 + 1].digitToIntOrNull(16) ?: return null
            hi * 16 + lo
        }
    }

    private fun cells(argb: IntArray, width: Int, height: Int, field: PxRect, cols: Int, rows: Int): IntArray? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        if (field.left < 0 || field.top < 0 || field.right > width || field.bottom > height) return null
        if (field.width < MIN_SIDE || field.height < MIN_SIDE) return null
        val dx = (field.width * INSET).toInt()
        val dy = (field.height * INSET).toInt()
        val left = field.left + dx
        val top = field.top + dy
        val w = field.width - 2 * dx
        val h = field.height - 2 * dy
        if (w < cols || h < rows) return null
        val sum = LongArray(cols * rows)
        val count = IntArray(cols * rows)
        for (y in 0 until h) {
            val row = (y * rows / h) * cols
            val base = (top + y) * width + left
            for (x in 0 until w) {
                val c = argb[base + x]
                val cell = row + x * cols / w
                sum[cell] += (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                count[cell]++
            }
        }
        return IntArray(sum.size) { (sum[it] / count[it]).toInt().coerceIn(0, 255) }
    }

    private const val HEX = "0123456789abcdef"
}
