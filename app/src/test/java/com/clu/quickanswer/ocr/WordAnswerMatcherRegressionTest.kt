package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class WordAnswerMatcherRegressionTest {
    private val choices = listOf(
        WordAnswerMatcher.Candidate("محمد ولي", PxRect(300, 100, 440, 130), 0.95f),
        WordAnswerMatcher.Candidate("هاني سعيد", PxRect(100, 100, 240, 130), 0.95f),
        WordAnswerMatcher.Candidate("خالد عمر", PxRect(300, 200, 440, 230), 0.95f),
        WordAnswerMatcher.Candidate("أحمد سالم", PxRect(100, 200, 240, 230), 0.95f),
    )

    @Test fun similarNameNeverSubstitutesForTheRequestedPrintedName() {
        assertNull(WordAnswerMatcher.match("محمد علي", choices))
        assertEquals(choices[0].rect, WordAnswerMatcher.match("محمد ولي", choices))
    }

    @Test fun oneDifferentYearDigitCannotSelectTheWrongOption() {
        val years = choices.mapIndexed { index, candidate ->
            candidate.copy(text = listOf("عام 1999", "عام 1914", "عام 1945", "عام 2001")[index])
        }
        assertNull(WordAnswerMatcher.match("عام 1998", years))
        assertEquals(years[0].rect, WordAnswerMatcher.match("عام 1999", years))
    }

    @Test fun invalidConfidenceAnywhereRefusesTheWholeChoiceSet() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 0.64f, 1.01f)) {
            for (index in choices.indices) {
                val invalid = choices.mapIndexed { i, candidate ->
                    if (i == index) candidate.copy(confidence = bad) else candidate
                }
                assertNull("confidence=$bad on option $index", WordAnswerMatcher.match("محمد ولي", invalid))
            }
        }
        assertEquals(choices[0].rect,
            WordAnswerMatcher.match("محمد ولي", choices.map { it.copy(confidence = 0.65f) }))
        assertEquals(choices[0].rect,
            WordAnswerMatcher.match("محمد ولي", choices.map { it.copy(confidence = 1f) }))
    }

    @Test fun anUnreadableAlternativeCannotBeIgnoredToProduceAUniqueMatch() {
        for (unreadable in listOf("", "...", "1234", "Paris")) {
            val invalid = choices.mapIndexed { index, candidate ->
                if (index == 3) candidate.copy(text = unreadable) else candidate
            }
            assertNull(unreadable, WordAnswerMatcher.match("محمد ولي", invalid))
        }
        val ambiguous = choices.mapIndexed { index, candidate ->
            if (index == 3) candidate.copy(text = "محمد ولي", confidence = 0.1f) else candidate
        }
        assertNull(WordAnswerMatcher.match("محمد ولي", ambiguous))
    }

    @Test fun harmlessArabicNormalizationStillMatchesExactlyAndUniquely() {
        val variants = choices.mapIndexed { index, candidate ->
            if (index == 0) candidate.copy(text = "أحمد علي") else candidate
        }
        assertEquals(variants[0].rect, WordAnswerMatcher.match("  إِحْمَد   عَلي  ", variants))
        assertEquals(choices[0].rect, WordAnswerMatcher.match("مُحَمَّد وَلِي", choices))
        assertEquals(choices[3].rect, WordAnswerMatcher.match("احمد سالم", choices))
    }

    @Test fun duplicateNormalizedAnswersAreAmbiguousEvenWithDifferentSpelling() {
        val duplicates = choices.mapIndexed { index, candidate ->
            if (index == 0) candidate.copy(text = "احمد سالم") else candidate
        }
        assertNull(WordAnswerMatcher.match("أحمد سالم", duplicates))
    }

    @Test fun proseAndOptionNumbersDoNotAuthorizeChoiceTaps() {
        for (answer in listOf("1", "١", "الخيار 1", "1) محمد ولي", "الإجابة هي محمد ولي", "محمد ولي هو الجواب")) {
            assertNull(answer, WordAnswerMatcher.match(answer, choices))
        }
    }

    @Test fun invalidCoordinatesOnAnyChoiceRefuseTheWholeSet() {
        for (bad in listOf(
            PxRect(-1, 200, 240, 230), PxRect(100, -1, 240, 230),
            PxRect(100, 200, 100, 230), PxRect(100, 200, 240, 200),
            PxRect(240, 200, 100, 230), PxRect(100, 230, 240, 200),
            PxRect(100, 200, 107, 230), PxRect(100, 200, 240, 207),
        )) {
            val invalid = choices.mapIndexed { index, candidate ->
                if (index == 3) candidate.copy(rect = bad) else candidate
            }
            assertNull(bad.toString(), WordAnswerMatcher.match("محمد ولي", invalid))
        }
    }

    @Test fun duplicateOrOverlappingChoiceRectanglesAreNeverTapped() {
        for (bad in listOf(choices[0].rect, PxRect(420, 110, 470, 140), choices[2].rect)) {
            val invalid = choices.mapIndexed { index, candidate ->
                if (index == 3) candidate.copy(rect = bad) else candidate
            }
            assertNull(bad.toString(), WordAnswerMatcher.match("محمد ولي", invalid))
        }
    }
}
