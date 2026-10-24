package com.clu.quickanswer.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the question banner, the answer buttons and the tight text lines of the quiz screen from
 * colour alone. It is a port of the lab prototype (layout.py), which reached 0.997 character
 * accuracy together with the recogniser.
 *
 *  1. Region finding works on a copy downscaled to [REGION_LONG_SIDE] px on the long side, so it is
 *     independent of the capture resolution and of portrait/landscape.
 *  2. The question banner is the biggest connected blob of cream pixels.
 *  3. Answer buttons are near-white, wide, mostly filled rectangles below the middle of the banner
 *     that overlap it horizontally. One that starts above the banner's bottom edge is the number
 *     entry box of a numeric question, not an option.
 *  4. Shape checks decline screens that merely contain a pale box and white rectangles (a settings
 *     tip, a chat notice, a dialog): the banner must be wide and about 3.5-8 times wider than high,
 *     the answer buttons one regular, centred stack under it (same size, evenly spaced), a numeric
 *     question needs its keypad. See [inspect].
 *  5. Text lines inside a region: Otsu on the grey ROI, row projection into bands, column extent
 *     of each band. The boxes are TIGHT (+-2 px); vertical padding made recognition clearly worse
 *     (0.997 -> 0.854), so none is added.
 *
 * Pixel constants in step 4 were tuned on 2712 px captures; they are scaled by
 * `longSide / REFERENCE_LONG_SIDE` so other capture sizes behave the same.
 */
class ColorLayout {

    /** Banner, wide white boxes and squarish white boxes (keypad keys) in input-image pixels. */
    private class Regions(val banner: PxRect, val buttons: List<PxRect>, val keys: List<PxRect>)

    /** What [inspect] made of a screen: the [layout], or null with the [rejection] reason. */
    class Result(val layout: ScreenLayout?, val rejection: String?)

    /** Null when the screen does not look like the quiz (see [inspect]). [argb] is row-major ARGB (alpha ignored). */
    fun analyze(argb: IntArray, width: Int, height: Int): ScreenLayout? = inspect(argb, width, height).layout

    /** Like [analyze], but says why a screen was declined. */
    fun inspect(argb: IntArray, width: Int, height: Int): Result {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val regions = findRegions(argb, width, height) ?: return Result(null, "no question banner")
        val unit = max(width, height).toDouble() / REFERENCE_LONG_SIDE

        val banner = regions.banner
        fun reject(why: String) = Result(null, why)
        val bannerAspect = banner.width.toDouble() / max(1, banner.height)
        if (bannerAspect < MIN_BANNER_ASPECT || bannerAspect > MAX_BANNER_ASPECT) {
            return reject("banner shape (aspect %.2f)".format(bannerAspect))
        }
        if (banner.width < MIN_BANNER_WIDTH_FRACTION * width) return reject("banner too narrow")
        val belowBanner = regions.buttons.filter { it.top >= banner.bottom }
        val field = regions.buttons.firstOrNull { it.top < banner.bottom }
        val shapeProblem = if (field != null) {
            numericProblem(banner, field, belowBanner, regions.keys)
        } else {
            stackProblem(banner, belowBanner, width)
        }
        if (shapeProblem != null) return reject(shapeProblem)

        // A white box (answer field / first button) may overlap the banner bottom: stop above it.
        var bannerBottom = banner.bottom
        for (b in regions.buttons) {
            if (banner.top < b.top && b.top < bannerBottom) bannerBottom = b.top
        }
        val questionLines = textLines(
            argb, width, height,
            PxRect(banner.left, banner.top, banner.right, bannerBottom),
            0.13f, 0.85f, 0.12f, 0.98f, unit,
        )

        // Two or more text lines are one banner; one that is a big part of the banner is several lines
        // merged by something painted over them.
        if (questionLines.any { it.height > MAX_LINE_FRACTION * banner.height }) return reject("question text band merged")

        val options = if (field != null) emptyList() else belowBanner
        val optionLines = options.map { textLines(argb, width, height, it, 0.04f, 0.96f, 0.12f, 0.88f, unit) }
        val numericKeys = if (field != null) NumericKeypad.detect(field, regions.keys) else emptyMap()
        return Result(
            ScreenLayout(
                banner = banner,
                buttons = options,
                numericField = field,
                questionLines = questionLines,
                optionLines = optionLines,
                digitKeys = numericKeys,
                numericSubmit = if (numericKeys.size == 10) NumericSubmit.detect(argb, width, height, numericKeys) else null,
            ),
            null,
        )
    }

