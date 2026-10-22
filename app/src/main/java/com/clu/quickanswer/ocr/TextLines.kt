package com.clu.quickanswer.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Tight text-line boxes inside a region, for text of either polarity: [ColorLayout] only knows
 * dark text on its cream and white boxes, but most games the app should learn are dark with light
 * text. The line cutting is the same as [ColorLayout]'s (Otsu, row projection into bands, merge of
 * bands split by a small gap, column extent per band, tight boxes) so the recogniser sees the
 * same kind of crop; only the choice of which side of the threshold is ink differs.
 */
object TextLines {
    /** Horizontal padding added to a line as a fraction of its height (vertical padding is harmful). */
    private const val PAD_X = 0.25

    /** Share of [box] ignored on each side: borders and rounded corners are not text. */
    private const val INSET_X = 0.04
    private const val INSET_Y = 0.06

    /** Capture size (long side) the pixel constants were tuned on, see [ColorLayout.REFERENCE_LONG_SIDE]. */
    private const val REFERENCE_LONG_SIDE = ColorLayout.REFERENCE_LONG_SIDE.toDouble()

    /** Ink is at least this far from the background in luma, so a faint gradient is not text. */
    private const val MIN_CONTRAST = 25

    /** Below this standard deviation of luma a region is flat: no text. */
    private const val MIN_STD = 12.0

    /**
     * Text lines inside [box] (input-image pixels), top to bottom, clamped to the box shrunk by 4 %
     * horizontally and 6 % vertically. Empty for a flat region or one without ink.
     */
    fun find(argb: IntArray, width: Int, height: Int, box: PxRect): List<PxRect> {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val unit = max(width, height) / REFERENCE_LONG_SIDE
        val rx0 = (box.left + (box.width * INSET_X).toInt()).coerceIn(0, width)
        val rx1 = (box.right - (box.width * INSET_X).toInt()).coerceIn(0, width)
        val ry0 = (box.top + (box.height * INSET_Y).toInt()).coerceIn(0, height)
        val ry1 = (box.bottom - (box.height * INSET_Y).toInt()).coerceIn(0, height)
        val w = rx1 - rx0
        val h = ry1 - ry0
        if (w <= 0 || h <= 0) return emptyList()

        val gray = ImageOps.grayRoi(argb, width, rx0, ry0, rx1, ry1)
        var sum = 0.0
        for (g in gray) sum += g
        val mean = sum / gray.size
        var sq = 0.0
        for (g in gray) sq += (g - mean) * (g - mean)
        if (Math.sqrt(sq / gray.size) < MIN_STD) return emptyList()

        // The background is what the edge of the region is made of; the ink is what lies on the
        // other side of the Otsu threshold and clearly differs from it.
        val edge = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { edge.add(gray[x]); edge.add(gray[(h - 1) * w + x]) }
        for (y in 1 until h - 1) { edge.add(gray[y * w]); edge.add(gray[y * w + w - 1]) }
        edge.sort()
        val border = edge[edge.size / 2]
        val threshold = ImageOps.otsu(gray)
        val ink = if (border > threshold) {
            BooleanArray(gray.size) { gray[it] <= threshold && border - gray[it] >= MIN_CONTRAST }
        } else {
            BooleanArray(gray.size) { gray[it] > threshold && gray[it] - border >= MIN_CONTRAST }
        }

        val rowMin = max(2.0, 0.004 * w)
        val bands = ArrayList<IntArray>()
        var start = -1
        for (y in 0..h) {
            var on = false
            if (y < h) {
                var n = 0
                val base = y * w
                for (x in 0 until w) if (ink[base + x]) n++
                on = n > rowMin
            }
            if (on && start < 0) {
                start = y
            } else if (!on && start >= 0) {
                bands.add(intArrayOf(start, y))
                start = -1
            }
        }
        // Merge bands split by a small gap (dots and diacritics above or below the line).
        val merged = ArrayList<IntArray>()
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null && b[0] - last[1] < 0.35 * max(8.0 * unit, (last[1] - last[0]).toDouble())) {
                last[1] = b[1]
            } else {
                merged.add(b)
            }
        }
        if (merged.isEmpty()) return emptyList()
        val tallest = merged.maxOf { it[1] - it[0] }

        val margin = max(2, Math.rint(2.0 * unit).toInt())
        val out = ArrayList<PxRect>()
        for ((a, b) in merged.map { it[0] to it[1] }) {
            if (b - a < 0.35 * tallest) continue // stray marks
            var first = -1
            var last = -1
            for (x in 0 until w) {
                var any = false
                for (y in a until b) if (ink[y * w + x]) { any = true; break }
                if (any) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            val px = (PAD_X * (b - a)).toInt() + margin
            out.add(
                PxRect(
                    max(rx0, rx0 + first - px),
                    max(ry0, ry0 + a - margin),
                    min(rx1, rx0 + last + px),
                    min(ry1, ry0 + b + margin),
                )
            )
        }
        return out
    }
}
