package com.clu.quickanswer.ocr

import kotlin.math.ceil

/**
 * Reads a quiz game's result screen. After an answer, these games colour the correct option
 * (typically green) for a moment; if it is not the option Clu answered, Clu's answer memory is
 * wrong and the game has just shown the right one. Pure Kotlin (no android.*), so it is unit-tested
 * on the JVM with synthetic screens.
 *
 * Every number below is a first guess: there are NO real captures of these games' result moment
 * yet ("not yet measured on real captures"). The rule is therefore deliberately strict and fails
 * closed: it names an option only when exactly one is clearly green and another is clearly not,
 * otherwise it says nothing and the memory is left alone. A wrong "correction" would overwrite a
 * right answer, which is worse than learning nothing.
 *
 * **Clu's own green box must never count.** [OverlayService] draws it around the answer it chose, in
 * green (HIGHLIGHT_COLOR) and on screen when the game reveals its result, so it is in every capture
 * of that moment. Only the INTERIOR of each button is sampled, [INSET] in from every side:
 * - The box is drawn HIGHLIGHT_PAD_DP (6 dp) outside the button with a HIGHLIGHT_STROKE_DP (4 dp)
 *   stroke inside its own bounds, so the stroke spans 2 to 6 dp outside the button and never
 *   touches it.
 * - Even with no padding at all (stroke drawn over the button's edge), 4 dp is cleared by an inset
 *   of 15% for any button higher than 4 / 0.15 = 27 dp; real buttons are 48 dp and more. The inset
 *   also keeps rounded corners, anti-aliasing and a box that the system placed a few pixels off out
 *   of the sample.
 */
object ResultReader {

    /**
     * Share of an option's width (and of its height) left out on each side before sampling, rounded
     * up. Not yet measured on real captures; chosen to clear Clu's box, see above.
     */
    const val INSET = 0.15

    /**
     * Every this-many pixels, each way, is sampled in the interior: a quarter of the pixels is
     * plenty for a share and keeps one check cheap on a 1080x2400 screen.
     */
    const val SAMPLE_STEP = 2

    /**
     * A pixel is green when g >= [GREEN_MIN_G], g - r >= [GREEN_OVER_R] and g - b >= [GREEN_OVER_B].
     * The values accept the greens games and Clu itself use (Material #4CAF50 = 76,175,80 and Clu's
     * #22C55E = 34,197,94) and reject white text, greys, the original game's cream banner and dark
     * backgrounds. Not yet measured on real captures.
     */
    const val GREEN_MIN_G = 90
    const val GREEN_OVER_R = 40
    const val GREEN_OVER_B = 30

    /**
     * An interior with fewer samples than this is too small to judge. A real button (432x144 px
     * at 15% inset, every 2nd pixel) gives about 7,600; this only refuses nonsense rectangles.
     */
    const val MIN_SAMPLES = 25

    /**
     * An option is green when at least this share of its sampled interior is green. Below 1.0
     * because white text sits on the colour; well above 0 so a green icon or a thin accent is not
     * enough. Not yet measured on real captures.
     */
    const val GREEN_SHARE = 0.35

    /**
     * Another option counts as clearly NOT green when its share is below this. A whole-screen tint
     * or an animation that greens several buttons at once leaves no such option and is declined.
     * Not yet measured on real captures.
     */
    const val NOT_GREEN_SHARE = 0.10

    /** True when [argb] is green by the rule above (alpha is ignored). */
    fun isGreen(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return g >= GREEN_MIN_G && g - r >= GREEN_OVER_R && g - b >= GREEN_OVER_B
    }