    /**
     * Why the number [field] under [banner] is not the number entry of this game, or null when it
     * is: it is centred, about a third of the banner wide, has no answer buttons next to it and
     * sits on a keypad ([keys] are the squarish white boxes).
     */
    private fun numericProblem(banner: PxRect, field: PxRect, belowBanner: List<PxRect>, keys: List<PxRect>): String? {
        if (belowBanner.size >= 2) return "number field together with answer buttons"
        val fieldWidth = field.width.toDouble() / banner.width
        if (fieldWidth < MIN_FIELD_WIDTH || fieldWidth > MAX_FIELD_WIDTH) return "number field width"
        if (abs(field.centerX - banner.centerX) > MAX_CENTRE_OFFSET * banner.width) return "number field off-centre"
        val keypad = keys.count {
            it.top >= field.bottom && it.height >= 0.5 * field.height && it.height <= 2.5 * field.height
        }
        return if (keypad < MIN_KEYPAD_KEYS) "no keypad under the number field" else null
    }

    /**
     * Why [options] (the wide white boxes under [banner]) are not this game's answer stack, or null
     * when they are (or there are none): all the same size, centred under the banner and not as wide
     * as the screen, one after the other with no hole (a missing one is hidden or recoloured).
     */
    private fun stackProblem(banner: PxRect, options: List<PxRect>, imageWidth: Int): String? {
        if (options.isEmpty()) return null
        val medianWidth = options.map { it.width }.sorted()[options.size / 2]
        val medianHeight = options.map { it.height }.sorted()[options.size / 2]
        for (b in options) {
            val ratio = b.width.toDouble() / banner.width
            if (ratio < MIN_BUTTON_WIDTH || ratio > MAX_BUTTON_WIDTH) return "button width"
            if (abs(b.width - medianWidth) > WIDTH_TOLERANCE * medianWidth ||
                abs(b.height - medianHeight) > HEIGHT_TOLERANCE * medianHeight
            ) return "buttons differ in size"
            if (abs(b.centerX - banner.centerX) > MAX_CENTRE_OFFSET * banner.width) return "buttons off-centre"
            if (b.left <= 0 || b.right >= imageWidth) return "button touches the screen edge"
        }
        if (options.first().top - banner.bottom > MAX_FIRST_GAP * medianHeight) return "first button far below the banner"
        for ((a, b) in options.zipWithNext()) {
            if (b.top - a.top > MAX_PITCH * medianHeight) return "a button is missing in the stack"
        }
        return null
    }

    private fun findRegions(argb: IntArray, width: Int, height: Int): Regions? {
        val s = REGION_LONG_SIDE.toDouble() / max(width, height)
        val dw = max(1, (width * s).toInt())
        val dh = max(1, (height * s).toInt())
        val small = ImageOps.areaResize(argb, width, height, dw, dh)
        val hsv = ImageOps.toHsv(small, dw, dh)

        val cream = BooleanArray(dw * dh) {
            val h = hsv.h[it].toInt() and 0xFF
            val sat = hsv.s[it].toInt() and 0xFF
            val v = hsv.v[it].toInt() and 0xFF
            h in 20..34 && sat in 40..120 && v > 215
        }
        val creamBlobs = ImageOps.blobs(ImageOps.close(cream, dw, dh, 5), dw, dh)
        if (creamBlobs.isEmpty()) return null
        var big = creamBlobs[0]
        for (b in creamBlobs) if (b.area > big.area) big = b
        val bx = big.left
        val by = big.top
        val bw = big.width
        val bh = big.height

        val white = BooleanArray(dw * dh) {
            val sat = hsv.s[it].toInt() and 0xFF
            val v = hsv.v[it].toInt() and 0xFF
            sat < 30 && v > 228
        }
        val buttons = ArrayList<PxRect>()
        val keys = ArrayList<PxRect>()
        for (b in ImageOps.blobs(ImageOps.close(white, dw, dh, 3), dw, dh)) {
            if (b.height < 6) continue
            val aspect = b.width.toDouble() / b.height
            if (b.area < 0.6 * b.width * b.height) continue // must be a mostly filled rectangle
            val cx = b.left + b.width / 2.0
            if (cx < bx || cx > bx + bw || b.top < by + 0.5 * bh) continue
            val rect = PxRect(b.left, b.top, b.left + b.width, b.top + b.height)
            if (aspect in 0.6..1.7) {
                keys.add(rect) // squarish: a keypad key
            } else if (b.width >= 0.25 * bw && aspect >= 2.5 && aspect <= 14.0) {
                buttons.add(rect)
            }
        }
        buttons.sortBy { it.top }

        fun up(r: PxRect) = PxRect(
            Math.rint(r.left / s).toInt().coerceIn(0, width),
            Math.rint(r.top / s).toInt().coerceIn(0, height),
            Math.rint(r.right / s).toInt().coerceIn(0, width),
            Math.rint(r.bottom / s).toInt().coerceIn(0, height),
        )
        return Regions(up(PxRect(bx, by, bx + bw, by + bh)), buttons.map { up(it) }, keys.map { up(it) })
    }

