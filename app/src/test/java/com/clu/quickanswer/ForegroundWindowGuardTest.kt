package com.clu.quickanswer

import org.junit.Assert.*
import org.junit.Test

class ForegroundWindowGuardTest {
    private val own = "com.clu.quickanswer"
    private val game = "com.example.quiz"

    @Test fun firstExternalWindowEstablishesAConservativeBaseline() {
        val guard = ForegroundWindowGuard(own)
        assertTrue(guard.onWindow(game))
        assertFalse(guard.onWindow(game))
    }

    @Test fun repeatedWindowsInsideTheSameGameDoNotCancelTaps() {
        val guard = ForegroundWindowGuard(own)
        guard.onWindow(game)
        repeat(5) { assertFalse(guard.onWindow(game)) }
    }

    @Test fun ourBubbleOrPanelEventsDoNotChangeTheKnownGame() {
        val guard = ForegroundWindowGuard(own)
        assertFalse(guard.onWindow(own))
        assertTrue(guard.onWindow(game))
        assertFalse(guard.onWindow(own))
        assertFalse(guard.onWindow(game))
    }

    @Test fun anotherAppOrSystemUiCancelsAndReturningToTheGameCancelsAgain() {
        val guard = ForegroundWindowGuard(own)
        guard.onWindow(game)
        assertTrue(guard.onWindow("com.android.systemui"))
        assertFalse(guard.onWindow("com.android.systemui"))
        assertFalse(guard.onWindow(own))
        assertTrue(guard.onWindow(game))
        assertFalse(guard.onWindow(game))
        assertTrue(guard.onWindow("com.example.browser"))
    }

    @Test fun unknownPackagesFailClosedAndForgetThePreviousApp() {
        val guard = ForegroundWindowGuard(own)
        for (unknown in listOf(null, "", " ")) {
            guard.onWindow(game)
            assertTrue(guard.onWindow(unknown))
            assertFalse(guard.onWindow(own))
            assertTrue(guard.onWindow(game))
            assertFalse(guard.onWindow(game))
        }
    }

    @Test fun interruptResetRequiresANewForegroundBaseline() {
        val guard = ForegroundWindowGuard(own)
        guard.onWindow(game)
        guard.reset()
        assertFalse(guard.onWindow(own))
        assertTrue(guard.onWindow(game))
        assertFalse(guard.onWindow(game))
    }
}
