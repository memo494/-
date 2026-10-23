package com.clu.quickanswer

import com.clu.quickanswer.AnswerSource.CALCULATOR
import com.clu.quickanswer.AnswerSource.FAILED
import com.clu.quickanswer.AnswerSource.IMAGE
import com.clu.quickanswer.AnswerSource.MEMORY
import com.clu.quickanswer.AnswerSource.PICTURE
import com.clu.quickanswer.AnswerSource.TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AnswerStatsTest {

    private val d1 = "2026-10-22"
    private val d2 = "2026-10-23"

    /** Day number 1..n as "2026-09-NN" (n <= 30), so day order is the string order. */
    private fun day(n: Int) = "2026-09-%02d".format(n)

    private fun summary(counts: Map<AnswerSource, Int>, averages: Map<AnswerSource, Long> = emptyMap()) =
        AnswerStats.Summary(counts, averages)

    // ---- counting --------------------------------------------------------------------------

    @Test
    fun countsPerSource_andEverySourceIsListed() {
        val s = AnswerStats()
        s.record(MEMORY, 100, d1)
        s.record(MEMORY, 100, d1)
        s.record(TEXT, 1000, d1)
        val today = s.today(d1)
        assertEquals(2, today.count[MEMORY])
        assertEquals(1, today.count[TEXT])
        // Sources never recorded are there with 0, so a caller never meets null.
        for (src in AnswerSource.values()) assertTrue("$src", today.count.containsKey(src))
        assertEquals(0, today.count[PICTURE])
        assertEquals(3, today.total)
    }

    @Test
    fun total_includesFailures() {
        val s = AnswerStats()
        s.record(IMAGE, 2000, d1)
        s.record(FAILED, 500, d1)
        assertEquals(2, s.today(d1).total)
        assertEquals(2, s.allTime().total)
    }

    @Test
    fun average_isTotalOverCount() {
        val s = AnswerStats()
        s.record(TEXT, 100, d1)
        s.record(TEXT, 200, d1)
        s.record(IMAGE, 1000, d1)
        s.record(IMAGE, 1000, d1)
        s.record(IMAGE, 1001, d1)
        val avg = s.today(d1).averageMillis
        assertEquals(150L, avg[TEXT])
        // 3001 / 3 = 1000.33
        assertEquals(1000L, avg[IMAGE])
    }

    @Test
    fun average_roundsHalfUp() {
        val s = AnswerStats()
        s.record(TEXT, 1, d1)
        s.record(TEXT, 2, d1)
        // 3 / 2 = 1.5
        assertEquals(2L, s.today(d1).averageMillis[TEXT])
    }

    @Test
    fun average_isOnlyThereForSourcesWithAnswers() {
        val s = AnswerStats()
        s.record(MEMORY, 80, d1)
        val avg = s.today(d1).averageMillis
        assertEquals(setOf(MEMORY), avg.keys)
        assertNull(avg[TEXT])
    }

    @Test
    fun negativeTime_countsAsZero() {
        val s = AnswerStats()
        s.record(TEXT, -500, d1)
        s.record(TEXT, 300, d1)
        assertEquals(2, s.today(d1).count[TEXT])
        assertEquals(150L, s.today(d1).averageMillis[TEXT])
    }

    @Test
    fun malformedDay_isNotRecordedAnywhere() {
        val s = AnswerStats()
        for (bad in listOf("", "today", "2026-1-5", "2026-10-23T10:00", "26-10-23", "2026/10/23", " 2026-10-23")) {
            s.record(TEXT, 100, bad)
        }
        assertEquals(0, s.allTime().total)
        assertEquals(0, s.today("today").total)
    }

    // ---- days --------------------------------------------------------------------------------

    @Test
    fun days_areKeptApart_andAllTimeAddsThemUp() {
        val s = AnswerStats()
        s.record(MEMORY, 100, d1)
        s.record(TEXT, 1000, d1)
        s.record(TEXT, 3000, d2)
        assertEquals(1, s.today(d1).count[MEMORY])
        assertEquals(0, s.today(d2).count[MEMORY])
        assertEquals(1000L, s.today(d1).averageMillis[TEXT])
        assertEquals(3000L, s.today(d2).averageMillis[TEXT])
        val all = s.allTime()
        assertEquals(3, all.total)
        assertEquals(2, all.count[TEXT])
        assertEquals(2000L, all.averageMillis[TEXT])
    }

    @Test
    fun unknownDay_isAnEmptySummary() {
        val s = AnswerStats()
        s.record(TEXT, 100, d1)
        val none = s.today("2030-01-01")
        assertEquals(0, none.total)
        assertTrue(none.averageMillis.isEmpty())
    }

    @Test
    fun keepsTheLast30Days_butAllTimeKeepsEverything() {
        val s = AnswerStats()
        // 31 days: 2026-08-31 and then 2026-09-01..30
        s.record(TEXT, 100, "2026-08-31")
        for (n in 1..30) s.record(TEXT, 100, day(n))
        assertEquals("oldest day dropped", 0, s.today("2026-08-31").total)
        assertEquals("second oldest kept", 1, s.today(day(1)).total)
        assertEquals(1, s.today(day(30)).total)
        assertEquals("all-time keeps the dropped day", 31, s.allTime().total)
    }

    @Test
    fun exactly30Days_areAllKept() {
        val s = AnswerStats()
        for (n in 1..30) s.record(TEXT, 100, day(n))
        for (n in 1..30) assertEquals("day $n", 1, s.today(day(n)).total)
    }

    @Test
    fun aDayOlderThanEverythingKept_isNotKeptWhenFull_butStillCountsAllTime() {
        val s = AnswerStats()
        for (n in 1..30) s.record(TEXT, 100, day(n))
        // The phone's clock went back: an old day arrives when 30 newer ones are held.
        s.record(TEXT, 100, "2025-01-01")
        assertEquals(0, s.today("2025-01-01").total)
        assertEquals(1, s.today(day(1)).total)
        assertEquals(31, s.allTime().total)
    }

    // ---- encode / restore ------------------------------------------------------------------

    @Test
    fun encodeRestore_roundTrip() {
        val s = AnswerStats()
        s.record(MEMORY, 90, d1)
        s.record(TEXT, 1200, d1)
        s.record(IMAGE, 2600, d2)
        s.record(FAILED, 700, d2)
        s.record(CALCULATOR, 5, d2)
        s.record(PICTURE, 1800, d2)
        val copy = AnswerStats()
        assertTrue(copy.restore(s.encode()))
        for (d in listOf(d1, d2)) {
            assertEquals(s.today(d).count, copy.today(d).count)
            assertEquals(s.today(d).averageMillis, copy.today(d).averageMillis)
        }
        assertEquals(s.allTime().count, copy.allTime().count)
        assertEquals(s.allTime().averageMillis, copy.allTime().averageMillis)
        // And it keeps counting from there.
        copy.record(MEMORY, 110, d2)
        assertEquals(1, copy.today(d2).count[MEMORY])
        assertEquals(2, copy.allTime().count[MEMORY])
    }

    @Test
    fun encode_ofAnEmptyStats_restoresToEmpty() {
        val copy = AnswerStats()
        copy.record(TEXT, 1, d1)
        assertTrue(copy.restore(AnswerStats().encode()))
        assertEquals(0, copy.allTime().total)
        assertEquals(0, copy.today(d1).total)
    }

    @Test
    fun restore_replacesWhatWasThere() {
        val a = AnswerStats()
        a.record(TEXT, 100, d1)
        val b = AnswerStats()
        b.record(IMAGE, 100, d2)
        assertTrue(a.restore(b.encode()))
        assertEquals(0, a.allTime().count[TEXT])
        assertEquals(1, a.allTime().count[IMAGE])
        assertEquals(0, a.today(d1).total)
    }

    @Test
    fun restore_garbage_isFalse_andLeavesItEmpty() {
        val garbage = listOf(
            "", "   ", "not json", "{", "[]", "[1,2,3]", "null", "{}", "{\"v\":1}",
            "{\"v\":1,\"all\":5,\"days\":{}}",
            "{\"v\":1,\"all\":{},\"days\":[]}",
            "[".repeat(100_000),
        )
        for (g in garbage) {
            val s = AnswerStats()
            s.record(TEXT, 100, d1)
            assertFalse("restore of <${g.take(30)}>", s.restore(g))
            assertEquals("empty after <${g.take(30)}>", 0, s.allTime().total)
            assertEquals(0, s.today(d1).total)
        }
    }

    @Test
    fun restore_skipsBadPartsAndKeepsTheRest() {
        val json = """
            {"v":1,
             "all":{"TEXT":[3,3000],"FROM_THE_FUTURE":[9,9],"MEMORY":[-1,5],"IMAGE":[1],"FAILED":["x","y"],"PICTURE":[2,200]},
             "days":{"2026-10-23":{"TEXT":[2,2000],"NOPE":[1,1],"IMAGE":[1,-5]},
                     "yesterday":{"TEXT":[1,1]},
                     "2026-10-22":"broken"}}
        """.trimIndent()
        val s = AnswerStats()
        assertTrue(s.restore(json))
        val all = s.allTime()
        assertEquals(3, all.count[TEXT])
        assertEquals(1000L, all.averageMillis[TEXT])
        assertEquals(2, all.count[PICTURE])
        assertEquals(0, all.count[MEMORY])
        assertEquals(0, all.count[IMAGE])
        assertEquals(0, all.count[FAILED])
        assertEquals(5, all.total)
        assertEquals(2, s.today(d2).count[TEXT])
        assertEquals(0, s.today(d2).count[IMAGE])
        assertEquals(0, s.today("yesterday").total)
        assertEquals(0, s.today(d1).total)
    }

    @Test
    fun restore_keepsOnlyThe30LatestDays() {
        val days = StringBuilder()
        // 40 days, 2026-09-01 .. 2026-10-10 in string order; written in scrambled order on purpose.
        val all = (1..40).map { n -> if (n <= 30) day(n) else "2026-10-%02d".format(n - 30) }
        for (d in all.reversed()) {
            if (days.isNotEmpty()) days.append(',')
            days.append("\"$d\":{\"TEXT\":[1,100]}")
        }
        val s = AnswerStats()
        assertTrue(s.restore("{\"v\":1,\"all\":{\"TEXT\":[40,4000]},\"days\":{$days}}"))
        for (d in all.take(10)) assertEquals("dropped $d", 0, s.today(d).total)
        for (d in all.drop(10)) assertEquals("kept $d", 1, s.today(d).total)
        assertEquals(40, s.allTime().total)
    }

    @Test
    fun restore_wrongTypesInside_areSkippedNotThrown() {
        val s = AnswerStats()
        assertTrue(s.restore("""{"v":1,"all":{"TEXT":"3"},"days":{"2026-10-23":{"TEXT":null}}}"""))
        assertEquals(0, s.allTime().total)
        // And it is usable afterwards.
        s.record(TEXT, 1, d1)
        assertEquals(1, s.today(d1).total)
    }

    @Test
    fun restore_refusesAbsurdNumbers_andCountsSaturate() {
        val s = AnswerStats()
        // 2^31-1 questions of one source would overflow the total; it is a corrupt file.
        assertTrue(s.restore("""{"v":1,"all":{"TEXT":[2147483647,5],"IMAGE":[2,9223372036854775807]},"days":{}}"""))
        assertEquals(0, s.allTime().total)
        // The largest count believed stays there however many answers come.
        assertTrue(s.restore("""{"v":1,"all":{"TEXT":[100000000,0]},"days":{}}"""))
        s.record(TEXT, 10, d1)
        assertEquals(100_000_000, s.allTime().count[TEXT])
    }

    // ---- clear / onChange --------------------------------------------------------------------

    @Test
    fun clear_forgetsEverything() {
        val s = AnswerStats()
        s.record(TEXT, 100, d1)
        s.record(IMAGE, 100, d2)
        s.clear()
        assertEquals(0, s.allTime().total)
        assertEquals(0, s.today(d1).total)
        assertEquals(0, s.today(d2).total)
        assertTrue(s.today(d1).averageMillis.isEmpty())
        s.record(TEXT, 100, d1)
        assertEquals(1, s.allTime().total)
    }

    @Test
    fun onChange_firesOnRecordAndClear_notOnRestoreOrRejectedRecords() {
        val s = AnswerStats()
        val fired = AtomicInteger()
        s.onChange = { fired.incrementAndGet() }
        s.record(TEXT, 100, d1)
        assertEquals(1, fired.get())
        s.record(TEXT, 100, "bad day")
        assertEquals("a rejected record changes nothing", 1, fired.get())
        s.clear()
        assertEquals(2, fired.get())
        s.clear()
        assertEquals("clearing an empty stats changes nothing", 2, fired.get())
        s.restore(AnswerStats().apply { record(TEXT, 1, d1) }.encode())
        assertEquals("restore is the loader, not a change", 2, fired.get())
    }

    @Test
    fun threadSafe_underConcurrentRecords() {
        val s = AnswerStats()
        val threads = 4
        val each = 2000
        val go = CountDownLatch(1)
        val workers = List(threads) { t ->
            thread {
                go.await()
                repeat(each) { s.record(if (t % 2 == 0) TEXT else IMAGE, 10, d1) }
            }
        }
        go.countDown()
        workers.forEach { it.join() }
        assertEquals(threads * each, s.allTime().total)
        assertEquals(threads * each, s.today(d1).total)
        assertEquals(10L, s.allTime().averageMillis[TEXT])
    }

    // ---- describe ----------------------------------------------------------------------------

    @Test
    fun describe_theOwnersExample() {
        val today = summary(
            mapOf(MEMORY to 8, CALCULATOR to 1, TEXT to 10, PICTURE to 0, IMAGE to 3, FAILED to 1),
            mapOf(MEMORY to 100L, TEXT to 1200L, IMAGE to 2600L),
        )
        val all = summary(mapOf(MEMORY to 140))
        assertEquals(
            "اليوم: 23 سؤال — ذاكرة ⚡ 8، حاسبة 1، نص 10، صورة مقصوصة 0، صورة كاملة 3، فشل 1\n" +
                "متوسط الوقت: ذاكرة 0.1 ث، نص 1.2 ث، صورة كاملة 2.6 ث\n" +
                "الكل: 140 سؤال",
            AnswerStats.describe(today, all),
        )
    }

    @Test
    fun describe_showsAnAverageForEverySourceThatHasOne_inOrder() {
        val today = summary(
            mapOf(MEMORY to 1, CALCULATOR to 1, TEXT to 1, PICTURE to 1, IMAGE to 1),
            mapOf(IMAGE to 2600L, PICTURE to 1800L, TEXT to 1200L, CALCULATOR to 100L, MEMORY to 100L),
        )
        val lines = AnswerStats.describe(today, summary(mapOf(MEMORY to 5))).lines()
        assertEquals(
            "متوسط الوقت: ذاكرة 0.1 ث، حاسبة 0.1 ث، نص 1.2 ث، صورة مقصوصة 1.8 ث، صورة كاملة 2.6 ث",
            lines[1],
        )
    }

    @Test
    fun describe_aSourceWithoutAverage_isLeftOut() {
        val today = summary(
            mapOf(MEMORY to 8, CALCULATOR to 0, TEXT to 10),
            mapOf(MEMORY to 100L, TEXT to 1200L),
        )
        val lines = AnswerStats.describe(today, summary(mapOf(MEMORY to 140))).lines()
        assertEquals("متوسط الوقت: ذاكرة 0.1 ث، نص 1.2 ث", lines[1])
    }

    @Test
    fun describe_neverShowsAnAverageForFailures() {
        // How long a failure took says nothing about how fast answers are (timeouts, no network).
        val today = summary(mapOf(TEXT to 1, FAILED to 2), mapOf(TEXT to 1000L, FAILED to 30_000L))
        val lines = AnswerStats.describe(today, summary(mapOf(TEXT to 1, FAILED to 2))).lines()
        assertEquals("متوسط الوقت: نص 1.0 ث", lines[1])
    }

    @Test
    fun describe_secondsAreRoundedToTenths() {
        fun line(ms: Long) = AnswerStats.describe(
            summary(mapOf(TEXT to 1), mapOf(TEXT to ms)),
            summary(mapOf(TEXT to 1)),
        ).lines()[1]
        assertEquals("متوسط الوقت: نص 0.0 ث", line(49))
        assertEquals("متوسط الوقت: نص 0.1 ث", line(50))
        assertEquals("متوسط الوقت: نص 1.2 ث", line(1249))
        assertEquals("متوسط الوقت: نص 1.3 ث", line(1250))
        assertEquals("متوسط الوقت: نص 12.0 ث", line(11_999))
        assertEquals("متوسط الوقت: نص 65.5 ث", line(65_500))
    }

    @Test
    fun describe_noAnswersToday_butSomeBefore_hasNoTimesLine() {
        val text = AnswerStats.describe(summary(emptyMap()), summary(mapOf(TEXT to 140)))
        assertEquals("اليوم: لا أسئلة بعد\nالكل: 140 سؤال", text)
    }

    @Test
    fun describe_nothingEver_saysSo() {
        assertEquals(
            "لا توجد إحصائيات بعد",
            AnswerStats.describe(summary(emptyMap()), summary(emptyMap())),
        )
    }

    @Test
    fun describe_ofARealStats_matchesItsNumbers() {
        val s = AnswerStats()
        s.record(MEMORY, 100, d2)
        s.record(MEMORY, 100, d2)
        s.record(IMAGE, 2600, d2)
        s.record(TEXT, 900, d1)
        assertEquals(
            "اليوم: 3 سؤال — ذاكرة ⚡ 2، حاسبة 0، نص 0، صورة مقصوصة 0، صورة كاملة 1، فشل 0\n" +
                "متوسط الوقت: ذاكرة 0.1 ث، صورة كاملة 2.6 ث\n" +
                "الكل: 4 سؤال",
            AnswerStats.describe(s.today(d2), s.allTime()),
        )
    }
}
