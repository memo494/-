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
    const val SIGNATURE_TOLERANCE = 25

    /** A region is as uniform as it was when learned, give or take this much (share of its border ring). */
    const val UNIFORMITY_SLACK = 0.2f

    /** Ring pixels this close (luma) to the ring's median count as uniform. */
    private const val UNIFORM_BAND = 25

    /** Pixels left out of the sampled border ring at the region's edge (borders, rounded corners). */
    private const val EDGE_INSET = 2
    private const val RING_THICKNESS = 2

    /** Why [layoutForDetailed] gave no layout, or [OK]. */
    enum class Outcome { OK, NO_QUESTION_TEXT, QUESTION_CLIPPED, OPTION_CLIPPED }

    /** What [layoutForDetailed] found: the [layout] (null unless [outcome] is [Outcome.OK]) and why not. */
    class LayoutResult(val layout: ScreenLayout?, val outcome: Outcome)

    /**
     * What [rect] looks like: [RegionSignature.background] is the median luma of its 2 px border
     * ring (inset by 2 px), [RegionSignature.uniformity] the share of that ring within 25 of the
     * median. Only the ring is used: what is written inside a region changes with every question
     * (and with what the player typed), the frame around it does not.
     */
    fun signature(argb: IntArray, width: Int, height: Int, rect: PxRect): RegionSignature {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val l = rect.left.coerceIn(0, width)
        val t = rect.top.coerceIn(0, height)
        val r = rect.right.coerceIn(0, width)
        val b = rect.bottom.coerceIn(0, height)
        if (r <= l || b <= t) return RegionSignature(0, 0f)
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
        if (ringN == 0) return RegionSignature(0, 0f)
        val background = percentile(ringHist, ringN, 0.5)
        var near = 0
        for (v in maxOf(0, background - UNIFORM_BAND)..minOf(255, background + UNIFORM_BAND)) near += ringHist[v]
        return RegionSignature(background, near.toFloat() / ringN)
    }

    /** The signatures of [t]'s regions on this screen, in the template's region order. */
    fun signatures(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): List<RegionSignature> =
        t.regions.map { signature(argb, width, height, it.toPx(width, height)) }

    /**
     * Whether the screen still looks like the one [t] was learned on: every region has about the
     * same background and its frame is about as uniform. Only the border ring is compared, not
     * the text: how much text there is, and what has been typed into a field, changes all the time.
     * A screen that merely shares the layout (a dialog over the game, another mode) fails here
     * instead of being read as if it were the game. A template with a second look
     * ([LayoutTemplate.altSignatures]) fits when either look matches.
     */
    fun fits(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): Boolean {
        val now = signatures(t, argb, width, height)
        return matches(t.signatures, now) || t.altSignatures?.let { matches(it, now) } == true
    }

    /** Whether the regions look as [stored] says: about the same background, about as uniform (either way). */
    private fun matches(stored: List<RegionSignature>, now: List<RegionSignature>): Boolean {
        if (stored.size != now.size) return false
        for ((s, seen) in stored.zip(now)) {
            if (abs(s.background - seen.background) > SIGNATURE_TOLERANCE) return false
            // Both ways: a region that became much MORE uniform has lost what framed it too (the wood
            // game's number field, seen on its choices screen, is plain banner where the board was).
            if (abs(seen.uniformity - s.uniformity) > UNIFORMITY_SLACK) return false
        }
        return true
    }

    /**
     * The [ScreenLayout] [t] gives on this screen, or null when the question has no text lines or
     * when the question, or (unless numeric) any option, runs out of its rectangle ([TextLines.Result.clipped]):
     * a word may be missing from it, and [ScreenReader] cannot tell. An option without text lines is
     * kept as an empty list: [ScreenReader] then declines the screen, which is wanted (a hidden or
     * recoloured option must not be skipped silently).
     */
    fun layoutFor(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): ScreenLayout? =
        layoutForDetailed(t, argb, width, height).layout

    /** Like [layoutFor], with the reason when there is none. */
    fun layoutForDetailed(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): LayoutResult {
        val question = t.question.toPx(width, height)
        val q = TextLines.findDetailed(argb, width, height, question, t.bounded)
        if (q.lines.isEmpty()) return LayoutResult(null, Outcome.NO_QUESTION_TEXT)
        if (q.clipped) return LayoutResult(null, Outcome.QUESTION_CLIPPED)
        if (t.arrangement == Arrangement.NUMERIC) {
            val field = t.numericField ?: return LayoutResult(null, Outcome.NO_QUESTION_TEXT)
            return LayoutResult(
                ScreenLayout(
                    banner = question,
                    buttons = emptyList(),
                    numericField = field.toPx(width, height),
                    questionLines = q.lines,
                    optionLines = emptyList(),
                ),
                Outcome.OK,
            )
        }
        val buttons = t.options.map { it.toPx(width, height) }
        val options = buttons.map { TextLines.findDetailed(argb, width, height, it, t.bounded) }
        if (options.any { it.clipped }) return LayoutResult(null, Outcome.OPTION_CLIPPED)
        return LayoutResult(
            ScreenLayout(
                banner = question,
                buttons = buttons,
                numericField = null,
                questionLines = q.lines,
                optionLines = options.map { it.lines },
            ),
            Outcome.OK,
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
