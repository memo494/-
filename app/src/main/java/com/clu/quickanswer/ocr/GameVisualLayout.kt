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
        val field: PxRect? = null,
    )

    private data class RGB(val r: Double, val g: Double, val b: Double) {
        val light: Double get() = (r * 299 + g * 587 + b * 114) / 1000.0
    }
    private const val BASE_W = 1536f
    private const val BASE_H = 691f

    fun detect(argb: IntArray, width: Int, height: Int): Detected? = detectFrame(argb, width, height, null, false)

    /** The gray pad's dark 0000 hint is not entered text; this adapter knows its gold digit state. */
    fun inputHasText(argb: IntArray, width: Int, height: Int, detected: Detected, field: PxRect): Boolean {
        if (field.left < 0 || field.top < 0 || field.right > width || field.bottom > height ||
            field.width <= 0 || field.height <= 0 || argb.size.toLong() < width.toLong() * height) return false
        if (detected.kind != Kind.NUMERIC_DARK) return detected.kind == Kind.NUMERIC_WHITE
        var count = 0
        var top = field.bottom
        var bottom = field.top
        for (y in field.top until field.bottom) for (x in field.left until field.right) {
            val c = argb[y * width + x]
            if (NumericFieldReader.isGold(c)) {
                count++
                top = min(top, y)
                bottom = max(bottom, y)
            }
        }
        return count >= max(8, field.width * field.height / 500) && bottom - top >= max(4, field.height / 5)
    }

    /**
     * The inside of the dark skin's number field, where digits are drawn, measured on this capture,
     * or null when no dark field is found around [field]. [field] comes from the reference geometry
     * and, on a card drawn off the reference, reaches into the field's gold rim (25 Oct 2026, owner's
     * phone: "ink373x30", rim arcs taken for typed digits, so nothing was ever typed). The dark wood
     * is found near [field] (rows over 40% dark, columns over 25% dark), then [INTERIOR_INSET] is
     * left out on every side: the rim and the pill's rounded ends. Digits are centred well inside.
     */
    fun fieldInterior(argb: IntArray, width: Int, height: Int, field: PxRect): PxRect? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height ||
            field.width < 16 || field.height < 16) return null
        val left = max(0, field.left - field.width / 6)
        val right = min(width, field.right + field.width / 6)
        val top = max(0, field.top - field.height / 2)
        val bottom = min(height, field.bottom + field.height / 2)
        fun dark(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return (r * 299 + g * 587 + b * 114) / 1000 < 120 && max(r, max(g, b)) - min(r, min(g, b)) < 60
        }
        var t = -1; var bt = -1
        for (y in top until bottom) {
            var n = 0
            for (x in left until right) if (dark(argb[y * width + x])) n++
            if (n > (right - left) * 0.4) { if (t < 0) t = y; bt = y + 1 }
        }
        var l = -1; var rt = -1
        for (x in left until right) {
            var n = 0
            for (y in top until bottom) if (dark(argb[y * width + x])) n++
            if (n > (bottom - top) * 0.25) { if (l < 0) l = x; rt = x + 1 }
        }
        if (t < 0 || l < 0) return null
        val w = rt - l
        val h = bt - t
        // The field found must be about the size expected; anything else is not this field.
        if (w < field.width / 2 || w > field.width * 2 || h < field.height / 2 || h > field.height * 2) return null
        val inside = PxRect(l + (w * INTERIOR_INSET).toInt(), t + (h * INTERIOR_INSET).toInt(),
            rt - (w * INTERIOR_INSET).toInt(), bt - (h * INTERIOR_INSET).toInt())
        return inside.takeIf { it.width >= 16 && it.height >= 16 }
    }

    /** Share of the dark field left out on each side: its gold rim and rounded ends (about 12%). */
    private const val INTERIOR_INSET = 0.15f

    /** A coloured parchment/shadow can fit sparse samples; an actual gold button has its own rim. */
    fun hasSubmitControl(argb: IntArray, width: Int, height: Int, detected: Detected): Boolean {
        val button = detected.submit ?: return false
        if (argb.size.toLong() < width.toLong() * height || width <= 0 || height <= 0) return false
        if (detected.kind == Kind.NUMERIC_WHITE) return true // its saturated green control is mandatory in detect
        if (detected.kind != Kind.NUMERIC_DARK) return false
        fun pixel(dx: Int, dy: Int): Int? {
            val x = (button.centerX + dx * button.width / 120f).toInt()
            val y = (button.centerY + dy * button.height / 34f).toInt()
            return if (x in 0 until width && y in 0 until height) argb[y * width + x] else null
        }
        fun light(c: Int) = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        val fill = listOf(-73 to -6, 67 to -6, -68 to 9).mapNotNull { (x, y) -> pixel(x, y) }
        if (fill.size != 3) return false
        val fillLight = fill.map(::light).average()
        fun rim(xs: IntArray, ys: IntArray): Boolean = xs.any { x -> ys.any { y ->
            pixel(x, y)?.let { c ->
                val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                light(c) < fillLight - 20 && maxOf(r, g, b) - minOf(r, g, b) < 55
            } == true
        } }
        return listOf(
            rim(intArrayOf(-103, -23, 67), intArrayOf(-42, -36, -30)),
            rim(intArrayOf(-103, -23, 67), intArrayOf(32, 38, 44)),
            rim(intArrayOf(-148, -142, -136), intArrayOf(-16, 0, 16)),
            rim(intArrayOf(134, 140, 146), intArrayOf(-16, 0, 16)),
        ).count { it } >= 3
    }

    /** A just-released key may still be highlighted; every other surface must remain visible. */
    fun stillVisible(
        argb: IntArray, width: Int, height: Int, expected: Detected, previousTap: PxRect? = null,
    ): Boolean {
        if (expected.kind == Kind.CHOICE_GRID) return false
        val knownPrevious = previousTap?.takeIf {
            it in expected.digits.values || it == expected.clear || it == expected.backspace
        }
        // While typing, the pad is only confirmed where it already was (every key must match its
        // place below), so the wide search may use main's two-sided key test: a pressed key's glow
        // in the gap next to its neighbours must not stop the entry halfway.
        val now = detectFrame(argb, width, height, knownPrevious, relaxedWide = true) ?: return false
        fun same(a: PxRect?, b: PxRect?): Boolean = a == null || b != null &&
            abs(a.centerX - b.centerX) <= max(3f, a.width * 0.2f) &&
            abs(a.centerY - b.centerY) <= max(3f, a.height * 0.2f)
        return now.kind == expected.kind && expected.digits.all { (digit, box) -> same(box, now.digits[digit]) } &&
            same(expected.submit, now.submit) && same(expected.clear, now.clear) &&
            same(expected.backspace, now.backspace)
    }

    /**
     * How far (reference units) the game may draw its card from the reference position. Measured on
     * the owner's phone (25 Oct 2026): the dark pad sat about 30 left and 25 up, beyond the former
     * ±12, so a plainly visible pad was "unknown". Kept below one key pitch (70 dark, 100 white) so a
     * shift can never line every key up with its neighbour; the backdrop and submit checks still apply.
     */
    private const val MAX_SHIFT = 48
    /** The measured window searched first (the detector's behaviour before the wide search). */
    private const val NEAR_SHIFT = 12
    private const val NEAR_STEP = 6
    private const val SHIFT_STEP = 4
    /** Lightness step on every side of a key for the wide search (the phone's keys: 86 and up). */
    private const val SEPARATE_KEY_EDGE = 40.0

    /**
     * Where the keys are: first the measured window (±12 in steps of 6, the offset nearest the
     * reference, as tested on the known captures), and only when nothing matches there, the wide
     * search. In the wide one many offsets match a broad key face, so the one at their centre is
     * taken (the nearest one to the centre that matches), not the first, which sits near a key's
     * edge and taps off-centre.
     */
    private inline fun centredOffset(
        allKeys: (Int, Int) -> Boolean,
        /** Null: no wide search. Stricter than [allKeys], because more of the screen is tried. */
        noinline allKeysWide: ((Int, Int) -> Boolean)?,
    ): Pair<Int, Int>? {
        var near: Pair<Int, Int>? = null
        for (dy in -NEAR_SHIFT..NEAR_SHIFT step NEAR_STEP) for (dx in -NEAR_SHIFT..NEAR_SHIFT step NEAR_STEP) {
            if ((near == null || abs(dx) + abs(dy) < abs(near.first) + abs(near.second)) && allKeys(dx, dy)) near = dx to dy
        }
        if (near != null || allKeysWide == null) return near
        val hits = ArrayList<Pair<Int, Int>>()
        for (dy in -MAX_SHIFT..MAX_SHIFT step SHIFT_STEP) for (dx in -MAX_SHIFT..MAX_SHIFT step SHIFT_STEP) {
            if (allKeysWide(dx, dy)) hits.add(dx to dy)
        }
        if (hits.isEmpty()) return null
        val cx = hits.sumOf { it.first }.toDouble() / hits.size
        val cy = hits.sumOf { it.second }.toDouble() / hits.size
        return hits.minWith(compareBy<Pair<Int, Int>> { abs(it.first - cx) + abs(it.second - cy) }
            .thenBy { abs(it.first) + abs(it.second) })
    }

    private fun detectFrame(argb: IntArray, width: Int, height: Int, previousTap: PxRect?, relaxedWide: Boolean): Detected? {
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
            p.light in 55.0..210.0 &&
                max(p.r, max(p.g, p.b)) - min(p.r, min(p.g, p.b)) <= 48.0 &&
                p.r - p.g in -8.0..26.0 && p.g - p.b in -8.0..32.0

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
        fun wasPressed(x: Int, y: Int): Boolean = previousTap != null &&
            abs(x * xScale - previousTap.centerX) <= max(2f, 3 * xScale) &&
            abs(y * yScale - previousTap.centerY) <= max(2f, 3 * yScale)

        fun darkKey(x: Int, y: Int): Boolean {
            if (!surface(x, y, compactX, compactY, ::darkSurface)) return false
            if (wasPressed(x, y)) return true
            // Neutral gray and bevel gradients vary by frame/device. A key is
            // identified by its separate edges as well as its four gray corners;
            // a flat gray panel covering all template locations is insufficient.
            val center = patch(x, y, compactX, compactY)
            val surrounding = listOf(
                patch(x - 35, y, intArrayOf(0), intArrayOf(-5, 5)),
                patch(x + 35, y, intArrayOf(0), intArrayOf(-5, 5)),
                patch(x, y - 34, intArrayOf(-5, 5), intArrayOf(0)),
                patch(x, y + 34, intArrayOf(-5, 5), intArrayOf(0)),
            )
            return surrounding.count { abs(it.light - center.light) >= 18.0 } >= 2
        }
        val darkRows = listOf(
            listOf(922 to 302, 992 to 302, 1062 to 302),
            listOf(922 to 370, 992 to 370, 1062 to 370),
            listOf(922 to 438, 992 to 438, 1062 to 438),
            listOf(992 to 504),
        ).flatten()

        // Skin A: warm charcoal squares, 1-2-3 / 4-5-6 / 7-8-9 / C-0-delete.
        // A key on its own, edged on all four sides (measured 86..129 on the owner's phone): for the
        // wide search, so the big dark answer buttons of a choice screen are never taken for a pad
        // (25 Oct 2026: choices stopped being tapped after the search was widened).
        fun separateKey(x: Int, y: Int): Boolean {
            if (!darkKey(x, y)) return false
            if (wasPressed(x, y)) return true
            val center = patch(x, y, compactX, compactY).light
            return listOf(
                patch(x - 35, y, intArrayOf(0), intArrayOf(-5, 5)), patch(x + 35, y, intArrayOf(0), intArrayOf(-5, 5)),
                patch(x, y - 34, intArrayOf(-5, 5), intArrayOf(0)), patch(x, y + 34, intArrayOf(-5, 5), intArrayOf(0)),
            ).all { abs(it.light - center) >= SEPARATE_KEY_EDGE }
        }
        val allDarkKeys: (Int, Int) -> Boolean = { dx, dy -> darkRows.all { (x, y) -> darkKey(x + dx, y + dy) } }
        val allSeparateKeys: (Int, Int) -> Boolean = { dx, dy -> darkRows.all { (x, y) -> separateKey(x + dx, y + dy) } }
        val darkOffset = centredOffset(allDarkKeys, if (relaxedWide) allDarkKeys else allSeparateKeys)
        if (darkOffset != null) {
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
                val strongGold = submitSamples.count { color ->
                    color.r > 110 && color.g > 65 &&
                        color.r - color.g > 25 && color.g - color.b > 34
                } >= 2
                val paleGold = submitSamples.count { color ->
                    color.r > 135 && color.g > 100 && color.b < 210 &&
                        color.r - color.g in 8.0..80.0 && color.g - color.b >= 20
                } >= 2
                val fillLight = submitSamples.map { it.light }.average()
                val distinctGoldFill = listOf(650 to 438, 650 to 534, 510 to 486, 835 to 486)
                    .count { (x, y) ->
                        abs(patch(x + dx, y + dy, intArrayOf(-2, 2), intArrayOf(-2, 2)).light - fillLight) >= 18
                    } >= 2
                fun rim(xs: IntArray, ys: IntArray): Boolean = xs.any { x -> ys.any { y ->
                    val color = patch(x + dx, y + dy, intArrayOf(-2, 2), intArrayOf(-2, 2))
                    color.light < fillLight - 20 &&
                        max(color.r, max(color.g, color.b)) - min(color.r, min(color.g, color.b)) < 55
                } }
                // Empty input can disable the button with a very pale gold fill,
                // then turn it orange after typing. Its gray rim still proves it
                // is present; plain parchment at these positions must not count.
                val goldRim = listOf(
                    rim(intArrayOf(570, 650, 740), intArrayOf(444, 450, 456)),
                    rim(intArrayOf(570, 650, 740), intArrayOf(518, 524, 530)),
                    rim(intArrayOf(525, 531, 537), intArrayOf(470, 486, 502)),
                    rim(intArrayOf(807, 813, 819), intArrayOf(470, 486, 502)),
                ).count { it } >= 2
                val visibleSubmit = strongGold && distinctGoldFill || paleGold && goldRim
                return Detected(Kind.NUMERIC_DARK, digits = digits,
                    submit = if (visibleSubmit) rect(673 + dx, 486 + dy, 60, 17) else null,
                    // End before the keypad: a highlighted 1/4/7 key is not a changed question.
                    questionArea = rect(650 + dx, 291 + dy, 235, 63),
                    field = rect(673 + dx, 394 + dy, 140, 34),
                    clear = if (darkKey(922 + dx, 504 + dy))
                        rect(922 + dx, 504 + dy, 18, 16) else null)
            }
        }

        val whiteRows = listOf(
            listOf(550 to 364, 653 to 364, 758 to 364, 862 to 364),
            listOf(550 to 470, 653 to 470, 758 to 470),
            listOf(550 to 569, 653 to 569, 758 to 569),
        ).flatten()
        // Only the measured window: no phone has shown the white pad off its place, and white
        // answer buttons would make a wide search risky.
        val whiteOffset = centredOffset(
            { dx, dy -> whiteRows.all { (x, y) -> surface(x + dx, y + dy, lightX, lightY, ::whiteSurface) } },
            null,
        )
        if (whiteOffset != null) {
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
                    field = rect(766 + dx, 217 + dy, 165, 43),
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
