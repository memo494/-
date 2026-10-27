package com.clu.quickanswer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmUpThrottleTest {
    @Test fun theFirstAskGoesThrough() {
        assertTrue(WarmUpThrottle(1_000).tryAcquire(0))
    }

    @Test fun asksCloserThanTheGapAreDropped() {
        val t = WarmUpThrottle(1_000)
        assertTrue(t.tryAcquire(5_000))
        assertFalse(t.tryAcquire(5_500))
        assertFalse(t.tryAcquire(5_999))
        assertTrue(t.tryAcquire(6_000))
    }

    /** A dropped ask does not push the next allowed one further out. */
    @Test fun aDroppedAskDoesNotResetTheGap() {
        val t = WarmUpThrottle(1_000)
        assertTrue(t.tryAcquire(0))
        assertFalse(t.tryAcquire(900))
        assertTrue(t.tryAcquire(1_000))
    }
}
