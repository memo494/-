package com.clu.quickanswer.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for [LearningGate]: when a layout request may be sent in the background. */
class LearningGateTest {

    /** A fingerprint whose every cell is [level]; levels 3 or more apart are different games. */
    private fun fp(level: Int) = Integer.toHexString(level).repeat(ScreenFingerprint.COLS * ScreenFingerprint.ROWS)

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun gate() = LearningGate(minIntervalMs = minute, maxPerDay = 3, retryAfterMs = 10 * minute)

    @Test fun oneAtATime() {
        val g = gate()
        assertTrue(g.tryStart(fp(2), 0))
        assertFalse("one request is still running", g.tryStart(fp(9), 2 * minute))
        g.finish(fp(2), learned = true, now = 2 * minute)
        assertTrue(g.tryStart(fp(9), 3 * minute))
    }

    @Test fun waitsBetweenRequests() {
        val g = gate()
        assertTrue(g.tryStart(fp(2), 0))
        g.finish(fp(2), learned = true, now = 1000)
        assertFalse(g.tryStart(fp(9), minute - 1))
        assertTrue(g.tryStart(fp(9), minute))
    }

    @Test fun aDailyLimit() {
        val g = gate()
        var now = 0L
        repeat(3) { i ->
            assertTrue(g.tryStart(fp(i * 4), now))
            g.finish(fp(i * 4), learned = true, now = now)
            now += 2 * minute
        }
        assertFalse("three starts in the last 24 h", g.tryStart(fp(14), now))
        // The first start leaves the window 24 h after it was made.
        assertTrue(g.tryStart(fp(14), day))
    }

    @Test fun aScreenThatFailedIsNotAskedAgainSoon() {
        val g = gate()
        assertTrue(g.tryStart(fp(5), 0))
        g.finish(fp(5), learned = false, now = 0)
        assertFalse("same game", g.tryStart(fp(6), 2 * minute))
        assertTrue("another game", g.tryStart(fp(12), 2 * minute))
        g.finish(fp(12), learned = true, now = 2 * minute)
        assertTrue("after the pause", g.tryStart(fp(6), 10 * minute))
    }

    @Test fun aLearnedScreenDoesNotBlock() {
        val g = gate()
        assertTrue(g.tryStart(fp(5), 0))
        g.finish(fp(5), learned = true, now = 0)
        assertTrue(g.tryStart(fp(5), 2 * minute))
    }

    @Test fun finishWithoutStartChangesNothing() {
        val g = gate()
        g.finish(fp(5), learned = false, now = 0)
        assertTrue(g.tryStart(fp(5), 0))
    }
}