    /**
     * True when the options can be judged at all: at least two of them, each inside the
     * [width]x[height] image with an interior big enough to sample, and none overlapped by one of
     * [masks] (our own bubble, panel or preview window, which hide what the game drew there).
     * Any overlap with the whole button counts, not only with the interior: when unsure, decline.
     */
    fun optionsUsable(width: Int, height: Int, options: List<PxRect>, masks: List<PxRect>): Boolean {
        if (width <= 0 || height <= 0 || options.size < 2) return false
        for (r in options) {
            if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height) return false
            val interior = interiorOf(r)
            if (interior.width <= 0 || interior.height <= 0) return false
            if (samplesIn(interior) < MIN_SAMPLES) return false
            if (masks.any { OverlayCheck.overlaps(it, r) }) return false
        }
        return true
    }

    /**
     * The index in [options] of the one option [argb] (a [width]x[height] ARGB image, row by row)
     * shows in green, or null. Null (fail closed) when none is green, when several are, when no
     * other option is clearly not green, or when [optionsUsable] says the options cannot be judged
     * (fewer than two, outside the image, covered by [masks]) or [argb] is too short for the image.
     */
    fun correctOption(argb: IntArray, width: Int, height: Int, options: List<PxRect>, masks: List<PxRect>): Int? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        if (!optionsUsable(width, height, options, masks)) return null
        val shares = options.map { greenShare(argb, width, interiorOf(it)) }
        val green = shares.indices.filter { shares[it] >= GREEN_SHARE }
        if (green.size != 1) return null
        val index = green[0]
        val clearlyNot = shares.indices.any { it != index && shares[it] < NOT_GREEN_SHARE }
        return if (clearlyNot) index else null
    }

    /** [r] without [INSET] of its size on every side (empty or inverted when [r] is tiny). */
    private fun interiorOf(r: PxRect): PxRect {
        val dx = ceil(r.width * INSET).toInt()
        val dy = ceil(r.height * INSET).toInt()
        return PxRect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy)
    }

    private fun samplesIn(r: PxRect): Int {
        if (r.width <= 0 || r.height <= 0) return 0
        return ((r.width + SAMPLE_STEP - 1) / SAMPLE_STEP) * ((r.height + SAMPLE_STEP - 1) / SAMPLE_STEP)
    }

    /** Share (0..1) of the sampled pixels of [r] that are green; [r] must be inside the image and non-empty. */
    private fun greenShare(argb: IntArray, width: Int, r: PxRect): Double {
        var green = 0
        var total = 0
        var y = r.top
        while (y < r.bottom) {
            val row = y * width
            var x = r.left
            while (x < r.right) {
                if (isGreen(argb[row + x])) green++
                total++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (total == 0) 0.0 else green.toDouble() / total
    }
}

/**
 * Turns the readings of a result watch (one per check, see [ResultReader]) into one decision. A
 * single check can be a transition frame (a fade, an animation half-way, a tap ripple), so it acts
 * only when the SAME option is green in [CONFIRMATIONS] consecutive checks; and it gives up after
 * [MAX_UNUSABLE] checks in a row whose options could not be judged at all (covered by our own
 * windows, or off the screen), since the screen is then not one worth waiting on.
 *
 * Not thread-safe; one instance per watch.
 */
class ResultConfirmer {

    sealed class Step {
        /** Keep watching. */
        object Wait : Step()

        /** Stop watching without learning anything. */
        object GiveUp : Step()

        /** The game showed option [index] as correct, confirmed. Stop watching and act on it. */
        class Act(val index: Int) : Step()
    }

    private var candidate: Int? = null
    private var streak = 0
    private var unusable = 0

    /**
     * One check: whether the options were [usable] and which one was green ([index], null for
     * none). An [index] reported for unusable options is ignored.
     */
    fun offer(usable: Boolean, index: Int?): Step {
        if (!usable) {
            candidate = null
            streak = 0
            unusable++
            return if (unusable >= MAX_UNUSABLE) Step.GiveUp else Step.Wait
        }
        unusable = 0
        if (index == null) {
            candidate = null
            streak = 0
            return Step.Wait
        }
        if (index == candidate) streak++ else {
            candidate = index
            streak = 1
        }
        return if (streak >= CONFIRMATIONS) Step.Act(index) else Step.Wait
    }

    companion object {
        /** Consecutive checks that must show the same green option (the lead's rule; not yet measured). */
        const val CONFIRMATIONS = 2

        /** Consecutive unusable checks after which the watch stops (not yet measured). */
        const val MAX_UNUSABLE = 3
    }
}
