package com.clu.quickanswer.ocr

import kotlin.math.abs

/**
 * Applies a [LayoutTemplate] to a screen: checks that the regions look like they did when the
 * template was learned, then cuts the text lines inside them. The geometry comes from the
 * template, so what [ScreenReader] gets is the same [ScreenLayout] it gets from [ColorLayout].
 *
 * Pure Kotlin, no android.*.
 */
object TemplateReader {
    /** How far (luma) a region's background may be from the learned one and still count as the same. */
    const val SIGNATURE_TOLERANCE = 40

    /** The polarity of a signature is only meaningful when its ink differs from the background this much. */
    private const val POLARITY_MIN_CONTRAST = 40

    /** Pixels left out of the sampled border ring and interior at the region's edge (borders, rounded corners). */
    private const val EDGE_INSET = 2
    private const val RING_THICKNESS = 2

    /**
     * What [rect] looks like: [RegionSignature.background] is the median luma of its 2 px border
     * ring (inset by 2 px), [RegionSignature.ink] the 5th or 95th luma percentile of the inside,
     * whichever is farther from the background.
     */
    fun signature(argb: IntArray, width: Int, height: Int, rect: PxRect): RegionSignature {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val l = rect.left.coerceIn(0, width)
        val t = rect.top.coerceIn(0, height)
        val r = rect.right.coerceIn(0, width)
        val b = rect.bottom.coerceIn(0, height)
        if (r <= l || b <= t) return RegionSignature(0, 0)
        // A region too small for the usual insets is read edge to edge.
        val small = minOf(r - l, b - t) < 2 * (EDGE_INSET + RING_THICKNESS) + 2
        val inset = if (small) 0 else EDGE_INSET
        val thick = if (small) 1 else RING_THICKNESS

        val ringHist = IntArray(256)
        var ringN = 0
        val innerL = l + inset + thick
        val innerR = r - inset - thick
        val innerT = t + inset + thick
        val innerB = b - inset - thick
        for (y in t + inset until b - inset) {
            val rowInside = y in innerT until innerB
            for (x in l + inset until r - inset) {
                if (rowInside && x in innerL until innerR) continue
                ringHist[luma(argb[y * width + x])]++
                ringN++
            }
        }
        val interiorHist = IntArray(256)
        var interiorN = 0
        var y = innerT
        while (y < innerB) {
            var x = innerL
            while (x < innerR) {
                interiorHist[luma(argb[y * width + x])]++
                interiorN++
                x += 2
            }
            y += 2
        }
        val background = if (ringN > 0) percentile(ringHist, ringN, 0.5) else 0
        if (interiorN == 0) return RegionSignature(background, background)
        val low = percentile(interiorHist, interiorN, 0.05)
        val high = percentile(interiorHist, interiorN, 0.95)
        return RegionSignature(background, if (abs(high - background) > abs(low - background)) high else low)
    }

    /** The signatures of [t]'s regions on this screen, in the template's region order. */
    fun signatures(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): List<RegionSignature> =
        t.regions.map { signature(argb, width, height, it.toPx(width, height)) }

    /**
     * Whether the screen still looks like the one [t] was learned on: every region has about the
     * same background and, where the learned region had clearly visible text, the same polarity
     * (light on dark or dark on light). A screen that merely shares the layout (a dialog over the
     * game, another mode) fails here instead of being read as if it were the game.
     */
    fun fits(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): Boolean {
        if (t.signatures.size != t.regions.size) return false
        val now = signatures(t, argb, width, height)
        for ((stored, seen) in t.signatures.zip(now)) {
            if (abs(stored.background - seen.background) > SIGNATURE_TOLERANCE) return false
            if (abs(stored.ink - stored.background) >= POLARITY_MIN_CONTRAST && stored.lightOnDark != seen.lightOnDark) return false
        }
        return true
    }

    /**
     * The [ScreenLayout] [t] gives on this screen, or null when the question has no text lines.
     * An option without text lines is kept as an empty list: [ScreenReader] then declines the
     * screen, which is wanted (a hidden or recoloured option must not be skipped silently).
     */
    fun layoutFor(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): ScreenLayout? {
        val question = t.question.toPx(width, height)
        val questionLines = TextLines.find(argb, width, height, question)
        if (questionLines.isEmpty()) return null
        if (t.arrangement == Arrangement.NUMERIC) {
            val field = t.numericField ?: return null
            return ScreenLayout(
                banner = question,
                buttons = emptyList(),
                numericField = field.toPx(width, height),
                questionLines = questionLines,
                optionLines = emptyList(),
            )
        }
        val buttons = t.options.map { it.toPx(width, height) }
        return ScreenLayout(
            banner = question,
            buttons = buttons,
            numericField = null,
            questionLines = questionLines,
            optionLines = buttons.map { TextLines.find(argb, width, height, it) },
        )
    }

    private fun luma(p: Int): Int = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000

    /** The smallest value with at least [p] of the [n] samples at or below it. */
    private fun percentile(hist: IntArray, n: Int, p: Double): Int {
        val target = Math.ceil(p * n).toInt().coerceAtLeast(1)
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }
}
