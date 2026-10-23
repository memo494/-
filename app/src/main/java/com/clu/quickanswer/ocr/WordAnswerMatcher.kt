package com.clu.quickanswer.ocr

/**
 * Only touch the unique printed option whose OCR words agree with the cloud
 * answer. A model's option number/guessed pixel position is deliberately NOT
 * enough for a 2-column game: it must name the visible Arabic answer.
 */
object WordAnswerMatcher {
    data class Candidate(val text: String, val rect: PxRect, val confidence: Float)

    private const val MIN_CONFIDENCE = 0.65f

    fun match(answer: String, candidates: List<Candidate>): PxRect? {
        if (candidates.size != 4) return null
        val target = ArabicText.key(answer)
        if (target.length < 3 || target.none { ArabicText.isArabicLetter(it) }) return null
        // An unreadable alternative can hide a duplicate answer. All four
        // choices must be trustworthy before a unique match can authorize a tap.
        if (candidates.any { candidate ->
            val rect = candidate.rect
            !candidate.confidence.isFinite() || candidate.confidence !in MIN_CONFIDENCE..1f ||
                candidate.text.none { ArabicText.isArabicLetter(it) } ||
                rect.left < 0 || rect.top < 0 || rect.right <= rect.left || rect.bottom <= rect.top ||
                rect.width < 8 || rect.height < 8
        }) return null
        if (candidates.withIndex().any { (i, candidate) ->
            candidates.drop(i + 1).any { other ->
                val a = candidate.rect
                val b = other.rect
                a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
            }
        }) return null
        // Similarity is useful for showing a proposed answer, but a one-letter
        // name or year difference must never choose a different printed option.
        val matches = candidates.filter { ArabicText.key(it.text) == target }
        return matches.singleOrNull()?.rect
    }
}
