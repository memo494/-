package com.clu.quickanswer.ocr

/**
 * A [LayoutTemplate] from boxes the user drew by hand on a capture ("معايرة يدوية"), so a new game
 * does not have to wait for automatic learning. The user is the authority on where the question and
 * the buttons are, so the template is TRUSTED at once; what is still checked is that the boxes can
 * be a layout at all (see [build]), because a slip of the finger must not become a template that
 * misreads every later question.
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
     *   inside a row, as in Arabic; see [RegionGeometry.readingRows]), whatever order they were drawn
     *   in. Arrangement: NUMERIC, GRID when some row holds two or more options, else VERTICAL.
     * - Null when the image size is wrong, a box is empty or outside the image, a region has a side
     *   under [TemplateLearner.MIN_REGION_SIDE] of the capture, two options overlap by more than
     *   [TemplateLearner.MAX_OVERLAP] of the smaller area, or an option or the number field overlaps
     *   the question by more than that (the same limits as a template learned from a model's boxes).
     * - fingerprint = [ScreenFingerprint.of]; signatures = [TemplateReader.signature] per region in
     *   [LayoutTemplate.regions] order; packageName and language null; state TRUSTED; hits and
     *   misses 0; source [SOURCE_MANUAL]; not bounded (hand-drawn edges are not measured, so text is
     *   still looked for a little beyond them); createdAt = lastUsedAt = [now].
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
    ): LayoutTemplate? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        val choices = options.size in LayoutTemplate.MIN_OPTIONS..LayoutTemplate.MAX_OPTIONS && numericField == null
        val numeric = options.isEmpty() && numericField != null
        if (!choices && !numeric) return null

        val rows = RegionGeometry.readingRows(options, leftToRight = false) { it }
        val orderedOptions = rows.flatten()
        val arrangement = when {
            numeric -> Arrangement.NUMERIC
            rows.any { it.size >= 2 } -> Arrangement.GRID
            else -> Arrangement.VERTICAL
        }
        val pxRegions = listOf(question) + orderedOptions + listOfNotNull(numericField)
        if (pxRegions.any { !insideImage(it, width, height) }) return null
        val regions = pxRegions.map { RelRect.fromPx(it, width, height) }
        if (regions.any { !it.isValid || it.right - it.left < TemplateLearner.MIN_REGION_SIDE || it.bottom - it.top < TemplateLearner.MIN_REGION_SIDE }) {
            return null
        }
        // Every pair: option against option, option or field against the question.
        for (i in 1 until regions.size) {
            for (j in 0 until i) {
                if (RegionGeometry.overlapsTooMuch(regions[i], regions[j], TemplateLearner.MAX_OVERLAP)) return null
            }
        }

        val template = LayoutTemplate(
            id = id,
            packageName = null,
            fingerprint = ScreenFingerprint.of(argb, width, height, masks),
            arrangement = arrangement,
            question = regions[0],
            options = regions.subList(1, 1 + orderedOptions.size),
            numericField = if (numeric) regions.last() else null,
            signatures = pxRegions.map { TemplateReader.signature(argb, width, height, it) },
            language = null,
            state = TemplateState.TRUSTED,
            hits = 0,
            misses = 0,
            source = SOURCE_MANUAL,
            createdAt = now,
            lastUsedAt = now,
        )
        return template.takeIf { it.isValid }
    }

    private fun insideImage(r: PxRect, width: Int, height: Int): Boolean =
        r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height && r.left < r.right && r.top < r.bottom
}
