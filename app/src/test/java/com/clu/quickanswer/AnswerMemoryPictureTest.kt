package com.clu.quickanswer

import com.clu.quickanswer.ocr.PictureRegion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the picture-aware entries of [AnswerMemory]: a question that shows a picture is the
 * same question only with the same picture ([PictureRegion.hash]), however alike the words and
 * options are.
 */
class AnswerMemoryPictureTest {

    private val q = "أين يقع المعلم السياحي الموجود في الصورة ؟"
    private val opts = listOf("مصر", "الأردن", "اليابان", "المغرب")

    /** A hash as [PictureRegion.hash] writes it. */
    private val picA = "0123456789abcdef".repeat(4)
    private val picB = "fedcba9876543210".repeat(4)

    /** [hash] with its first [n] bits inverted (bit k is the (k % 4)-th bit of character k / 4). */
    private fun flip(hash: String, n: Int, from: Int = 0): String {
        val chars = hash.toCharArray()
        for (k in from until from + n) {
            val i = k / 4
            chars[i] = Character.forDigit(Character.digit(chars[i], 16) xor (1 shl (k % 4)), 16)
        }
        return String(chars)
    }

    @Test
    fun theHelperFlipsExactlyThatManyBits() {
        assertEquals(0, PictureRegion.distance(picA, flip(picA, 0)))
        assertEquals(5, PictureRegion.distance(picA, flip(picA, 5)))
        assertEquals(24, PictureRegion.distance(picA, flip(picA, 24)))
        assertTrue(PictureRegion.distance(picA, picB) > PictureRegion.SAME_PICTURE_MAX)
    }

