package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMemoryTest {

    private val q = "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟"
    private val opts = listOf("سورة البقرة", "سورة طه", "سورة الإخلاص", "سورة النور")

    @Test
    fun exactHit_returnsTheStoredOptionText() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        val hit = m.lookup(q, opts, false)
        assertNotNull(hit)
        assertEquals("سورة طه", hit!!.answer)
        assertTrue(hit.exact)
    }

    @Test
    fun shuffledOptions_stillHit() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        assertEquals("سورة طه", m.lookup(q, opts.reversed(), false)?.answer)
    }

    @Test
    fun ocrNoise_diacriticsAndLetterVariants_stillHitExactly() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        // Missing space (OCR), hamza dropped, ta marbuta as ha.
        val noisy = "ما هي السوره التي بفضلها اسلم الصحابي الجليل عمربن الخطاب"
        val hit = m.lookup(noisy, listOf("سوره البقره", "سوره طه", "سوره الاخلاص", "سوره النور"), false)
        assertNotNull(hit)
        assertEquals("سورة طه", hit!!.answer)
    }

    @Test
    fun fuzzyHit_onSmallSpellingDifference() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        val oneLetterOff = "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاف؟"
        val hit = m.lookup(oneLetterOff, opts, false)
        assertNotNull(hit)
        assertFalse(hit!!.exact)
    }

    @Test
    fun differentOptions_doNotHit() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        assertNull(m.lookup(q, listOf("سورة البقرة", "سورة يس", "سورة الإخلاص", "سورة النور"), false))
    }

    @Test
    fun differentNumbersInTheQuestion_areDifferentQuestions() {
        val m = AnswerMemory()
        m.put("كم عدد اللاعبين في فريق كرة القدم عام 1990؟", emptyList(), true, "11")
        assertNull(m.lookup("كم عدد اللاعبين في فريق كرة القدم عام 1991؟", emptyList(), true))
    }

    @Test
    fun negation_makesADifferentQuestion() {
        val m = AnswerMemory()
        val a = listOf("مصر", "الأردن", "اليابان", "المغرب")
        m.put("أي من هذه الدول عربية؟", a, false, "مصر")
        assertNull(m.lookup("أي من هذه الدول ليست عربية؟", a, false))
    }

    @Test
    fun numeric_storesTheNumber() {
        val m = AnswerMemory()
        m.put("كم عدد الأشخاص الذين يشاركون في سباق الماراثون الأولمبي؟", emptyList(), true, "80")
        assertEquals("80", m.lookup("كم عدد الأشخاص الذين يشاركون في سباق الماراثون الأولمبي", emptyList(), true)?.answer)
        // A multiple-choice question with the same text is not the numeric one.
        assertNull(m.lookup("كم عدد الأشخاص الذين يشاركون في سباق الماراثون الأولمبي؟", listOf("10", "80"), false))
    }

    @Test
    fun put_overwritesTheSameQuestion() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة البقرة")
        m.put(q, opts.shuffled(), false, "سورة طه")
        assertEquals(1, m.size)
        assertEquals("سورة طه", m.lookup(q, opts, false)?.answer)
    }

    @Test
    fun put_ignoresAnswersThatAreNotAnOption_andAmbiguousOptions() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة يس")
        assertEquals(0, m.size)
        m.put(q, listOf("سورة طه", "سوره طه", "سورة النور"), false, "سورة طه")
        assertEquals(0, m.size)
    }

    @Test
    fun lru_dropsTheLeastRecentlyUsed() {
        val m = AnswerMemory(capacity = 2)
        m.put("سؤال أول طويل بما يكفي", listOf("أ", "ب"), false, "أ")
        m.put("سؤال ثان طويل بما يكفي", listOf("أ", "ب"), false, "ب")
        assertNotNull(m.lookup("سؤال أول طويل بما يكفي", listOf("أ", "ب"), false)) // touch the first
        m.put("سؤال ثالث طويل بما يكفي", listOf("أ", "ب"), false, "أ")
        assertEquals(2, m.size)
        assertNotNull(m.lookup("سؤال أول طويل بما يكفي", listOf("أ", "ب"), false))
        assertNull(m.lookup("سؤال ثان طويل بما يكفي", listOf("أ", "ب"), false))
    }

    @Test
    fun encodeRestore_roundTrips_andBadJsonLeavesItEmpty() {
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        m.put("كم عدد أيام الأسبوع؟", emptyList(), true, "7")
        val copy = AnswerMemory()
        assertTrue(copy.restore(m.encode()))
        assertEquals(2, copy.size)
        assertEquals("سورة طه", copy.lookup(q, opts, false)?.answer)
        assertEquals("7", copy.lookup("كم عدد أيام الأسبوع؟", emptyList(), true)?.answer)
        assertFalse(copy.restore("not json"))
        assertEquals(0, copy.size)
    }

    @Test
    fun remove_andOnChange() {
        val m = AnswerMemory()
        var changes = 0
        m.onChange = { changes++ }
        m.put(q, opts, false, "سورة طه")
        val key = m.lookup(q, opts, false)!!.key
        m.remove(key)
        assertEquals(0, m.size)
        assertEquals(2, changes)
        m.remove(key) // nothing left: no change
        assertEquals(2, changes)
    }
}

class AnswerMemoryMergeTest {
    @org.junit.Test
    fun merge_addsNewQuestions_keepsYourOwnAnswers() {
        val opts = listOf("أ", "ب", "ج")
        val mine = AnswerMemory()
        mine.put("سؤال مشترك طويل بما يكفي", opts, false, "أ")
        val friend = AnswerMemory()
        friend.put("سؤال مشترك طويل بما يكفي", opts, false, "ب")
        friend.put("سؤال جديد عند الصديق", opts, false, "ج")
        org.junit.Assert.assertEquals(1, mine.merge(friend.encode()))
        org.junit.Assert.assertEquals("أ", mine.lookup("سؤال مشترك طويل بما يكفي", opts, false)?.answer)
        org.junit.Assert.assertEquals("ج", mine.lookup("سؤال جديد عند الصديق", opts, false)?.answer)
        org.junit.Assert.assertEquals(-1, mine.merge("{broken"))
        org.junit.Assert.assertEquals(2, mine.size)
    }
}
