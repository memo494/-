package com.clu.quickanswer.ocr

/**
 * A [LayoutTemplate] from boxes the user drew by hand on a capture ("معايرة يدوية"), so a new game
 * does not have to wait for automatic learning. Skeleton: the implementation follows its tests.
 *
 * Pure Kotlin, no android.*.
 */
object ManualTemplate {

    /** [LayoutTemplate.source] of a template the user drew. */
    const val SOURCE_MANUAL = "manual"

    /**
     * A TRUSTED template for the capture [argb] ([width] x [height], with our own windows at
     * [masks]) from the boxes drawn on it, or null when they cannot be a sane layout.
     *
     * - Exactly one of: [options] holds [LayoutTemplate.MIN_OPTIONS]..[LayoutTemplate.MAX_OPTIONS]
     *   boxes (multiple choice, [numericField] null), or [numericField] is set and [options] is empty.
     * - Options are put in the order the readers list them (rows from top to bottom, right to left
     *   inside a row, as in Arabic). Arrangement: NUMERIC, GRID when some row holds two or more
     *   options, else VERTICAL.
     * - Null when the image size is wrong, a box is empty or outside the image, a region has a side
     *   under [TemplateLearner.MIN_REGION_SIDE] of the capture, two options overlap by more than
     *   [TemplateLearner.MAX_OVERLAP] of the smaller area, or an option or the number field overlaps
     *   the question by more than that.
     * - fingerprint = [ScreenFingerprint.of]; signatures = [TemplateReader.signature] per region in
     *   [LayoutTemplate.regions] order; packageName and language null; state TRUSTED (the user is
     *   the authority, no confirmations needed); hits and misses 0; source [SOURCE_MANUAL]; not
     *   bounded (hand-drawn edges are not measured); createdAt = lastUsedAt = [now].
     */
    fun build(
        question: PxRect,
        options: List<PxRect>,
        numericField: PxRect?,
        argb: IntArray,
        width: Int,
        height: Int,
        masks: List<PxRect>,
        id: String,
        now: Long,
    ): LayoutTemplate? = TODO("manual calibration")
}
