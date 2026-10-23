package com.clu.quickanswer.ocr

/**
 * Reads a quiz game's result screen: after an answer, games colour the correct option (typically
 * green) for a moment. Pure Kotlin (no android.*), so it is unit-tested on the JVM.
 *
 * SKELETON: bodies are TODO() until the implementation commit.
 */
object ResultReader {

    /** Share of an option button's width/height left out on each side before sampling. */
    const val INSET = 0.15

    /** Every this-many pixels, each way, is sampled inside an option's interior. */
    const val SAMPLE_STEP = 2

    /** A pixel is green when g >= [GREEN_MIN_G], g - r >= [GREEN_OVER_R] and g - b >= [GREEN_OVER_B]. */
    const val GREEN_MIN_G = 90
    const val GREEN_OVER_R = 40
    const val GREEN_OVER_B = 30

    /** An interior with fewer samples than this is too small to judge (real buttons give thousands). */
    const val MIN_SAMPLES = 25

    /** An option is green when at least this share of its sampled interior is green. */
    const val GREEN_SHARE = 0.35

    /** Another option counts as clearly not green when its share is below this. */
    const val NOT_GREEN_SHARE = 0.10

    /** True when [argb] is green by the rule above (alpha is ignored). */
    fun isGreen(argb: Int): Boolean = TODO()

    /**
     * True when the options can be judged at all: at least two, each inside the image with a
     * non-empty interior, none overlapped by one of [masks].
     */
    fun optionsUsable(width: Int, height: Int, options: List<PxRect>, masks: List<PxRect>): Boolean = TODO()

    /** Index of the one option the result screen shows in green, or null (fail closed). */
    fun correctOption(argb: IntArray, width: Int, height: Int, options: List<PxRect>, masks: List<PxRect>): Int? = TODO()
}

/**
 * Turns the per-check readings of a watch into one decision: act only when the same option is
 * green in [CONFIRMATIONS] consecutive checks, give up after [MAX_UNUSABLE] unusable checks in a row.
 *
 * SKELETON: bodies are TODO() until the implementation commit.
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

    /** One check: whether the options were [usable] and which one was green ([index], null for none). */
    fun offer(usable: Boolean, index: Int?): Step = TODO()

    companion object {
        const val CONFIRMATIONS = 2
        const val MAX_UNUSABLE = 3
    }
}
