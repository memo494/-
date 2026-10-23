package com.clu.quickanswer.ocr

import kotlin.math.abs

/**
 * A tiny, resolution-independent picture of a whole screen (24x12 cells, one hex digit of mean
 * luma per cell) used to recognise which GAME is on screen, so a layout learned once can be
 * found again. Same format as the test fixture UserScreenshotFingerprints, so the real
 * samples can test it. Pure Kotlin, no android.*.
 *
 * Our own windows (overlay, panel) are painted over the capture; the cells they cover are '.'
 * (unknown) so they neither make two screens of one game differ nor two games alike.
 */
object ScreenFingerprint {
    const val COLS = 24
    const val ROWS = 12

    /**
     * Largest [distance] at which two screens count as the same game. Measured on the nine user
     * screenshots: screens of one game are at most 1.59 apart, different games at least 2.06, the
     * gallery viewer at least 5.2. The fingerprint tells the GAME, not the question type (a numeric
     * screen is 0.5 from a 2x2 grid screen of the same game), which is why region signatures exist.
     * To be recalibrated on more captures.
     */
    const val SAME_GAME_MAX = 1.8

    /** A cell is unknown when at least this share of it is under one of our windows. */
    private const val MASKED_SHARE = 0.30

    /** Every n-th pixel each way is read: plenty for a mean over a cell, and fast on a 1080x2400 capture. */
    private const val STEP = 4

    private const val UNKNOWN = '.'

    /**
     * The fingerprint of [argb] (row-major ARGB, alpha ignored): [COLS] x [ROWS] characters,
     * row-major. A cell is the hex digit of its mean luma / 17 (rounded, 0..15), or '.' when at
     * least 30 % of it is covered by [masks].
     */
    fun of(argb: IntArray, width: Int, height: Int, masks: List<PxRect> = emptyList()): String {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val sb = StringBuilder(COLS * ROWS)
        for (r in 0 until ROWS) {
            val y0 = minOf(height - 1, r * height / ROWS)
            val y1 = maxOf(y0 + 1, (r + 1) * height / ROWS)
            for (c in 0 until COLS) {
                val x0 = minOf(width - 1, c * width / COLS)
                val x1 = maxOf(x0 + 1, (c + 1) * width / COLS)
                var sum = 0L
                var n = 0
                var covered = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val p = argb[y * width + x]
                        sum += (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
                        n++
                        if (masks.isNotEmpty() && inAny(masks, x, y)) covered++
                        x += STEP
                    }
                    y += STEP
                }
                if (covered >= MASKED_SHARE * n) {
                    sb.append(UNKNOWN)
                } else {
                    val level = Math.round(sum.toDouble() / n / 17.0).toInt().coerceIn(0, 15)
                    sb.append(Character.forDigit(level, 16))
                }
            }
        }
        return sb.toString()
    }

    private fun inAny(masks: List<PxRect>, x: Int, y: Int): Boolean {
        for (m in masks) if (x >= m.left && x < m.right && y >= m.top && y < m.bottom) return true
        return false
    }

    /**
     * Mean absolute difference, in levels (0..15), over the cells known in both. Infinite when the
     * lengths differ or fewer than half of the cells are known in both: too little is left to say
     * anything. Anything that is not a hex digit counts as unknown.
     */
    fun distance(a: String, b: String): Double {
        if (a.length != b.length || a.isEmpty()) return Double.POSITIVE_INFINITY
        var known = 0
        var sum = 0
        for (i in a.indices) {
            val x = a[i].digitToIntOrNull(16) ?: continue
            val y = b[i].digitToIntOrNull(16) ?: continue
            known++
            sum += abs(x - y)
        }
        if (known * 2 < a.length) return Double.POSITIVE_INFINITY
        return sum.toDouble() / known
    }
}
