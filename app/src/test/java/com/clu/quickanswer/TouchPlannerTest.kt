package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.*
import org.junit.Test

class TouchPlannerTest {
    private val keys = ('0'..'9').associateWith { digit ->
        val n = digit - '0'
        PxRect(20 + n * 30, 200, 44 + n * 30, 230)
    }

    @Test fun optionTapIsCenteredAndBounded() {
        val option = PxRect(120, 350, 300, 420)
        assertEquals(listOf(option), TouchPlanner.plan("الإجابة", option, emptyMap(), null, 500, 800))
    }

    @Test fun fourDigitsAreTypedInOrderIncludingZeroAndRepeats() {
        assertEquals(listOf(keys['0'], keys['0'], keys['2'], keys['4']),
            TouchPlanner.plan("0024", null, keys, null, 500, 800))
        assertEquals(List(4) { keys['1'] }, TouchPlanner.plan("1111", null, keys, null, 500, 800))
        val submit = PxRect(360, 300, 440, 395)
        assertEquals(listOf(keys['1'], keys['2'], keys['3'], keys['4'], submit),
            TouchPlanner.plan("1234", null, keys, submit, 500, 800))
    }

    @Test fun untrustedNumbersOrCoordinatesAreNeverTapped() {
        for (input in listOf("", "12345", "-12", "1.5", "12 3", "الإجابة 1234", "١٢٣٤")) {
            assertNull(input, TouchPlanner.plan(input, null, keys, null, 500, 800))
        }
        assertNull(TouchPlanner.plan("9012", null, keys - '0', null, 500, 800))
        assertNull(TouchPlanner.plan("1", PxRect(-1, 5, 20, 24), keys, null, 500, 800))
        assertNull(TouchPlanner.plan("1", PxRect(5, 5, 5, 20), keys, null, 500, 800))
    }
}
