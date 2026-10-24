package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class WordAnswerMatcherTest {
    private val rightTop = PxRect(875, 385, 989, 420)
    private val leftTop = PxRect(601, 386, 688, 416)
    private val rightBottom = PxRect(897, 475, 994, 505)
    private val leftBottom = PxRect(618, 472, 703, 503)
    private val choices = listOf(
        WordAnswerMatcher.Candidate("سبونج بوب", rightTop, 0.95f),
        WordAnswerMatcher.Candidate("شفيق", leftTop, 0.92f),
        WordAnswerMatcher.Candidate("بسيط", rightBottom, 0.88f),
        WordAnswerMatcher.Candidate("ساندي", leftBottom, 0.91f),
    )

    @Test fun tapsPrintedCorrectWordsNotAnOptionNumber() {
        assertEquals(rightTop, WordAnswerMatcher.match("سبونج بوب", choices))
        assertEquals(leftTop, WordAnswerMatcher.match("شفيق", choices))
        assertEquals(rightBottom, WordAnswerMatcher.match("بسيط", choices))
        assertEquals(leftBottom, WordAnswerMatcher.match("ساندي", choices))
        assertNull(WordAnswerMatcher.match("1", choices))
        assertNull(WordAnswerMatcher.match("الخيار 3", choices))
    }

    @Test fun refusesAmbiguousUnreadableOrUnmatchedWords() {
        assertNull(WordAnswerMatcher.match("شفيق", choices.dropLast(1)))
        assertNull(WordAnswerMatcher.match("ستيف جوبز", choices))
        assertNull(WordAnswerMatcher.match("شفيق",
            choices.map { if (it.text == "شفيق") it.copy(confidence = 0.2f) else it }))
        assertNull(WordAnswerMatcher.match("شفيق",
            choices.mapIndexed { index, c -> if (index == 2) c.copy(text = "شفيق") else c }))
    }

    @Test fun acceptsSafeArabicOrthographicVariants() {
        val corrected = choices.toMutableList()
        corrected[0] = corrected[0].copy(text = "سبونج بوب")
        assertEquals(rightTop, WordAnswerMatcher.match("سَبُونج بوب", corrected))
    }
}
