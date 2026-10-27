package com.clu.quickanswer

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The race between Gemini models, on a real small thread pool with fake `ask` functions: no network,
 * no keys. The `timeout` on the tests that wait on a hung request makes a regression (a race that
 * waits for ever, or for the 10 s the fakes sleep) fail fast instead of hanging the build.
 */
class ModelRaceTest {

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "race-test").apply {
            isDaemon =
                true
        }
    }
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }

    // The models the race asked, in the order they were asked, and the ones whose request was interrupted.
    private val asked = ConcurrentLinkedQueue<String>()
    private val interrupted = ConcurrentLinkedQueue<String>()

    @After
    fun stopPool() {
        pool.shutdownNow()
    }

    private fun ok(text: String) =
        HttpResult(200, """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}""")

    private fun error(code: Int, message: String = "") =
        HttpResult(code, if (message.isEmpty()) "{}" else """{"error":{"message":"$message"}}""")

    /** A request that gets no reply: sleeps [HANG_MS] unless it is interrupted, which it notes. */
    private fun hang(model: String): HttpResult {
        try {
            Thread.sleep(HANG_MS)
        } catch (e: InterruptedException) {
            interrupted += model
            throw e
        }
        return error(500)
    }

    private fun race(
        first: String = "a",
        fallbacks: List<String> = emptyList(),
        preferred: String = first,
        primaryWaitMs: Long = 50,
        maxExtra: Int = 2,
        blocked: Set<String> = emptySet(),
        deadline: AnswerDeadline = AnswerDeadline.start(clock, 5_000L),
        ask: (String) -> HttpResult
    ) = ModelRace(
        executor = pool,
        clock = clock,
        first = first,
        fallbacks = fallbacks,
        preferred = preferred,
        isBlocked = { it in blocked },
        ask = { m ->
            asked += m
            ask(m)
        },
        primaryWaitMs = primaryWaitMs,
        maxExtraInFlight = maxExtra,
        deadline = deadline
    )

    /** Waits (up to 2 s) until every model in [models] has had its request interrupted. */
    private fun assertInterrupted(models: Set<String>) {
        val end = clock() + 2_000L
        while (!interrupted.containsAll(models) && clock() < end) Thread.sleep(10)
        assertTrue(
            "interrupted: ${interrupted.toList()}, wanted $models",
            interrupted.containsAll(models)
        )
    }

    // ------------------------------------------------------------ the deadline

    @Test(timeout = 8_000)
    fun everyModelHangs_timesOutAtTheBudget_andTheRequestsAreCancelled() {
        val t0 = clock()
        val out = race("a", listOf("b", "c"), deadline = AnswerDeadline.start(clock, 300L)) {
            hang(it)
        }.run()
        val elapsed = clock() - t0
        assertTrue(out.timedOut)
        assertFalse(out.ok)
        assertTrue("a timeout ends the question: no other request can do better", out.final)
        assertTrue("took $elapsed ms, the budget was 300", elapsed < 2_000L)
        assertTrue("returned after $elapsed ms, before the budget was spent", elapsed >= 250L)
        // The primary was asked, and the fallbacks started after its wait: all three hung and all three are cut.
        assertEquals(setOf("a", "b", "c"), asked.toSet())
        assertInterrupted(setOf("a", "b", "c"))
    }

    @Test(timeout = 8_000)
    fun theBudgetCutsThePrimarysWait_soNoFallbackStartsAfterIt() {
        val t0 = clock()
        val out = race(
            "a",
            listOf("b", "c"),
            primaryWaitMs = 5_000L,
            deadline = AnswerDeadline.start(clock, 300L)
        ) {
            hang(it)
        }.run()
        val elapsed = clock() - t0
        assertTrue(out.timedOut)
        assertTrue("took $elapsed ms, the budget was 300", elapsed < 2_000L)
        assertEquals(listOf("a"), asked.toList())
        assertInterrupted(setOf("a"))
    }

    @Test(timeout = 8_000)
    fun noMoreThanMaxExtraInFlight_evenWhenEveryoneHangs() {
        val out = race("a", listOf("b", "c", "d"), deadline = AnswerDeadline.start(clock, 400L)) {
            hang(it)
        }.run()
        assertTrue(out.timedOut)
        assertEquals("the primary and two extras", setOf("a", "b", "c"), asked.toSet())
    }

    @Test
    fun aDeadlineThatHasAlreadyPassed_asksNobody() {
        val out = race("a", listOf("b"), deadline = AnswerDeadline.start(clock, 0L)) {
            ok("late")
        }.run()
        assertTrue(out.timedOut)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun noNewFallbackStartsAfterTheDeadline() {
        val now = AtomicLong(0)
        val deadline = AnswerDeadline.start({ now.get() }, 1_000L)
        val out = race("a", listOf("b", "c"), deadline = deadline) {
            if (it == "a") {
                now.set(5_000L) // the primary's failure comes after the budget is spent
                error(503)
            } else {
                ok("too late")
            }
        }.run()
        assertEquals(listOf("a"), asked.toList())
        assertFalse(out.ok)
        assertEquals("a: خوادم Gemini مشغولة الآن، جرّب بعد قليل", out.text)
    }

    @Test
    fun anAnswerThatArrivesAfterTheDeadlineButBeforeTheRaceLooks_isStillUsed() {
        val now = AtomicLong(0)
        val deadline = AnswerDeadline.start({ now.get() }, 1_000L)
        val out = race("a", deadline = deadline) {
            now.set(5_000L)
            ok("in hand")
        }.run()
        assertTrue(out.ok)
        assertEquals("in hand", out.text)
    }

    @Test
    fun aCallThatDiesBecauseTheBudgetRanOut_isATimeout_notALostConnection() {
        val now = AtomicLong(0)
        val deadline = AnswerDeadline.start({ now.get() }, 1_000L)
        val out = race("a", listOf("b"), deadline = deadline) {
            now.set(5_000L)
            throw SocketTimeoutException("read timed out")
        }.run()
        assertTrue(out.timedOut)
    }

    @Test
    fun aFailureWithTimeToSpare_isStillRethrown() {
        val e = assertThrows(IOException::class.java) {
            race("a", listOf("b")) { throw IOException("no route to host") }.run()
        }
        assertEquals("no route to host", e.message)
    }

    // ------------------------------------------------------------ who answers, who is asked

    @Test(timeout = 8_000)
    fun primaryAnswers_onlyThePrimaryIsAsked() {
        val out = race("a", listOf("b", "c"), primaryWaitMs = 1_000L) {
            if (it == "a") ok("A") else hang(it)
        }.run()
        assertTrue(out.ok)
        assertFalse(out.timedOut)
        assertEquals("A", out.text)
        assertEquals("a", out.model)
        assertEquals(listOf("a"), asked.toList())
    }

    @Test(timeout = 8_000)
    fun aPrimaryThatAnswersWithinItsWait_needsNoFallback() {
        val out = race("a", listOf("b"), primaryWaitMs = 1_000L) {
            Thread.sleep(100)
            ok("A")
        }.run()
        assertEquals("A", out.text)
        assertEquals(listOf("a"), asked.toList())
    }

    @Test(timeout = 8_000)
    fun primaryFailsFast_aFallbackAnswersAndWins_andTheOtherExtraIsCancelled() {
        val t0 = clock()
        val out = race("a", listOf("b", "c", "d"), primaryWaitMs = 5_000L) {
            when (it) {
                "a" -> error(503)

                "b" -> {
                    Thread.sleep(200)
                    ok("B")
                }

                else -> hang(it)
            }
        }.run()
        assertTrue("took ${clock() - t0} ms", clock() - t0 < 2_000L)
        assertTrue(out.ok)
        assertEquals("B", out.text)
        assertEquals("b", out.model)
        // The failure started the fallbacks at once (not after the 5 s wait), two at a time.
        assertEquals(setOf("a", "b", "c"), asked.toSet())
        assertInterrupted(setOf("c"))
    }

    @Test(timeout = 8_000)
    fun primarySlowPastItsWait_aFallbackWins_andTheStillRunningPrimaryIsCancelled() {
        val t0 = clock()
        val out = race("a", listOf("b"), primaryWaitMs = 100L) {
            if (it == "a") hang(it) else ok("B")
        }.run()
        assertTrue("took ${clock() - t0} ms", clock() - t0 < 2_000L)
        assertTrue(out.ok)
        assertEquals("b", out.model)
        assertEquals(listOf("a", "b"), asked.toList())
        assertInterrupted(setOf("a"))
    }

    @Test
    fun aReplyWithNoText_isAFailure_soAFallbackStarts() {
        val out = race("a", listOf("b")) {
            if (it == "a") HttpResult(200, """{"candidates":[]}""") else ok("B")
        }.run()
        assertTrue(out.ok)
        assertEquals("b", out.model)
    }

    @Test
    fun aBlockedFallbackIsSkipped() {
        val out = race("a", listOf("b", "c"), blocked = setOf("b")) {
            if (it == "a") error(503) else ok("C")
        }.run()
        assertEquals("c", out.model)
        assertEquals(listOf("a", "c"), asked.toList())
    }

    // ------------------------------------------------------------ errors

    @Test
    fun aKeyError_stopsAtOnce() {
        val badKey = """{"error":{"message":"API key not valid. Please pass a valid API key."}}"""
        val out = race("a", listOf("b", "c")) { HttpResult(400, badKey) }.run()
        assertTrue(out.final)
        assertFalse(out.ok)
        assertEquals("a: مفتاح API غير صالح", out.text)
        assertEquals(listOf("a"), asked.toList())
    }

    @Test
    fun a403_isAKeyError_too() {
        val out = race("a", listOf("b")) { error(403) }.run()
        assertTrue(out.final)
        assertEquals("a: مفتاح API غير صالح", out.text)
        assertEquals(listOf("a"), asked.toList())
    }

    @Test(timeout = 8_000)
    fun aRegionError_fromAFallback_endsTheRaceAtOnce_andCancelsTheOthers() {
        val region = "User location is not supported for the API use."
        val t0 = clock()
        val out = race("a", listOf("b", "c"), primaryWaitMs = 50L) {
            if (it == "b") error(400, region) else hang(it)
        }.run()
        assertTrue("took ${clock() - t0} ms", clock() - t0 < 2_000L)
        assertTrue(out.final)
        assertFalse(out.ok)
        assertEquals("b: Gemini غير متاح في منطقتك، جرّب تشغيل VPN", out.text)
        assertInterrupted(setOf("a", "c"))
    }

    @Test
    fun everyModelOutOfQuota_isTheQuotaMessage() {
        val out = race("a", listOf("b", "c", "d")) { error(429, "quota") }.run()
        assertEquals(
            "انتهى الحد المجاني لكل النماذج حاليًا — جرّب لاحقًا أو استخدم Claude",
            out.text
        )
        assertTrue(out.final)
        assertFalse(out.ok)
        assertEquals(setOf("a", "b", "c", "d"), asked.toSet())
    }

    @Test
    fun aMissingModel_doesNotSpoilTheQuotaMessage() {
        val out = race("a", listOf("b")) { if (it == "a") error(429) else error(404) }.run()
        assertEquals(
            "انتهى الحد المجاني لكل النماذج حاليًا — جرّب لاحقًا أو استخدم Claude",
            out.text
        )
        assertTrue(out.final)
    }

    @Test
    fun quotaMixedWithAnotherError_isNotTheQuotaMessage_andTheUsersModelIsReportedFirst() {
        val out = race("a", listOf("b")) { if (it == "a") error(429) else error(503) }.run()
        assertFalse(out.final)
        assertFalse(out.ok)
        assertEquals("a: تم تجاوز الحد المجاني، انتظر قليلاً", out.text)
    }

    @Test
    fun theUsersOwnModelsErrorComesFirst_evenWhenItIsNotTheOneAskedFirst() {
        val out = race("a", listOf("b", "c"), preferred = "c") {
            when (it) {
                "a" -> error(503)
                "b" -> error(404)
                else -> error(400, "bad")
            }
        }.run()
        assertEquals("c: خطأ 400: bad", out.text)
        assertFalse(out.final)
    }

    @Test
    fun aMissingModelIsNeverReported_whileAnotherErrorExists() {
        val out = race("a", listOf("b")) { if (it == "a") error(404) else error(503) }.run()
        assertEquals("b: خوادم Gemini مشغولة الآن، جرّب بعد قليل", out.text)
    }

    @Test
    fun aSuccessHasNoTimeoutAndNoModelOnFailure() {
        val failed = race("a") { error(503) }.run()
        assertNull(failed.model)
        assertFalse(failed.timedOut)
    }

    private companion object {
        /** Longer than any test waits, so a request that is not interrupted is a request that hangs. */
        const val HANG_MS = 10_000L
    }
}
