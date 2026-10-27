package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answer budget: a fake clock stands in for SystemClock.elapsedRealtime. */
class AnswerDeadlineTest {

    private var now = 1_000L
    private val clock: () -> Long = { now }

    @Test
    fun start_endsAtNowPlusTheBudget() {
        val d = AnswerDeadline.start(clock, budgetMs = 4_000L)
        assertEquals(5_000L, d.endsAt)
        assertEquals(4_000L, d.remainingMs())
        assertFalse(d.expired)
    }

    @Test
    fun theDefaultBudgetIsTenSeconds() {
        assertEquals(10_000L, AnswerDeadline.BUDGET_MS)
        val d = AnswerDeadline.start(clock)
        assertEquals(now + 10_000L, d.endsAt)
        assertEquals(10_000L, d.remainingMs())
    }

    @Test
    fun remainingShrinksAsTheClockAdvances() {
        val d = AnswerDeadline.start(clock, 10_000L)
        now += 2_500L
        assertEquals(7_500L, d.remainingMs())
        assertFalse(d.expired)
        now += 7_499L
        assertEquals(1L, d.remainingMs())
        assertFalse(d.expired)
    }

    @Test
    fun expiredExactlyWhenTheBudgetIsSpent() {
        val d = AnswerDeadline.start(clock, 10_000L)
        now += 10_000L
        assertEquals(0L, d.remainingMs())
        assertTrue(d.expired)
    }

    @Test
    fun remainingIsNeverNegative() {
        val d = AnswerDeadline.start(clock, 10_000L)
        now += 1_000_000L
        assertEquals(0L, d.remainingMs())
        assertTrue(d.expired)
        // A deadline that already ended before it was made (a negative budget) is just as harmless.
        val past = AnswerDeadline.start(clock, -5_000L)
        assertEquals(0L, past.remainingMs())
        assertTrue(past.expired)
    }

    @Test
    fun theClockIsReadEveryTime() {
        val d = AnswerDeadline.start(clock, 100L)
        assertFalse(d.expired)
        now += 100L
        assertTrue(d.expired)
    }

    @Test
    fun socketTimeoutsAreCutToWhatIsLeft() {
        val d = AnswerDeadline.start(clock, 10_000L)
        // Plenty left: the usual connect timeout stands, the longer read timeout is cut.
        assertEquals(10_000, d.capTimeoutMs(10_000))
        assertEquals(10_000, d.capTimeoutMs(30_000))
        now += 8_000L
        assertEquals(2_000, d.capTimeoutMs(10_000))
        assertEquals(2_000, d.capTimeoutMs(30_000))
        assertEquals(500, d.capTimeoutMs(500))
    }

    @Test
    fun socketTimeoutIsNeverZero() {
        // 0 means "wait for ever" to HttpURLConnection, the opposite of what an expired deadline says.
        val d = AnswerDeadline.start(clock, 1_000L)
        now += 5_000L
        assertEquals(1, d.capTimeoutMs(10_000))
    }

    @Test
    fun theTimeoutTextIsTheOwnersArabicMessage() {
        assertEquals("انتهى الوقت (10 ث)، جرّب مرة ثانية", AnswerDeadline.TIMEOUT_TEXT)
    }
}
