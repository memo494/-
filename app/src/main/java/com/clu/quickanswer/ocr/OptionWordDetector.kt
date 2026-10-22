package com.clu.quickanswer.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the *printed words* of the four answer choices, rather than guessing where
 * their buttons lie. This is a conservative detector for the landscape parchment
 * quiz skin: near-white Arabic text on four charcoal buttons.
 *
 * Returns RTL reading order: top-right, top-left, bottom-right, bottom-left.
 * If all four separate word regions cannot be found, returns null (no tap).
 *
 * No dependence on the game's button coordinates, online model, or accessibility
 * view tree; it searches actual bright letter pixels in wide screen areas.
 */
object OptionWordDetector {
    private const val REF_WIDTH = 1536f
    private const val REF_HEIGHT = 691f

    fun detect(argb: IntArray, width: Int, height: Int): List<PxRect>? {
        if (width < 320 || height < 200 ||
            width.toDouble() / height !in 1.7..2.65 ||
            argb.size.toLong() < width.toLong() * height
        ) return null
        fun x(v: Int) = (v * width / REF_WIDTH).toInt().coerceIn(0, width)
        fun y(v: Int) = (v * height / REF_HEIGHT).toInt().coerceIn(0, height)

        val left = column(argb, width, x(515), x(795), y(345), y(514), height) ?: return null
        val right = column(argb, width, x(813), x(1084), y(345), y(514), height) ?: return null
        val maxMisalignment = max(5, y(17))
        if (abs(left[0].centerY - right[0].centerY) > maxMisalignment ||
            abs(left[1].centerY - right[1].centerY) > maxMisalignment
        ) return null
        val leftPitch = left[1].centerY - left[0].centerY
        val rightPitch = right[1].centerY - right[0].centerY
        if (leftPitch < y(56) || leftPitch > y(110) ||
            rightPitch < y(56) || rightPitch > y(110)
        ) return null
        // The word boxes are inside four distinct choices. Tap the text itself.
        return listOf(right[0], left[0], right[1], left[1])
    }

    private fun column(
        pixels: IntArray, width: Int, l: Int, r: Int,
        t: Int, b: Int, imageHeight: Int,
    ): List<PxRect>? {
        if (r - l < 30 || b - t < 55) return null
        val rowCounts = IntArray(b - t)
        val minX = IntArray(b - t) { r }
        val maxX = IntArray(b - t) { l }
        for (yy in t until b) {
            val row = yy * width
            val idx = yy - t
            for (xx in l until r) {
                val c = pixels[row + xx]
                val red = (c shr 16) and 255
                val green = (c shr 8) and 255
                val blue = c and 255
                // White glyphs, not cream parchment or colored HUD graphics.
                if (min(red, min(green, blue)) <= 175 ||
                    max(red, max(green, blue)) - min(red, min(green, blue)) >= 27
                ) continue
                rowCounts[idx]++
                minX[idx] = min(minX[idx], xx)
                maxX[idx] = max(maxX[idx], xx)
            }
        }
        val threshold = max(3, (width * 4f / REF_WIDTH).toInt())
        val joinGap = max(2, (imageHeight * 8f / REF_HEIGHT).toInt())
        val rows = ArrayList<IntRange>()
        var first = -1
        var prev = -1
        for (i in rowCounts.indices) {
            if (rowCounts[i] < threshold) continue
            if (first == -1) first = i
            else if (i - prev > joinGap) {
                rows.add(first..prev)
                first = i
            }
            prev = i
        }
        if (first != -1) rows.add(first..prev)
        val candidates = ArrayList<PxRect>()
        for (band in rows) {
            val ht = band.last - band.first
            val total = band.sumOf { rowCounts[it] }
            if (ht < max(6, (imageHeight * 10f / REF_HEIGHT).toInt()) ||
                ht > max(17, (imageHeight * 55f / REF_HEIGHT).toInt()) ||
                total < max(35, (width * imageHeight * 110L / (REF_WIDTH * REF_HEIGHT)).toInt())
            ) continue
            val xl = band.minOf { minX[it] }
            val xr = band.maxOf { maxX[it] }
            // One glyph or UI dot is not a valid answer phrase.
            if (xr - xl < max(12, (width * 27f / REF_WIDTH).toInt())) continue
            val px = max(2, (width * 5f / REF_WIDTH).toInt())
            val py = max(2, (imageHeight * 4f / REF_HEIGHT).toInt())
            candidates.add(PxRect(max(l, xl - px), max(t, t + band.first - py),
                min(r, xr + px + 1), min(b, t + band.last + py + 1)))
        }
        // More than two clearly separated lines in a column means this is
        // not the expected two-choice-column skin (or the screen is occluded).
        if (candidates.size != 2) return null
        return candidates.sortedBy { it.centerY }
    }
}
