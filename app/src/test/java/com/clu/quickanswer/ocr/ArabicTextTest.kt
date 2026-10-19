package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicTextTest {

    @Test
    fun normalize_stripsDiacriticsAndTatweel() {
        assertEquals("السلام", ArabicText.normalize("السَّلامُ"))
        assertEquals("العربيه", ArabicText.normalize("العــربية"))
        assertEquals("عمر بن الخطاب", ArabicText.normalize("عُمَر بن الخطّاب"))
    }

    @Test
    fun normalize_unifiesLetterVariants() {
        assertEquals("احمد ابراهيم امن", ArabicText.normalize("أحمد إبراهيم آمن"))
        assertEquals("علي", ArabicText.normalize("على"))
        assertEquals("مدرسه", ArabicText.normalize("مدرسة"))
        assertEquals("ي", ArabicText.normalize("ی")) // Persian yeh
        assertEquals("ك", ArabicText.normalize("ک")) // Persian keheh
    }

    @Test
    fun normalize_turnsPunctuationIntoSingleSpaces() {
        assertEquals("ما هي السوره", ArabicText.normalize("ما هي السورة؟"))
        assertEquals("عليه السلام", ArabicText.normalize("  عليه   السلام ؟ "))
        assertEquals("a b c", ArabicText.normalize("a,b;c"))
        assertEquals("", ArabicText.normalize(" ؟! "))
    }

    @Test
    fun key_dropsSpacesToo() {
        assertEquals("سورهطه", ArabicText.key("سورة  طه؟"))
        assertEquals(ArabicText.key("سورة ا خلا"), ArabicText.key("سورة اخلا"))
    }

    @Test
    fun levenshtein_countsEdits() {
        assertEquals(0, ArabicText.levenshtein("", ""))
        assertEquals(3, ArabicText.levenshtein("", "abc"))
        assertEquals(3, ArabicText.levenshtein("abc", ""))
        assertEquals(3, ArabicText.levenshtein("kitten", "sitting"))
        assertEquals(1, ArabicText.levenshtein("المارائون", "الماراثون"))
    }

    @Test
    fun similarity_isOneForSameNormalisedTextAndFallsWithEdits() {
        assertEquals(1.0, ArabicText.similarity("سورة طه؟", "سُورَةُ  طه"), 1e-9)
        assertEquals(1.0, ArabicText.similarity("", ""), 1e-9)
        assertEquals(0.0, ArabicText.similarity("", "طه"), 1e-9)
        // One wrong letter out of nine.
        assertEquals(1.0 - 1.0 / 9, ArabicText.similarity("المارائون", "الماراثون"), 1e-9)
        assertTrue(ArabicText.similarity("سورة البقرة", "سورة النور") < 0.8)
    }

    @Test
    fun charAccuracy_scoresAgainstTheReferenceLength() {
        assertEquals(1.0, ArabicText.charAccuracy("سورة طه", "سورة طه"), 1e-9)
        assertEquals(0.0, ArabicText.charAccuracy("", "سورة طه"), 1e-9)
        // Extra characters cannot push the score below zero.
        assertEquals(0.0, ArabicText.charAccuracy("xxxxxxxxxxxxxxxx", "طه"), 1e-9)
        // "طه" (2 letters): one wrong letter -> 0.5.
        assertEquals(0.5, ArabicText.charAccuracy("طب", "طه"), 1e-9)
    }
}
