package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CtcTest {

    private val labels = arrayOf("", "a", "b", "c")

    /** One-hot-ish probabilities: class [best] gets [p], the rest share what is left. */
    private fun probs(vararg best: Int, p: Float = 0.9f): CtcLogits {
        val c = labels.size
        val v = FloatArray(best.size * c)
        for ((t, b) in best.withIndex()) {
            for (k in 0 until c) v[t * c + k] = if (k == b) p else (1f - p) / (c - 1)
        }
        return CtcLogits(best.size, c, v)
    }

    @Test
    fun greedyDecode_dropsBlanksAndCollapsesRepeats() {
        // a a _ a b b c  ->  a (a a collapse) , a again after the blank, b, c
        val d = Ctc.greedyDecode(probs(1, 1, 0, 1, 2, 2, 3), labels)
        assertEquals("aabc", d.text)
        assertEquals(4, d.charCount)
        assertEquals(4 * 0.9, d.probSum, 1e-5)
    }

    @Test
    fun greedyDecode_allBlankIsEmpty() {
        val d = Ctc.greedyDecode(probs(0, 0, 0), labels)
        assertEquals("", d.text)
        assertEquals(0, d.charCount)
    }

    @Test
    fun greedyDecode_softmaxesRawLogits() {
        // Raw logits (not summing to 1): best class 8, others 0 -> p = e^8 / (e^8 + 3).
        val v = floatArrayOf(0f, 8f, 0f, 0f)
        val d = Ctc.greedyDecode(CtcLogits(1, 4, v), labels)
        assertEquals("a", d.text)
        val e8 = Math.exp(8.0)
        assertEquals(e8 / (e8 + 3), d.probSum, 1e-6)
    }

    @Test
    fun greedyDecode_rejectsADictionaryOfTheWrongSize() {
        assertThrows(IllegalArgumentException::class.java) {
            Ctc.greedyDecode(probs(1), arrayOf("", "a"))
        }
    }

    @Test
    fun predReverse_reversesArabicCharactersOneByOne() {
        // The model emits visual left-to-right order; reading order is the reverse.
        assertEquals("جبا", Ctc.predReverse("ابج"))
    }

    @Test
    fun predReverse_keepsLatinAndDigitRunsIntact() {
        assertEquals("abc", Ctc.predReverse("abc"))
        // visual: 12 س ab  ->  reading: ab س 12
        assertEquals("abس12", Ctc.predReverse("12سab"))
        assertEquals("a:b/c.d-e+f%g*h", Ctc.predReverse("a:b/c.d-e+f%g*h"))
    }

    @Test
    fun predReverse_treatsSpacesAsPartOfRuns() {
        // visual: ب[space]ا -> reading: ا[space]ب
        assertEquals("ا ب", Ctc.predReverse("ب ا"))
    }

    @Test
    fun predReverse_keepsArabicIndicDigitsInOrder() {
        // visual: ب ٢٠٢٤ ا -> reading: ا ٢٠٢٤ ب (a number reads left to right)
        assertEquals("ا٢٠٢٤ب", Ctc.predReverse("ب٢٠٢٤ا"))
    }

    @Test
    fun predReverse_emptyStaysEmpty() {
        assertEquals("", Ctc.predReverse(""))
    }

    @Test
    fun parseDictionary_ignoresTheTrailingNewlineAndCarriageReturns() {
        assertEquals(listOf("a", "b"), LineRecognizer.parseDictionary("a\nb\n"))
        assertEquals(listOf("a", "b"), LineRecognizer.parseDictionary("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), LineRecognizer.parseDictionary("a\nb"))
    }
}
