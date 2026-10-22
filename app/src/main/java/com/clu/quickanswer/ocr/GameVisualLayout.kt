package com.clu.quickanswer.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative visual UI fallback for the two landscape quiz skins.
 *
 * This is independent of the strict legacy Arabic OCR layout. It recognizes the
 * button/keypad geometry only; it never guesses question text or an answer.
 * Coordinates are projected from a 1536x691 reference into the actual capture.
 * Samples validate the *visible key surfaces* on each frame: a template alone
 * can never authorize a tap on a different screen.
 */
object GameVisualLayout {
    enum class Kind { CHOICE_GRID, NUMERIC_DARK, NUMERIC_WHITE }

    data class Detected(
        val kind: Kind,
        val options: List<PxRect> = emptyList(),
        val digits: Map<Char, PxRect> = emptyMap(),
        val submit: PxRect? = null,
        val questionArea: PxRect,
    )

    private data class RGB(val r: Double, val g: Double, val b: Double) {
        val light: Double get() = (r * 299 + g * 587 + b * 114) / 1000.0
    }
    private const val BASE_W = 1536f
    private const val BASE_H = 691f

    fun detect(argb: IntArray, width: Int, height: Int): Detected? {
        if (width < 320 || height < 200 || width.toDouble() / height !in 1.7..2.65 ||
            argb.size < width.toLong() * height
        ) return null
        val xScale = width / BASE_W
        val yScale = height / BASE_H

        fun rect(x: Int, y: Int, halfW: Int, halfH: Int): PxRect =
            PxRect(
                ((x - halfW) * xScale).toInt().coerceIn(0, width - 1),
                ((y - halfH) * yScale).toInt().coerceIn(0, height - 1),
                ((x + halfW) * xScale).toInt().coerceIn(1, width),
                ((y + halfH) * yScale).toInt().coerceIn(1, height),
            )

        fun patch(x: Int, y: Int, xOffsets: IntArray, yOffsets: IntArray): RGB {
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            var count = 0
            for (xo in xOffsets) for (yo in yOffsets) {
                val cx = ((x + xo) * xScale).toInt()
                val cy = ((y + yo) * yScale).toInt()
                val radiusX = max(1, (2 * xScale).toInt())
                val radiusY = max(1, (2 * yScale).toInt())
                for (yy in max(0, cy - radiusY)..min(height - 1, cy + radiusY)) {
                    for (xx in max(0, cx - radiusX)..min(width - 1, cx + radiusX)) {
                        val c = argb[yy * width + xx]
                        red += (c shr 16) and 255
                        green += (c shr 8) and 255
                        blue += c and 255
                        count++
                    }
                }
            }
            return RGB(red / count, green / count, blue / count)
        }

        val compactX = intArrayOf(-20, 20)
        val compactY = intArrayOf(-12, 12)
        val lightX = intArrayOf(-26, 26)
        val lightY = intArrayOf(-22, 22)
        val darkRows = listOf(
            listOf(922 to 302, 992 to 302, 1062 to 302),
            listOf(922 to 370, 992 to 370, 1062 to 370),
            listOf(922 to 438, 992 to 438, 1062 to 438),
            listOf(992 to 504),
        ).flatten()

        // Skin A: warm charcoal squares, 1-2-3 / 4-5-6 / 7-8-9 / C-0-delete.
        var darkOffset: Pair<Int, Int>? = null
        var darkBest = 0
        for (dy in -12..12 step 6) for (dx in -12..12 step 6) {
            val matches = darkRows.count { (x, y) ->
                val p = patch(x + dx, y + dy, compactX, compactY)
                p.r in 112.0..178.0 && p.r - p.g in 4.0..21.0 &&
                    p.g - p.b in 3.0..21.0
            }
            if (matches > darkBest) { darkBest = matches; darkOffset = dx to dy }
        }
        if (darkBest >= 9 && darkOffset != null) {
            val (dx, dy) = darkOffset
            val digits = mutableMapOf<Char, PxRect>()
            val labels = listOf("123", "456", "789", "0")
            var i = 0
            for (row in labels) for (digit in row) {
                val (x, y) = darkRows[i++]
                digits[digit] = rect(x + dx, y + dy, 18, 16)
            }
            return Detected(Kind.NUMERIC_DARK, digits = digits,
                questionArea = rect(664, 291, 250, 63))
        }

        val whiteRows = listOf(
            listOf(550 to 364, 653 to 364, 758 to 364, 862 to 364),
            listOf(550 to 470, 653 to 470, 758 to 470),
            listOf(550 to 569, 653 to 569, 758 to 569),
        ).flatten()
        var whiteOffset: Pair<Int, Int>? = null
        var whiteBest = 0
        for (dy in -12..12 step 6) for (dx in -12..12 step 6) {
            val matches = whiteRows.count { (x, y) ->
                val p = patch(x + dx, y + dy, lightX, lightY)
                min(p.r, min(p.g, p.b)) > 223.0 &&
                    max(p.r, max(p.g, p.b)) - min(p.r, min(p.g, p.b)) < 36.0
            }
            if (matches > whiteBest) { whiteBest = matches; whiteOffset = dx to dy }
        }
        if (whiteBest >= 9 && whiteOffset != null) {
            val (dx, dy) = whiteOffset
            // The saturated green confirmation control is essential for skin B.
            val submitPixels = arrayOf(
                patch(901 + dx, 472 + dy, intArrayOf(-35, 35), intArrayOf(-23, 23)),
                patch(902 + dx, 543 + dy, intArrayOf(-35, 35), intArrayOf(-18, 18)),
            )
            val greens = submitPixels.count { it.g > 110 && it.g > it.r * 1.35 && it.g > it.b * 1.15 }
            if (greens >= 1) {
                val labels = "7890456123"
                val digits = whiteRows.mapIndexed { index, (x, y) ->
                    labels[index] to rect(x + dx, y + dy, 20, 20)
                }.toMap()
                return Detected(Kind.NUMERIC_WHITE, digits = digits,
                    submit = rect(904 + dx, 518 + dy, 37, 38),
                    questionArea = rect(754, 114, 440, 93))
            }
        }

        // Skin A 2x2 choices. It comes in a light translucent and a dark opaque
        // version. Search translation and row pitch and verify all four surfaces
        // have similar color *and* contrast with the question background.
        var best = Double.NEGATIVE_INFINITY
        var choiceRects: List<PxRect>? = null
        for (dx in -25..60 step 8) for (dy in -10..45 step 6) {
            for (pitch in intArrayOf(79, 84, 90)) {
                val centers = listOf(
                    917 + dx to 382 + dy,
                    637 + dx to 382 + dy,
                    917 + dx to 382 + dy + pitch,
                    637 + dx to 382 + dy + pitch,
                )
                val swatches = centers.map { (x, y) ->
                    patch(x, y, intArrayOf(-25, 25), intArrayOf(-13, 13))
                }
                val levels = swatches.map { it.light }
                if (levels.max() > 197 || levels.min() < 50 ||
                    levels.max() - levels.min() > 47
                ) continue
                if (swatches.any { it.r - it.g !in -4.0..21.0 || it.g - it.b !in -4.0..25.0 }) continue
                val backdrop = listOf(
                    patch(637 + dx, 285 + dy, intArrayOf(-25, 25), intArrayOf(-13, 13)).light,
                    patch(917 + dx, 285 + dy, intArrayOf(-25, 25), intArrayOf(-13, 13)).light,
                ).average()
                // The choice panels sit on a visibly bright parchment. Without
                // this, a dark numeric-game background can imitate four choices.
                if (backdrop < 150.0) continue
                val darkest = levels.max()
                if (backdrop - darkest < 18) continue
                val score = backdrop - levels.average() - (levels.max() - levels.min()) * 0.5
                if (score > best) {
                    best = score
                    choiceRects = centers.map { (x, y) -> rect(x, y, 18, 14) }
                }
            }
        }
        return choiceRects?.let {
            Detected(Kind.CHOICE_GRID, options = it,
                questionArea = rect(780, 290, 300, 86))
        }
    }
}
