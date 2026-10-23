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
        /** Present only when the dark skin's C key is visible. */
        val clear: PxRect? = null,
        /** Present only when the white skin's cyan erase key is visible. */
        val backspace: PxRect? = null,
    )

    private data class RGB(val r: Double, val g: Double, val b: Double) {
        val light: Double get() = (r * 299 + g * 587 + b * 114) / 1000.0
    }
    private const val BASE_W = 1536f
    private const val BASE_H = 691f

    fun detect(argb: IntArray, width: Int, height: Int): Detected? = detectFrame(argb, width, height, null)

    /** A just-released key may still be highlighted; every other surface must remain visible. */
    fun stillVisible(
        argb: IntArray, width: Int, height: Int, expected: Detected, previousTap: PxRect? = null,
    ): Boolean {
        if (expected.kind == Kind.CHOICE_GRID) return false
        val knownPrevious = previousTap?.takeIf {
            it in expected.digits.values || it == expected.clear || it == expected.backspace
        }
        val now = detectFrame(argb, width, height, knownPrevious) ?: return false
        fun same(a: PxRect?, b: PxRect?): Boolean = a == null || b != null &&
            abs(a.centerX - b.centerX) <= max(3f, a.width * 0.2f) &&
            abs(a.centerY - b.centerY) <= max(3f, a.height * 0.2f)
        return now.kind == expected.kind && expected.digits.all { (digit, box) -> same(box, now.digits[digit]) } &&
            same(expected.submit, now.submit) && same(expected.clear, now.clear) &&
            same(expected.backspace, now.backspace)
    }

    private fun detectFrame(argb: IntArray, width: Int, height: Int, previousTap: PxRect?): Detected? {
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
        fun darkSurface(p: RGB): Boolean =
            p.r in 112.0..178.0 && p.r - p.g in 4.0..21.0 && p.g - p.b in 3.0..21.0

        fun whiteSurface(p: RGB): Boolean =
            min(p.r, min(p.g, p.b)) > 223.0 &&
                max(p.r, max(p.g, p.b)) - min(p.r, min(p.g, p.b)) < 36.0

        // Check each corner separately: averaging an occluded key together with
        // three good corners can otherwise hide the obstruction.
        fun surface(
            x: Int, y: Int, xs: IntArray, ys: IntArray, matches: (RGB) -> Boolean,
        ): Boolean = (previousTap != null &&
            abs(x * xScale - previousTap.centerX) <= max(2f, 3 * xScale) &&
            abs(y * yScale - previousTap.centerY) <= max(2f, 3 * yScale)) || xs.all { xo -> ys.all { yo ->
            matches(patch(x, y, intArrayOf(xo), intArrayOf(yo)))
        } }
        // Several translations can fit broad key surfaces equally well. Do
        // not bias taps towards the upper-left just because it is searched first.
        fun closer(dx: Int, dy: Int, previous: Pair<Int, Int>?): Boolean =
            previous == null || abs(dx) + abs(dy) < abs(previous.first) + abs(previous.second)
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
                surface(x + dx, y + dy, compactX, compactY, ::darkSurface)
            }
            if (matches > darkBest || matches == darkBest && closer(dx, dy, darkOffset)) {
                darkBest = matches
                darkOffset = dx to dy
            }
        }
        if (darkBest == darkRows.size && darkOffset != null) {
            val (dx, dy) = darkOffset
            // A flat charcoal panel is not a keypad. Its keys must stand out
            // from the parchment immediately to the left of the three rows.
            val backdrop = listOf(302, 370, 438).count { y ->
                patch(850 + dx, y + dy, intArrayOf(-4, 4), intArrayOf(-4, 4)).light > 170.0
            }
            if (backdrop >= 2) {
                val digits = mutableMapOf<Char, PxRect>()
                val labels = listOf("123", "456", "789", "0")
                var i = 0
                for (row in labels) for (digit in row) {
                    val (x, y) = darkRows[i++]
                    digits[digit] = rect(x + dx, y + dy, 18, 16)
                }
                // Some rounds show a large gold «تأكيد» button below the input;
                // other rounds have no submit control. Never tap where it is absent.
                val submitSamples = listOf(600 to 480, 740 to 480, 605 to 495)
                    .map { (x, y) -> patch(x + dx, y + dy,
                        intArrayOf(-5, 5), intArrayOf(-3, 3)) }
                val visibleSubmit = submitSamples.count { color ->
                    color.r > 110 && color.g > 65 &&
                        color.r - color.g > 25 && color.g - color.b > 34
                } >= 2
                return Detected(Kind.NUMERIC_DARK, digits = digits,
                    submit = if (visibleSubmit) rect(650 + dx, 480 + dy, 60, 17) else null,
                    // End before the keypad: a highlighted 1/4/7 key is not a changed question.
                    questionArea = rect(650 + dx, 291 + dy, 235, 63),
                    clear = if (surface(922 + dx, 504 + dy, compactX, compactY, ::darkSurface))
                        rect(922 + dx, 504 + dy, 18, 16) else null)
            }
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
                surface(x + dx, y + dy, lightX, lightY, ::whiteSurface)
            }
            if (matches > whiteBest || matches == whiteBest && closer(dx, dy, whiteOffset)) {
                whiteBest = matches
                whiteOffset = dx to dy
            }
        }
        if (whiteBest == whiteRows.size && whiteOffset != null) {
            val (dx, dy) = whiteOffset
            // The saturated green confirmation control is essential for skin B.
            val submitPixels = arrayOf(
                patch(901 + dx, 472 + dy, intArrayOf(-35, 35), intArrayOf(-23, 23)),
                patch(902 + dx, 543 + dy, intArrayOf(-35, 35), intArrayOf(-18, 18)),
            )
            val greens = submitPixels.count { it.g > 110 && it.g > it.r * 1.35 && it.g > it.b * 1.15 }
            // The white keys sit on a darker wooden panel. This separate
            // sample rejects a blank white screen with an unrelated green area.
            val backdrop = listOf(364, 470, 569).count { y ->
                patch(480 + dx, y + dy, intArrayOf(-4, 4), intArrayOf(-4, 4)).light < 200.0
            }
            if (greens >= 1 && backdrop >= 2) {
                val labels = "7890456123"
                val digits = whiteRows.mapIndexed { index, (x, y) ->
                    labels[index] to rect(x + dx, y + dy, 20, 20)
                }.toMap()
                return Detected(Kind.NUMERIC_WHITE, digits = digits,
                    submit = rect(904 + dx, 518 + dy, 37, 38),
                    // Stop above the input field: entering digits must not change the question guard.
                    questionArea = rect(754 + dx, 98 + dy, 440, 70),
                    backspace = if (surface(966 + dx, 364 + dy, lightX, lightY) { p ->
                        p.r in 80.0..225.0 && p.g in 120.0..245.0 && p.b > 155.0 &&
                            p.g - p.r in 12.0..90.0 && p.b - p.g in 0.0..65.0
                    }) rect(966 + dx, 364 + dy, 20, 20) else null)
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
                    // These are visible button surfaces; tap targets come from their OCR words.
                    choiceRects = centers.map { (x, y) -> rect(x, y, 132, 34) }
                }
            }
        }
        return choiceRects?.let {
            Detected(Kind.CHOICE_GRID, options = it,
                questionArea = rect(780, 290, 300, 86))
        }
    }
}
