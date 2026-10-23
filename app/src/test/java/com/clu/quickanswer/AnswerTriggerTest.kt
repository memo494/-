package com.clu.quickanswer

import org.junit.Assert.*
import org.junit.Test

class AnswerTriggerTest {
    @Test fun volumeDownSolvesAndTouchesWithoutAOrAutoTouchSetting() {
        assertTrue(AnswerTrigger.VOLUME_DOWN.permitsTouch(false, false, true))
        assertTrue(AnswerTrigger.VOLUME_DOWN.permitsTouch(true, false, true))
    }

    @Test fun disablingVolumeShortcutRevokesEvenAnInFlightVolumeRequest() {
        assertFalse(AnswerTrigger.VOLUME_DOWN.permitsTouch(true, true, false))
    }

    @Test fun automaticScanningStillNeedsBothOptIns() {
        for (mode in listOf(false, true)) for (touch in listOf(false, true)) {
            assertEquals(mode && touch, AnswerTrigger.AUTOMATIC.permitsTouch(mode, touch, true))
        }
    }

    @Test fun bubblePressUsesTouchPreferenceWithoutRequiringContinuousScanning() {
        assertTrue(AnswerTrigger.BUBBLE.permitsTouch(false, true, false))
        assertFalse(AnswerTrigger.BUBBLE.permitsTouch(true, false, true))
    }
}