    @Test
    fun sameWordsAndOptions_withTwoPictures_keepTwoAnswers() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        m.put(q, opts, false, "اليابان", picture = picB)
        assertEquals(2, m.size)
        assertEquals("مصر", m.lookup(q, opts, false, picA)?.answer)
        assertEquals("اليابان", m.lookup(q, opts, false, picB)?.answer)
    }

    @Test
    fun theSamePicture_hitsExactly() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        val hit = m.lookup(q, opts.reversed(), false, picA)
        assertNotNull(hit)
        assertTrue(hit!!.exact)
        assertEquals("مصر", hit.answer)
    }

    @Test
    fun aNearlyIdenticalPicture_hits_butNotExactly() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        val hit = m.lookup(q, opts, false, flip(picA, 5))
        assertNotNull(hit)
        assertFalse(hit!!.exact)
        assertEquals("مصر", hit.answer)
    }

    @Test
    fun theLimitIsSamePictureMax() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        assertNotNull(m.lookup(q, opts, false, flip(picA, PictureRegion.SAME_PICTURE_MAX)))
        assertNull(m.lookup(q, opts, false, flip(picA, PictureRegion.SAME_PICTURE_MAX + 1)))
        assertNull(m.lookup(q, opts, false, picB))
    }

    @Test
    fun ofSeveralNearPictures_theClosestWins() {
        val m = AnswerMemory()
        val near = flip(picA, 16, from = 100)
        m.put(q, opts, false, "مصر", picture = picA)
        m.put(q, opts, false, "الأردن", picture = near)
        assertEquals(2, m.size)
        // 4 bits from picA and 12 from [near], then 12 from picA and 4 from [near].
        assertEquals("مصر", m.lookup(q, opts, false, flip(picA, 4, from = 100))?.answer)
        assertEquals("الأردن", m.lookup(q, opts, false, flip(picA, 12, from = 100))?.answer)
    }

    @Test
    fun aPictureEntry_isNeverReturnedForAPictureLessLookup_andViceVersa() {
        val withPicture = AnswerMemory().apply { put(q, opts, false, "مصر", picture = picA) }
        assertNull(withPicture.lookup(q, opts, false))
        assertNull(withPicture.lookup(q, opts, false, null))

        val without = AnswerMemory().apply { put(q, opts, false, "مصر") }
        assertNull(without.lookup(q, opts, false, picA))
        assertEquals("مصر", without.lookup(q, opts, false)?.answer)
    }

    @Test
    fun aPictureEntryAndAPictureLessEntry_ofTheSameQuestion_livesSideBySide() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر")
        m.put(q, opts, false, "اليابان", picture = picA)
        assertEquals(2, m.size)
        assertEquals("مصر", m.lookup(q, opts, false)?.answer)
        assertEquals("اليابان", m.lookup(q, opts, false, picA)?.answer)
    }

    @Test
    fun aNearPicture_stillNeedsTheSameQuestionAndOptions() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        val near = flip(picA, 3)
        assertNull("other options", m.lookup(q, listOf("مصر", "الأردن", "اليابان", "قطر"), false, near))
        assertNull("other question", m.lookup("أين يقع هذا المعلم السياحي في العالم ؟", opts, false, near))
        // Negation and numbers keep their own entries, as without a picture.
        assertNull(m.lookup("أين لا يقع المعلم السياحي الموجود في الصورة ؟", opts, false, near))
        // Shuffled options and spelling variants are the same exact key as before.
        assertNotNull(m.lookup("اين يقع المعلم السياحي الموجود في الصوره", opts.reversed(), false, near))
    }

    @Test
    fun aNearPicture_isNotMatchedByFuzzyText() {
        // The fuzzy text lookup is for picture-less entries only: with a picture the words must be the same.
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        val oneLetterOff = "أين يقع المعلم السياحي الموجوط في الصورة ؟"
        assertNull(m.lookup(oneLetterOff, opts, false, picA))
        assertNull(m.lookup(oneLetterOff, opts, false, flip(picA, 3)))
    }

    @Test
    fun theFuzzyTextLookup_stillWorksWithoutAPicture() {
        val m = AnswerMemory()
        val long = "ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاب؟"
        val o = listOf("سورة البقرة", "سورة طه", "سورة الإخلاص", "سورة النور")
        m.put(long, o, false, "سورة طه")
        // A picture entry of the same question does not get in the way.
        m.put(long, o, false, "سورة النور", picture = picA)
        val hit = m.lookup("ما هي السورة التي بفضلها أسلم الصحابي الجليل عمر بن الخطاف؟", o, false)
        assertNotNull(hit)
        assertFalse(hit!!.exact)
        assertEquals("سورة طه", hit.answer)
    }

    @Test
    fun aHitCanBeRemovedByItsKey() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        m.put(q, opts, false, "اليابان", picture = picB)
        val hit = m.lookup(q, opts, false, picA)!!
        m.remove(hit.key)
        assertEquals(1, m.size)
        assertNull(m.lookup(q, opts, false, picA))
        assertEquals("اليابان", m.lookup(q, opts, false, picB)?.answer)
    }

    @Test
    fun aPicture_doesNotRelaxWhatPutRefuses() {
        val m = AnswerMemory()
        m.put(q, opts, false, "قطر", picture = picA)
        m.put(q, listOf("مصر", "مصر"), false, "مصر", picture = picA)
        m.put("", opts, false, "مصر", picture = picA)
        assertEquals(0, m.size)
    }

    @Test
    fun aNumericQuestionCanHaveAPicture_too() {
        val m = AnswerMemory()
        val nq = "كم عدد الأشخاص الذين تراهم في الصورة التالية؟"
        m.put(nq, emptyList(), true, "12", picture = picA)
        m.put(nq, emptyList(), true, "7", picture = picB)
        assertEquals("12", m.lookup(nq, emptyList(), true, picA)?.answer)
        assertEquals("7", m.lookup(nq, emptyList(), true, picB)?.answer)
        assertNull(m.lookup(nq, emptyList(), true))
    }

    // ---- persistence

    @Test
    fun encodeRestore_keepsThePictures_andOnlyWritesThemWhenThereIsOne() {
        val m = AnswerMemory()
        m.put(q, opts, false, "مصر", picture = picA)
        m.put(q, opts, false, "اليابان", picture = picB)
        m.put("سؤال بلا صورة طويل بما يكفي", listOf("أ", "ب"), false, "أ")
        val json = m.encode()
        val entries = JSONObject(json).getJSONArray("e")
        val withP = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.has("p") }
        assertEquals(2, withP.size)
        assertEquals(setOf(picA, picB), withP.map { it.getString("p") }.toSet())

        val back = AnswerMemory()
        assertTrue(back.restore(json))
        assertEquals("a second round trip changes nothing", json, back.encode())
        assertEquals(3, back.size)
        assertEquals("مصر", back.lookup(q, opts, false, picA)?.answer)
        assertEquals("اليابان", back.lookup(q, opts, false, picB)?.answer)
        assertNull(back.lookup(q, opts, false))
        assertEquals("أ", back.lookup("سؤال بلا صورة طويل بما يكفي", listOf("أ", "ب"), false)?.answer)
    }

    private fun entryJson(question: String, options: List<String>, answer: String, picture: String? = null): JSONObject =
        JSONObject().put("q", question).put("o", JSONArray(options)).put("n", 0).put("a", answer).also {
            if (picture != null) it.put("p", picture)
        }

    private fun bankOf(vararg entries: JSONObject) = JSONObject().put("v", 1).put("e", JSONArray(entries.toList())).toString()

    @Test
    fun oldFilesWithoutP_loadAsPictureLessEntries() {
        val m = AnswerMemory()
        assertTrue(m.restore(bankOf(entryJson(q, opts, "مصر"))))
        assertEquals(1, m.size)
        assertEquals("مصر", m.lookup(q, opts, false)?.answer)
        assertNull(m.lookup(q, opts, false, picA))
    }

    @Test
    fun merge_keepsPictures_andYourOwnAnswerForTheSamePictureWins() {
        val mine = AnswerMemory()
        mine.put(q, opts, false, "مصر", picture = picA)
        val theirs = AnswerMemory()
        theirs.put(q, opts, false, "الأردن", picture = picA) // the same picture: mine stays
        theirs.put(q, opts, false, "اليابان", picture = picB) // another picture: new
        theirs.put(q, opts, false, "المغرب") // no picture: new
        val r = mine.merge(theirs.encode())!!
        assertEquals(2, r.added)
        assertEquals(3, mine.size)
        assertEquals("مصر", mine.lookup(q, opts, false, picA)?.answer)
        assertEquals("اليابان", mine.lookup(q, opts, false, picB)?.answer)
        assertEquals("المغرب", mine.lookup(q, opts, false)?.answer)
    }

    @Test
    fun merge_ofAnOldFile_addsPictureLessEntries() {
        val mine = AnswerMemory()
        mine.put(q, opts, false, "اليابان", picture = picA)
        val r = mine.merge(bankOf(entryJson(q, opts, "مصر")))!!
        assertEquals(1, r.added)
        assertEquals("مصر", mine.lookup(q, opts, false)?.answer)
        assertEquals("اليابان", mine.lookup(q, opts, false, picA)?.answer)
    }

    @Test
    fun restore_skipsInvalidEntries_pictureOrNot() {
        val m = AnswerMemory()
        assertTrue(m.restore(bankOf(entryJson(q, opts, "قطر", picA), entryJson(q, opts, "مصر", picB))))
        assertEquals(1, m.size)
        assertEquals("مصر", m.lookup(q, opts, false, picB)?.answer)
    }
}
