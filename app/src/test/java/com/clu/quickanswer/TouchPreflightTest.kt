package com.clu.quickanswer

import org.junit.Assert.*
import org.junit.Test

class TouchPreflightTest {
    @Test fun aQueuedOverlayFrameCanBeReplacedBeforeAnOtherwiseValidTouch() {
        val pending = ArrayDeque<() -> Unit>()
        val results = mutableListOf<Boolean>()
        var reads = 0
        TouchPreflight { delay, action -> assertEquals(100L, delay); pending.add(action) }
            .awaitReady({ true }, { ++reads >= 2 }, results::add)
        assertTrue(results.isEmpty())
        pending.removeFirst()()
        assertEquals(listOf(true), results)
        assertEquals(2, reads)
        assertTrue(pending.isEmpty())
    }

    @Test fun aChangedQuestionIsRejectedAfterABoundedWaitWithNoSuccessCallback() {
        val pending = ArrayDeque<() -> Unit>()
        val results = mutableListOf<Boolean>()
        var reads = 0
        TouchPreflight { _, action -> pending.add(action) }.awaitReady({ true }, { reads++; false }, results::add)
        while (pending.isNotEmpty()) pending.removeFirst()()
        assertEquals(3, reads)
        assertEquals(listOf(false), results)
    }

    @Test fun cancellationDuringTheWaitCannotAuthorizeAnotherAttempt() {
        val pending = ArrayDeque<() -> Unit>()
        val results = mutableListOf<Boolean>()
        var current = true
        var reads = 0
        TouchPreflight { _, action -> pending.add(action) }.awaitReady({ current }, { reads++; false }, results::add)
        current = false
        pending.removeFirst()()
        assertEquals(1, reads)
        assertEquals(listOf(false), results)
        assertTrue(pending.isEmpty())
    }
}
