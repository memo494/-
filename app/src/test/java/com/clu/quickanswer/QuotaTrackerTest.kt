package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** retryDelay parsing (real 429 bodies from the lab run) and the quota tracker. Prefs-free. */
class QuotaTrackerTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("gemini/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    // ------------------------------------------------------------ retryDelay

    @Test
    fun retryDelay_perMinuteLimit_isFiveSeconds() {
        // gemini-3.7-flash, limit 5/min: "Please retry in 5.005569625s." with RetryInfo "5s".
        assertEquals(5_000L, GeminiModels.retryDelayMs(resource("429_per_minute_gemini-3.7-flash.json")))
    }

    @Test
    fun retryDelay_dailyQuota_isAboutEightHours() {
        // gemini-3.8-flash, 20/day: RetryInfo "28945s".
        val ms = GeminiModels.retryDelayMs(resource("429_daily_gemini-3.8-flash.json"))
        assertEquals(28_945_000L, ms)
    }

    @Test
    fun retryDelay_paidOnlyModel_hasRetryInfoToo() {
        assertEquals(27_718_000L, GeminiModels.retryDelayMs(resource("429_paid_only_gemini-omni-flash-preview.json")))
    }

    @Test
    fun retryDelay_formats() {
        assertEquals(5_000L, GeminiModels.parseDurationMs("5s"))
        assertEquals(28_945_000L, GeminiModels.parseDurationMs("28945s"))
        assertEquals(1_500L, GeminiModels.parseDurationMs("1.5s"))
        assertEquals(500L, GeminiModels.parseDurationMs("0.5s"))
        assertEquals(1_235L, GeminiModels.parseDurationMs("1.2345s"))   // rounded up
        assertEquals(0L, GeminiModels.parseDurationMs("0s"))
        assertNull(GeminiModels.parseDurationMs(""))
        assertNull(GeminiModels.parseDurationMs("5"))
        assertNull(GeminiModels.parseDurationMs("5m"))
        assertNull(GeminiModels.parseDurationMs("8h3m27s"))
        assertNull(GeminiModels.parseDurationMs("-3s"))
    }

    @Test
    fun retryDelay_fromSyntheticBodies() {
        fun body(delay: String) =
            """{"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.Help"},""" +
                """{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"$delay"}]}}"""
        assertEquals(1_500L, GeminiModels.retryDelayMs(body("1.5s")))
        // The unwrapped error object (as stored in the lab log) works as well.
        assertEquals(
            7_000L,
            GeminiModels.retryDelayMs("""{"code":429,"details":[{"@type":"x.RetryInfo","retryDelay":"7s"}]}""")
        )
    }

    @Test
    fun retryDelay_missingOrBroken_isNull() {
        assertNull(GeminiModels.retryDelayMs(""))
        assertNull(GeminiModels.retryDelayMs("<html>429</html>"))
        assertNull(GeminiModels.retryDelayMs("""{"error":{"code":429,"message":"Please retry in 5s"}}"""))
        assertNull(GeminiModels.retryDelayMs("""{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.Help"}]}}"""))
        assertNull(GeminiModels.retryDelayMs("""{"error":{"details":[{"@type":"x.RetryInfo"}]}}"""))
    }

    // ------------------------------------------------------------ tracker

    private class Clock(var now: Long = 1_000_000L)

    @Test
    fun block_usesTheRetryDelay_thenExpires() {
        val c = Clock()
        val q = QuotaTracker(clock = { c.now })
        assertFalse(q.isBlocked("gemini-3.7-flash"))
        q.block("gemini-3.7-flash", 5_000L)
        assertTrue(q.isBlocked("gemini-3.7-flash"))
        assertEquals(5_000L, q.remainingMs("gemini-3.7-flash"))
        c.now += 4_999
        assertTrue(q.isBlocked("gemini-3.7-flash"))
        c.now += 1
        assertFalse(q.isBlocked("gemini-3.7-flash"))
        assertEquals(0L, q.remainingMs("gemini-3.7-flash"))
    }

    @Test
    fun block_withoutRetryDelay_isSixtySeconds() {
        val c = Clock()
        val q = QuotaTracker(clock = { c.now })
        q.block("gemini-3.6-flash", null)
        assertEquals(60_000L, q.remainingMs("gemini-3.6-flash"))
        c.now += 59_999
        assertTrue(q.isBlocked("gemini-3.6-flash"))
        c.now += 1
        assertFalse(q.isBlocked("gemini-3.6-flash"))
    }

    @Test
    fun block_coversTheWholeQuotaGroup() {
        val q = QuotaTracker(clock = { 0L })
        q.block("gemini-flash-latest", 28_945_000L)
        assertTrue(q.isBlocked("gemini-3.8-flash"))
        assertTrue(q.isBlocked("gemini-flash-latest"))
        assertFalse(q.isBlocked("gemini-3-flash-preview"))

        q.block("gemini-3.5-flash-lite", 1_000L)
        assertTrue(q.isBlocked("gemini-flash-lite-latest"))
        assertFalse(q.isBlocked("gemini-3.6-flash"))
    }

    @Test
    fun block_keepsTheLongerOfTwoBlocks() {
        val q = QuotaTracker(clock = { 0L })
        q.block("gemini-3.8-flash", 28_945_000L)
        q.block("gemini-flash-latest", 5_000L)   // a racing request reporting a shorter wait
        assertEquals(28_945_000L, q.remainingMs("gemini-3.8-flash"))
    }

    @Test
    fun clear_unblocksTheGroup() {
        val q = QuotaTracker(clock = { 0L })
        q.block("gemini-3.8-flash", 60_000L)
        q.clear("gemini-flash-latest")
        assertFalse(q.isBlocked("gemini-3.8-flash"))
    }

    @Test
    fun encodeRestore_roundTripsAndDropsExpired() {
        val c = Clock()
        val q = QuotaTracker(clock = { c.now })
        q.block("gemini-3.8-flash", 28_945_000L)
        q.block("gemini-3.7-flash", 5_000L)
        val state = q.encode()

        c.now += 10_000   // the 5 s block is over by the time the app restarts
        val back = QuotaTracker(clock = { c.now })
        back.restore(state)
        assertTrue(back.isBlocked("gemini-flash-latest"))
        assertFalse(back.isBlocked("gemini-3.7-flash"))
        assertEquals(28_935_000L, back.remainingMs("gemini-3.8-flash"))
        assertFalse(back.encode().contains("gemini-3.7-flash"))
    }

    @Test
    fun restore_ignoresJunk() {
        val q = QuotaTracker(clock = { 0L })
        q.restore("junk;=5;a=notanumber;gemini-3.6-flash=60000")
        assertTrue(q.isBlocked("gemini-3.6-flash"))
        assertFalse(q.isBlocked("a"))
        q.restore("")
        assertFalse(q.isBlocked("gemini-3.6-flash"))
    }

    @Test
    fun onChange_reportsStateOnlyWhenSomethingChanged() {
        val seen = mutableListOf<String>()
        val q = QuotaTracker(clock = { 0L }).also { it.onChange = { s -> seen += s } }
        q.clear("gemini-3.6-flash")           // nothing blocked: no write
        q.block("gemini-3.6-flash", 60_000L)
        q.clear("gemini-3.6-flash")
        q.clearAll()                          // already empty: no write
        assertEquals(listOf("gemini-3.6-flash=60000", ""), seen)
    }

    @Test
    fun onChange_racingThreadsNeverLeaveAnOlderStateAsTheLastWrite() {
        // Prefs keeps whichever write comes last, so the last state handed to onChange must be the
        // final state of the tracker, however the threads interleave.
        repeat(50) {
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val q = QuotaTracker(clock = { 0L }).also { it.onChange = { s -> seen += s } }
            val ids = listOf("gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite")
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = ids.map { id ->
                Thread { start.await(); q.block(id, 60_000L) }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(ids.size, seen.size)
            assertEquals(q.encode(), seen.last())
        }
    }

    @Test
    fun thinkingMemory_racingThreadsNeverLeaveAnOlderStateAsTheLastWrite() {
        repeat(50) {
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val mem = ThinkingMemory().also { it.onChange = { s -> seen += s } }
            val ids = listOf("gemini-a", "gemini-b", "gemini-c", "gemini-d")
            val start = java.util.concurrent.CountDownLatch(1)
            val threads = ids.map { id ->
                Thread { start.await(); mem.learn(id, ThinkingMode.LOW) }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(ids.size, seen.size)
            assertEquals(mem.encode(), seen.last())
        }
    }
}
