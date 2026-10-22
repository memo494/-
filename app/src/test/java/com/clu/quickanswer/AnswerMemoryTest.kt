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
    fun englishQuestion_isFoundOfflineDespiteCaseAndOrderChanges() {
        val memory = AnswerMemory()
        val options = listOf("Amman", "Cairo", "DOHA", "Beirut")
        memory.put("What is the capital of Jordan?", options, false, "Amman")
        val hit = memory.lookup("WHAT IS THE CAPITAL OF JORDAN?", listOf("beirut", "doha", "AMMAN", "cairo"), false)
        assertNotNull(hit)
        assertTrue(hit!!.exact)
        assertEquals("Amman", hit.answer)
    }

    @Test
    fun englishNegationAndNumbersPreventWrongOfflineAnswers() {
        val memory = AnswerMemory()
        val options = listOf("Earth", "Mars", "Pluto", "Venus")
        memory.put("Which of these is NOT a planet in 2026?", options, false, "Pluto")
        assertNull(memory.lookup("Which of these is a planet in 2026?", options, false))
        assertNull(memory.lookup("Which of these is not a planet in 2025?", options, false))
    }

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
    fun put_ignoresNumericAnswersThatAreNotNumbers_andTooLongText() {
        val m = AnswerMemory()
        m.put("كم عدد أيام الأسبوع؟", emptyList(), true, "سبعة")
        m.put("كم عدد أيام الأسبوع؟", emptyList(), true, "7 أيام")
        m.put("ا".repeat(AnswerMemory.MAX_QUESTION_CHARS + 1), emptyList(), true, "7")
        assertEquals(0, m.size)
        m.put("كم عدد أيام الأسبوع؟", emptyList(), true, "٧")
        assertEquals(1, m.size)
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
        val result = mine.merge(friend.encode())!!
        org.junit.Assert.assertEquals(1, result.added)
        org.junit.Assert.assertEquals(0, result.noRoom)
        org.junit.Assert.assertEquals("أ", mine.lookup("سؤال مشترك طويل بما يكفي", opts, false)?.answer)
        org.junit.Assert.assertEquals("ج", mine.lookup("سؤال جديد عند الصديق", opts, false)?.answer)
        org.junit.Assert.assertNull(mine.merge("{broken"))
        org.junit.Assert.assertEquals(2, mine.size)
    }

    private val opts = listOf("أ", "ب", "ج")

    private fun question(i: Int) = "سؤال رقم $i من البنك الكبير جدا"

    @org.junit.Test
    fun merge_neverEvictsYourOwnEntries_whenTheBankIsBigger() {
        val mine = AnswerMemory(capacity = 150)
        for (i in 0 until 100) mine.put("سؤال خاص بي رقم $i", opts, false, "ب")
        val friend = AnswerMemory()
        for (i in 0 until 200) friend.put(question(i), opts, false, "ج")
        val result = mine.merge(friend.encode())!!
        org.junit.Assert.assertEquals(50, result.added)
        org.junit.Assert.assertEquals(150, result.noRoom)
        org.junit.Assert.assertEquals(150, mine.size)
        for (i in 0 until 100) org.junit.Assert.assertNotNull("own $i", mine.lookup("سؤال خاص بي رقم $i", opts, false))
        // Of the incoming entries the most recently used (the end of the file) are the ones that fit.
        org.junit.Assert.assertNotNull(mine.lookup(question(199), opts, false))
        org.junit.Assert.assertNull(mine.lookup(question(0), opts, false))
    }

    @org.junit.Test
    fun merge_ofAFullMemory_addsNothing() {
        val mine = AnswerMemory(capacity = 2)
        mine.put("سؤال أول طويل بما يكفي", opts, false, "أ")
        mine.put("سؤال ثان طويل بما يكفي", opts, false, "ب")
        val friend = AnswerMemory()
        friend.put("سؤال ثالث طويل بما يكفي", opts, false, "ج")
        val result = mine.merge(friend.encode())!!
        org.junit.Assert.assertEquals(0, result.added)
        org.junit.Assert.assertEquals(1, result.noRoom)
        org.junit.Assert.assertEquals(2, mine.size)
    }

    private fun entryJson(q: String, o: List<String>, n: Int, a: String): String {
        val arr = org.json.JSONArray(o)
        return org.json.JSONObject().put("q", q).put("o", arr).put("n", n).put("a", a).toString()
    }

    private fun bankOf(vararg entries: String) = "{\"v\":1,\"e\":[${entries.joinToString(",")}]}"

    @org.junit.Test
    fun merge_andRestore_skipEntriesThatPutWouldRefuse() {
        val huge = "ا".repeat(200_000)
        val bank = bankOf(
            entryJson("كم عدد أيام الأسبوع؟", emptyList(), 1, "حمّل التطبيق من موقعنا www.example.com"), // numeric, not a number
            entryJson("ما عاصمة ألمانيا حاليا؟", listOf("باريس", "روما", "مدريد"), 0, "برلين"), // answer not an option
            entryJson("ما أكبر المحيطات في العالم؟", listOf("الهادي", "الهادي", "الهندي"), 0, "الهادي"), // duplicate options
            entryJson(huge, listOf("أ", "ب"), 0, "أ"), // question too long
            entryJson("سؤال بإجابة طويلة جدا؟", listOf("أ", "ب"), 0, "أ".repeat(1000)),
            entryJson("سؤال بخيارات كثيرة جدا؟", List(20) { "خيار $it" }, 0, "خيار 1"),
            entryJson("ما هو أطول نهر في العالم؟", listOf("النيل", "الأمازون", "الفرات"), 0, "النيل"), // fine
            entryJson("كم عدد أيام السنة الميلادية؟", emptyList(), 1, "٣٦٥"), // fine
        )
        val m = AnswerMemory()
        org.junit.Assert.assertEquals(2, m.merge(bank)!!.added)
        org.junit.Assert.assertEquals(2, m.size)
        org.junit.Assert.assertNull(m.lookup("كم عدد أيام الأسبوع؟", emptyList(), true))
        org.junit.Assert.assertEquals("النيل", m.lookup("ما هو أطول نهر في العالم؟", listOf("الفرات", "النيل", "الأمازون"), false)?.answer)
        val r = AnswerMemory()
        org.junit.Assert.assertTrue(r.restore(bank))
        org.junit.Assert.assertEquals(2, r.size)
    }

    @org.junit.Test
    fun restore_readsAtMostTheGivenNumberOfEntries_andSurvivesGarbage() {
        val m = AnswerMemory()
        for (i in 0 until 10) m.put(question(i), opts, false, "ب")
        val copy = AnswerMemory()
        org.junit.Assert.assertTrue(copy.restore(m.encode(), maxEntries = 4))
        org.junit.Assert.assertEquals(4, copy.size)
        // Deeply nested or plainly broken input is "not readable", never an exception.
        org.junit.Assert.assertFalse(copy.restore("{\"e\":" + "[".repeat(100_000)))
        org.junit.Assert.assertEquals(0, copy.size)
        org.junit.Assert.assertFalse(copy.restore(""))
    }

    @org.junit.Test
    fun readAtMost_stopsAtTheLimit() {
        val data = ByteArray(1000) { 7 }
        org.junit.Assert.assertEquals(1000, AnswerMemory.readAtMost(java.io.ByteArrayInputStream(data), 1000)?.size)
        org.junit.Assert.assertNull(AnswerMemory.readAtMost(java.io.ByteArrayInputStream(data), 999))
        org.junit.Assert.assertEquals(0, AnswerMemory.readAtMost(java.io.ByteArrayInputStream(ByteArray(0)), 10)?.size)
    }
}

