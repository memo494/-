package com.clu.quickanswer.ocr

/**
 * Only touch the unique printed option whose OCR words agree with the cloud
 * answer. A model's option number/guessed pixel position is deliberately NOT
 * enough for a 2-column game: it must name the visible Arabic answer.
 */
object WordAnswerMatcher {
    data class Candidate(val text: String, val rect: PxRect, val confidence: Float)

    private const val MIN_TEXT_SIMILARITY = 0.78
    private const val MIN_CONFIDENCE = 0.65f
    private const val MIN_MARGIN = 0.12

    fun match(answer: String, candidates: List<Candidate>): PxRect? {
        if (candidates.size != 4) return null
        val target = ArabicText.key(answer)
        if (target.length < 3 || target.none { ArabicText.isArabicLetter(it) }) return null
        val scores = candidates.map { candidate ->
            if (candidate.confidence < MIN_CONFIDENCE ||
                candidate.text.none { ArabicText.isArabicLetter(it) } ||
                candidate.rect.width < 8 || candidate.rect.height < 8
            ) 0.0 else ArabicText.similarity(answer, candidate.text)
        }
        val ranked = scores.withIndex().sortedByDescending { it.value }
        val first = ranked.getOrNull(0) ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0.0
        if (first.value < MIN_TEXT_SIMILARITY || first.value - second < MIN_MARGIN) return null
        return candidates[first.index].rect
    }
}
