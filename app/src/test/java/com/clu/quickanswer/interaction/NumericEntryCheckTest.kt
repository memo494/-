package com.clu.quickanswer.interaction

import com.clu.quickanswer.TouchFrameGuard
import org.junit.Assert.*
import org.junit.Test

class NumericEntryCheckTest {
    @Test fun enteredValueMustKeepLeadingZeroesRepeatedDigitsAndOrder() {
        assertTrue(NumericEntryCheck.matches("0011", "0011", 0.95f, true))
        assertTrue(NumericEntryCheck.matches("1989", "١٩٨٩", 0.95f, true))
        for (wrong in listOf("011", "001", "1100", "0000", "0011 شخص", "1.1")) {
            assertFalse(NumericEntryCheck.matches("0011", wrong, 0.95f, true))
        }
    }

    @Test fun unchangedPlaceholderAndUnreliableReadsNeverConfirm() {
        assertFalse(NumericEntryCheck.matches("0000", "0000", 0.95f, false))
        for (confidence in listOf(0.74f, Float.NaN, Float.POSITIVE_INFINITY, 1.1f)) {
            assertFalse(NumericEntryCheck.matches("1989", "1989", confidence, true))
        }
    }

    @Test fun flatEmptyFieldsAreComparableAndNeedRealChangedStrokes() {
        val before = IntArray(TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT) { 240 }
        assertFalse(NumericEntryCheck.fieldChanged(before, before.copyOf()))
        assertFalse(NumericEntryCheck.fieldChanged(before, before.map { it - 1 }.toIntArray()))
        val typed = before.copyOf()
        for (y in 16 until 32) for (x in 36 until 92) if (x % 6 < 2) typed[y * TouchFrameGuard.WIDTH + x] = 40
        assertTrue(NumericEntryCheck.fieldChanged(before, typed))
        val narrowOne = before.copyOf()
        for (y in 16 until 32) narrowOne[y * TouchFrameGuard.WIDTH + 64] = 40
        assertTrue(NumericEntryCheck.fieldChanged(before, narrowOne))
        val masked = typed.copyOf().also { p -> for (i in 0 until p.size / 2) p[i] = -1 }
        assertFalse(NumericEntryCheck.fieldChanged(before, masked))
        assertFalse(NumericEntryCheck.fieldChanged(before, typed.copyOf().also { it[0] = 256 }))
    }
}