/** Questions that differ by one word are different questions, however alike their letters are. */
class AnswerMemoryNearDuplicateTest {

    private val planets = listOf("المشتري", "عطارد", "زحل", "المريخ")
    private val suras = listOf("البقرة", "الإخلاص", "الفاتحة", "الكوثر")
    private val currencies = listOf("الجنيه", "الريال", "الدينار", "الدولار")
    private val caliphs = listOf("أبو بكر الصديق", "عمر بن الخطاب", "عثمان بن عفان", "علي بن أبي طالب")

    private fun assertDifferent(a: String, b: String, options: List<String>, answer: String) {
        val m = AnswerMemory()
        m.put(a, options, false, answer)
        assertNull("'$b' must not hit the answer of '$a'", m.lookup(b, options, false))
    }

    private val animals = listOf("Dog", "Cat", "Whale", "Bat")
    private val ringPlanets = listOf("Saturn", "Mars", "Jupiter", "Uranus")
    private val birds = listOf("Penguin", "Eagle", "Sparrow", "Owl")

    @Test
    fun contraction_isnt_isNegation() =
        assertDifferent("Which of these isn't a mammal?", "Which of these is a mammal?", animals, "Bat")

    @Test
    fun contraction_isnt_withCurlyApostrophe_isNegation() =
        assertDifferent("Which of these isn\u2019t a mammal?", "Which of these is a mammal?", animals, "Bat")

    @Test
    fun contraction_doesnt_isNegation() =
        assertDifferent("Which planet doesn't have rings?", "Which planet does have rings?", ringPlanets, "Mars")

    @Test
    fun contraction_cant_isNegation() =
        assertDifferent("Which bird can't fly?", "Which bird can fly?", birds, "Penguin")

    @Test
    fun cannot_isNegation() =
        assertDifferent("Which bird cannot fly?", "Which bird can fly?", birds, "Penguin")

    @Test
    fun englishContraction_identicalQuestionStillHitsExactly() {
        val m = AnswerMemory()
        m.put("Which bird can't fly?", birds, false, "Penguin")
        val hit = m.lookup("Which bird can't fly?", birds, false)
        assertNotNull(hit)
        assertTrue(hit!!.exact)
        assertEquals("Penguin", hit.answer)
    }

