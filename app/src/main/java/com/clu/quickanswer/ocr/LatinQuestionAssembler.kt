package com.clu.quickanswer.ocr

/**
 * Pure JVM seam between ML Kit's English text lines and the existing layout engine.
 * Never invents a question or option: every line must lie inside its recognized
 * button/banner, be unobscured by an overlay and agree with the question's language.
 */
object LatinQuestionAssembler {
    // These mirror the Arabic reader (ScreenReader.MIN_LINE_CONFIDENCE / DEFAULT_MIN_CONFIDENCE)
    // and must be calibrated on real ML Kit captures before being trusted.
    const val MIN_LINE_CONFIDENCE = 0.6f
    const val MIN_MEAN_CONFIDENCE = 0.75f

    /** One ML Kit text line: its text, box in bitmap pixels and recognition confidence (0..1). */
    data class Segment(val text: String, val rect: PxRect, val confidence: Float)

    fun assemble(
        layout: ScreenLayout,
        segments: List<Segment>,
        overlayMasks: List<PxRect> = emptyList(),
    ): Question? {
        // Same rule as the Arabic reader, plus the stricter banner rule: any mask touching the banner rejects.
        if (OverlayCheck.covers(layout, overlayMasks)) return null
        if (overlayMasks.any { OverlayCheck.overlaps(it, layout.banner) }) return null
        val lastQuestionY = layout.numericField?.top?.coerceAtMost(layout.banner.bottom) ?: layout.banner.bottom
        val questionArea = PxRect(layout.banner.left, layout.banner.top, layout.banner.right, lastQuestionY)
        val questionSegments = linesInside(questionArea, segments)
        val question = questionSegments.joinToString(" ") { it.text }
        val normalized = ArabicText.normalize(question)
        val letters = normalized.filter(Char::isLetter)
        val latinCount = letters.count { it in 'a'..'z' }
        // A handful of Latin letters in a scoreboard does not turn a screen into an English question.
        if (normalized.length < 9 || normalized.split(' ').count { it.isNotEmpty() } < 2 ||
            latinCount < 5 || latinCount * 2 < letters.length
        ) return null

        val optionSegments = ArrayList<Segment>()
        val options = if (layout.numeric) emptyList() else layout.buttons.map { button ->
            val words = linesInside(button, segments)
            if (words.isEmpty()) return null
            optionSegments.addAll(words)
            words.joinToString(" ") { it.text }
        }
        if (!layout.numeric) {
            if (options.size < 2 || options.size > 8) return null
            val keys = options.map { ArabicText.key(it) }
            if (keys.any { it.isBlank() } || keys.toSet().size != keys.size) return null
            if (options.any { option -> option.none { it.isLetterOrDigit() } }) return null
        }
        // Fail closed: a line without a real confidence (0 = unavailable, or NaN) is not trusted.
        val used = questionSegments + optionSegments
        if (used.any { !(it.confidence >= MIN_LINE_CONFIDENCE) }) return null
        val questionMean = weightedMean(questionSegments)
        val overallMean = weightedMean(used)
        if (!(questionMean >= MIN_MEAN_CONFIDENCE) || !(overallMean >= MIN_MEAN_CONFIDENCE)) return null
        return Question(
            questionText = question,
            options = options,
            optionRects = layout.buttons,
            numeric = layout.numeric,
            // Text.Line.getConfidence() exists in ML Kit Text Recognition v2 and the bundled
            // model returns real values; the gate above already rejected unavailable (0) ones.
            meanConfidence = overallMean
        )
    }

    private fun linesInside(area: PxRect, segments: List<Segment>): List<Segment> =
        segments.filter {
            it.text.isNotBlank() &&
                it.rect.centerX >= area.left && it.rect.centerX < area.right &&
                it.rect.centerY >= area.top && it.rect.centerY < area.bottom
        }.sortedWith(compareBy<Segment> { it.rect.top }.thenBy { it.rect.left })

    /** Mean confidence weighted by normalized text length (at least 1 per line). */
    private fun weightedMean(lines: List<Segment>): Float {
        var sum = 0.0
        var weight = 0.0
        for (l in lines) {
            val w = ArabicText.normalize(l.text).length.coerceAtLeast(1).toDouble()
            sum += l.confidence * w
            weight += w
        }
        return if (weight == 0.0) 0f else (sum / weight).toFloat()
    }
}