    /**
     * Tight boxes of the text lines inside [box], top to bottom. [x0]..[x1] and [y0]..[y1] are the
     * fractions of the box to look at (the banner edges hold icons, the button edges a border).
     */
    private fun textLines(
        argb: IntArray, width: Int, height: Int, box: PxRect,
        x0: Float, x1: Float, y0: Float, y1: Float, unit: Double,
    ): List<PxRect> {
        val rx0 = (box.left + (box.width * x0).toInt()).coerceIn(0, width)
        val rx1 = (box.left + (box.width * x1).toInt()).coerceIn(0, width)
        val ry0 = (box.top + (box.height * y0).toInt()).coerceIn(0, height)
        val ry1 = (box.top + (box.height * y1).toInt()).coerceIn(0, height)
        val w = rx1 - rx0
        val h = ry1 - ry0
        if (w <= 0 || h <= 0) return emptyList()

        val gray = ImageOps.grayRoi(argb, width, rx0, ry0, rx1, ry1)
        var sum = 0.0
        for (g in gray) sum += g
        val mean = sum / gray.size
        var sq = 0.0
        for (g in gray) sq += (g - mean) * (g - mean)
        if (Math.sqrt(sq / gray.size) < 12.0) return emptyList() // flat region: no text

        val cutoff = min(ImageOps.otsu(gray).toDouble(), mean - 25.0)
        val dark = BooleanArray(gray.size) { gray[it] < cutoff }
        val rowMin = max(2.0, 0.004 * w)
        val bands = ArrayList<IntArray>()
        var start = -1
        for (y in 0..h) {
            var on = false
            if (y < h) {
                var n = 0
                val base = y * w
                for (x in 0 until w) if (dark[base + x]) n++
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

        // +-2 px around the ink, more only for captures bigger than the reference size. Going lower
        // on small captures cost accuracy (half-size S2: 0.86-0.90 instead of 1.0 on three options).
        val margin = max(2, Math.rint(2.0 * unit).toInt())
        val out = ArrayList<PxRect>()
        for ((a, b) in merged.map { it[0] to it[1] }) {
            if (b - a < 0.35 * tallest) continue // stray marks
            var first = -1
            var last = -1
            for (x in 0 until w) {
                var any = false
                for (y in a until b) if (dark[y * w + x]) { any = true; break }
                if (any) {
                    if (first < 0) first = x
                    last = x
                }
            }
            if (first < 0) continue
            val lh = b - a
            val px = (PAD_X * lh).toInt() + margin
            val py = margin
            out.add(
                PxRect(
                    max(rx0, rx0 + first - px),
                    max(ry0, ry0 + a - py),
                    min(rx1, rx0 + last + px),
                    min(ry1, ry0 + b + py),
                )
            )
        }
        return out
    }

    companion object {
        /** Long side of the working copy the regions are found on. */
        const val REGION_LONG_SIDE = 540

        /** Capture size (long side) the pixel constants of the line cutting were tuned on. */
        const val REFERENCE_LONG_SIDE = 2712

        /** Horizontal padding added to a line as a fraction of its height (vertical padding is harmful). */
        private const val PAD_X = 0.25

        // The game's shapes, measured on screenshots (S1-S3, also at 3/4 and 1/2 size); a screen
        // outside them is not this quiz. Banner: aspect 4.9-5.3, 0.71-0.79 of the screen width.
        private const val MIN_BANNER_ASPECT = 3.5
        private const val MAX_BANNER_ASPECT = 8.0
        private const val MIN_BANNER_WIDTH_FRACTION = 0.5

        // Answer buttons (S2 = S3): 0.46 of the banner wide, centred, pitch 1.3-1.5 button heights.
        private const val MIN_BUTTON_WIDTH = 0.30
        private const val MAX_BUTTON_WIDTH = 0.65
        private const val WIDTH_TOLERANCE = 0.05
        private const val HEIGHT_TOLERANCE = 0.20
        private const val MAX_CENTRE_OFFSET = 0.08
        private const val MAX_FIRST_GAP = 1.5
        private const val MAX_PITCH = 1.8

        // Number field (S1): 0.30 of the banner wide, with a keypad of 10 keys under it.
        private const val MIN_FIELD_WIDTH = 0.20
        private const val MAX_FIELD_WIDTH = 0.45
        private const val MIN_KEYPAD_KEYS = 6

        // A text line is 0.15-0.21 of the banner; a merged band is 0.5 or more.
        private const val MAX_LINE_FRACTION = 0.30
    }
}