    @Test
    fun oneSwappedWord_isNotTheSameQuestion() {
        assertDifferent("ما هو أكبر كوكب في المجموعة الشمسية؟", "ما هو أصغر كوكب في المجموعة الشمسية؟", planets, "المشتري")
        assertDifferent("ما هي أطول سورة في القرآن الكريم؟", "ما هي أقصر سورة في القرآن الكريم؟", suras, "البقرة")
        assertDifferent("ما هي عملة مصر؟", "ما هي عملة قطر؟", currencies, "الجنيه")
        assertDifferent("من هو أول خليفة للمسلمين بعد الرسول؟", "من هو آخر خليفة للمسلمين بعد الرسول؟", caliphs, "أبو بكر الصديق")
    }

    @Test
    fun numericQuestionsThatDifferByOneWord_areDifferent() {
        val pairs = listOf(
            "كم عدد ركعات صلاة الفجر المفروضة على المسلم في اليوم؟" to "كم عدد ركعات صلاة الظهر المفروضة على المسلم في اليوم؟",
            "كم عدد سكان مصر حسب آخر إحصاء رسمي في العالم؟" to "كم عدد سكان قطر حسب آخر إحصاء رسمي في العالم؟",
            "كم عدد اللاعبين في فريق كرة القدم داخل الملعب؟" to "كم عدد اللاعبين في فريق كرة اليد داخل الملعب؟",
        )
        for ((a, b) in pairs) {
            val m = AnswerMemory()
            m.put(a, emptyList(), true, "11")
            assertNull("'$b' must not hit the answer of '$a'", m.lookup(b, emptyList(), true))
            // The first question itself, with OCR noise (one letter, a dropped diacritic), still hits.
            assertNotNull(m.lookup(a.replace("؟", ""), emptyList(), true))
        }
    }

    @Test
    fun readingNoise_stillHitsFuzzily() {
        val q = "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟"
        val opts = listOf("سورة البقرة", "سورة طه", "سورة الإخلاص", "سورة النور")
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        // A wrong letter in a long word, a merged pair of words, a split word, a stray one-letter word.
        for (noisy in listOf(
            "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاف؟",
            "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمربن الخطاف؟",
            "ما هي السورة التي بفضلها أسلم الصحابي الجل يل عمر بن الخطاف؟",
            "ما هي السورة التي بفضلها أسلم الصحابي الجليل ا عمر بن الخطاف؟",
        )) {
            val hit = m.lookup(noisy, opts, false)
            assertNotNull(noisy, hit)
            assertEquals("سورة طه", hit!!.answer)
        }
    }

    @Test
    fun correctingAFuzzyHit_doesNotDeleteTheOtherQuestionsAnswer() {
        // What OverlayService does: a fuzzy hit is corrected by storing the answer under the question's
        // own key; the entry that was hit belongs to another question and stays.
        val q = "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟"
        val similar = "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاف؟"
        val opts = listOf("سورة البقرة", "سورة طه", "سورة الإخلاص", "سورة النور")
        val m = AnswerMemory()
        m.put(q, opts, false, "سورة طه")
        val hit = m.lookup(similar, opts, false)!!
        assertFalse(hit.exact)
        m.put(similar, opts, false, "سورة النور")
        assertEquals("سورة طه", m.lookup(q, opts, false)?.answer)
        assertEquals("سورة النور", m.lookup(similar, opts, false)?.answer)
        assertEquals(2, m.size)
    }

    @Test
    fun numbersWithSeparators_keepTheirOwnEntries() {
        val m = AnswerMemory()
        m.put("ما ناتج 1.5 × 2؟", emptyList(), true, "3")
        assertNull(m.lookup("ما ناتج 15 × 2؟", emptyList(), true))
        m.put("ما ناتج 12 - 4؟", emptyList(), true, "8")
        assertNull(m.lookup("ما ناتج 1 - 24؟", emptyList(), true))
        m.put("ما ناتج 12 / 4؟", emptyList(), true, "3")
        assertNull(m.lookup("ما ناتج 124؟", emptyList(), true))
        // The same question still hits exactly, in any digit script.
        assertEquals("8", m.lookup("ما ناتج ١٢ - ٤؟", emptyList(), true)?.answer)
    }

    @Test
    fun wordsCompatible_followsTheWordRules() {
        fun ok(a: String, b: String) = AnswerMemory.wordsCompatible(AnswerMemory.wordsOf(a), AnswerMemory.wordsOf(b))
        assertTrue(ok("ما هو أكبر كوكب", "ما هو أكبر كوكب"))
        assertTrue(ok("عمر بن الخطاب", "عمربن الخطاب")) // merged
        assertTrue(ok("عمربن الخطاب", "عمر بن الخطاب")) // split
        assertTrue(ok("الخطاب", "الخطاف")) // one letter in a six-letter word
        assertFalse(ok("أكبر", "أصغر")) // two letters
        assertFalse(ok("مصر", "قطر")) // no letter may differ in a short word
        assertFalse(ok("ما هو أكبر كوكب", "ما هو كوكب")) // a word missing
        assertTrue(ok("ما هو أكبر كوكب", "ما هو ا أكبر كوكب")) // a stray letter
    }
}
