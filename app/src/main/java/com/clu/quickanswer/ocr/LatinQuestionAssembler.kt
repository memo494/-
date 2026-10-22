package com.clu.quickanswer.ocr

/**
 * Pure JVM seam between ML Kit's English text lines and the existing layout engine.
 * Never invents a question or option: every line must lie inside its recognized
 * button/banner, be unobscured by an overlay and agree with the question's language.
 */
object LatinQuestionAssembler {
    data class Segment(val text: String, val rect: PxRect)

    fun assemble(
        layout: ScreenLayout,
        segments: List<Segment>,
        overlayMasks: List<PxRect> = emptyList(),
    ): Question? {
        val regions = buildList {
            add(layout.banner)
            addAll(layout.buttons)
            layout.numericField?.let { add(it) }
        }
        if (overlayMasks.any { mask -> regions.any { region -> overlaps(mask, region) } }) return null
        val lastQuestionY = layout.numericField?.top?.coerceAtMost(layout.banner.bottom) ?: layout.banner.bottom
        val questionArea = PxRect(layout.banner.left, layout.banner.top, layout.banner.right, lastQuestionY)
        val question = linesInside(questionArea, segments).joinToString(" ") { it.text }
        val normalized = ArabicText.normalize(question)
        val letters = normalized.filter(Char::isLetter)
        val latinCount = letters.count { it in 'a'..'z' }
        // A handful of Latin letters in a scoreboard does not turn a screen into an English question.
        if (normalized.length < 9 || normalized.split(' ').count { it.isNotEmpty() } < 2 ||
            latinCount < 5 || latinCount * 2 < letters.length
        ) return null

        val options = if (layout.numeric) emptyList() else layout.buttons.map { button ->
            val words = linesInside(button, segments)
            if (words.isEmpty()) return null
            words.joinToString(" ") { it.text }
        }
        if (!layout.numeric) {
            if (options.size < 2 || options.size > 8) return null
            val keys = options.map { ArabicText.key(it) }
            if (keys.any { it.isBlank() } || keys.toSet().size != keys.size) return null
            if (options.any { option -> option.none { it.isLetterOrDigit() } }) return null
        }
        return Question(
            questionText = question,
            options = options,
            optionRects = layout.buttons,
            numeric = layout.numeric,
            // ML Kit Latin Text Recognition v2 does not expose the same per-character
            // confidence as our Arabic ONNX model. Zero means unavailable, not low.
            meanConfidence = 0f
        )
    }

    private fun linesInside(area: PxRect, segments: List<Segment>): List<Segment> =
        segments.filter {
            it.text.isNotBlank() &&
                it.rect.centerX >= area.left && it.rect.centerX < area.right &&
                it.rect.centerY >= area.top && it.rect.centerY < area.bottom
        }.sortedWith(compareBy<Segment> { it.rect.top }.thenBy { it.rect.left })

    private fun overlaps(a: PxRect, b: PxRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
