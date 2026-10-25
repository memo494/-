package com.clu.quickanswer.ocr

import kotlin.math.abs

/**
 * What a number field looked like EMPTY on the screen the user calibrated, so a game whose ink
 * colour Clu does not know can still be checked before typing. The stamp is a coarse grid of mean
 * lightness inside the field (its frame left out); a placeholder such as a grey "0000" is part of
 * the empty look, so it is never mistaken for typed digits.
 *
 * Fail closed: [state] is EMPTY only when every cell is still close to the stamp. Any typed digit
 * darkens or lightens a few cells far beyond [CELL_TOLERANCE]; an animated or covered field, or a
 * wrong size, is UNKNOWN or FILLED, and both stop the typing.
 *
 * Pure Kotlin, no android.*.
 */
object FieldStamp {
    enum class State { EMPTY, FILLED, UNKNOWN }

    /** Grid of the stamp: wide, because digits sit side by side. */
    const val COLS = 16
    const val ROWS = 4

    /** Share of the field's width/height left out on each side: its frame, bevel or shadow. */
    private const val INSET = 0.12f

    /**
     * Largest change of a cell's mean lightness (0..255) that still counts as the same empty field.
     * Measured on the synthetic fields of the tests: JPEG-like noise and anti-aliasing move a cell
     * by under 6; the thinnest typed "1" moves at least one cell by over 30.
     */
    const val CELL_TOLERANCE = 14

    /** Smallest field side, in pixels, that leaves at least one pixel per cell after the inset. */
    private const val MIN_SIDE = 24

    /** The stamp of [field] on this capture (hex, two digits per cell), or null when it cannot have one. */
    fun of(argb: IntArray, width: Int, height: Int, field: PxRect): String? {
        val cells = cells(argb, width, height, field) ?: return null
        return buildString(cells.size * 2) { for (v in cells) append(HEX[v shr 4]).append(HEX[v and 15]) }
    }

    /** Whether [field] still looks as it did empty ([stamp], from [of]). */
    fun state(stamp: String?, argb: IntArray, width: Int, height: Int, field: PxRect): State {
        val stored = decode(stamp) ?: return State.UNKNOWN
        val now = cells(argb, width, height, field) ?: return State.UNKNOWN
        return if (stored.indices.all { abs(stored[it] - now[it]) <= CELL_TOLERANCE }) State.EMPTY else State.FILLED
    }

    fun isValid(stamp: String?): Boolean = decode(stamp) != null

    private fun decode(stamp: String?): IntArray? {
        if (stamp == null || stamp.length != COLS * ROWS * 2) return null
        return IntArray(COLS * ROWS) { i ->
            val hi = stamp[i * 2].digitToIntOrNull(16) ?: return null
            val lo = stamp[i * 2 + 1].digitToIntOrNull(16) ?: return null
            hi * 16 + lo
        }
    }

    private fun cells(argb: IntArray, width: Int, height: Int, field: PxRect): IntArray? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        if (field.left < 0 || field.top < 0 || field.right > width || field.bottom > height) return null
        if (field.width < MIN_SIDE || field.height < MIN_SIDE) return null
        val dx = (field.width * INSET).toInt()
        val dy = (field.height * INSET).toInt()
        val left = field.left + dx
        val top = field.top + dy
        val w = field.width - 2 * dx
        val h = field.height - 2 * dy
        if (w < COLS || h < ROWS) return null
        val sum = LongArray(COLS * ROWS)
        val count = IntArray(COLS * ROWS)
        for (y in 0 until h) {
            val row = (y * ROWS / h) * COLS
            val base = (top + y) * width + left
            for (x in 0 until w) {
                val c = argb[base + x]
                val cell = row + x * COLS / w
                sum[cell] += (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                count[cell]++
            }
        }
        return IntArray(sum.size) { (sum[it] / count[it]).toInt().coerceIn(0, 255) }
    }

    private const val HEX = "0123456789abcdef"
}
