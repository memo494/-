package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class TouchFailureRecoveryTest {
    private val key = PxRect(10, 10, 40, 40)

    @Test fun nativeGuardFailureReleasesTheSequenceForTheNextQuestion() {
        val results = mutableListOf<Boolean>()
        var presses = 0
        val sequence = TapSequence({ _, _, complete -> presses++; complete(true); true }, { _, _ -> })
        assertTrue(sequence.start(listOf(key), canContinue = { throw LinkageError("reader") }, result = results::add))
        assertEquals(0, presses)
        assertTrue(sequence.start(listOf(key), result = results::add))
        assertEquals(1, presses)
        assertEquals(listOf(false, true), results)
    }

    @Test fun platformFailureDoesNotLeaveTheNextRequestLocked() {
        val results = mutableListOf<Boolean>()
        var fail = true
        val sequence = TapSequence({ _, _, complete ->
            if (fail) throw AssertionError("platform")
            complete(true)
            true
        }, { _, _ -> })
        assertTrue(sequence.start(listOf(key), result = results::add))
        fail = false
        assertTrue(sequence.start(listOf(key), result = results::add))
        assertEquals(listOf(false, true), results)
    }

    @Test fun failedCallbackSchedulingDoesNotReplayTheDigitOrBlockTheNextQuestion() {
        val results = mutableListOf<Boolean>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        val sequence = TapSequence({ _, _, complete -> callbacks += complete; true },
            { _, _ -> throw AssertionError("scheduler") })
        assertTrue(sequence.start(listOf(key, key), result = results::add))
        callbacks[0](true)
        assertEquals(1, callbacks.size)
        assertTrue(sequence.start(listOf(key), result = results::add))
        callbacks[0](true)
        callbacks[1](true)
        assertEquals(listOf(false, false), results)
    }

    @Test fun preflightSchedulingFailureCompletesOnceAndIgnoresLateWork() {
        var queued: (() -> Unit)? = null
        val results = mutableListOf<Boolean>()
        val preflight = TouchPreflight { _, action -> queued = action; throw AssertionError("scheduler") }
        preflight.awaitReady({ true }, { false }, results::add)
        assertEquals(listOf(false), results)
        queued!!()
        assertEquals(listOf(false), results)
        preflight.awaitReady({ true }, { true }, results::add)
        assertEquals(listOf(false, true), results)
    }
}
