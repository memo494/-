package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An answer that is tapped is not shown and hidden again first (about 0.4 s per touched answer). */
class TouchStartTest {
    @Test fun nothingOfOursOnScreenMeansNoWait() {
        val plan = TouchStart.plan(panelShown = false, boxShown = false)
        assertEquals(0L, plan.settleMs)
        assertFalse(plan.hide)
    }

    @Test fun aPanelOrABoxOnScreenIsHiddenAndWaitedFor() {
        for ((panel, box) in listOf(true to false, false to true, true to true)) {
            val plan = TouchStart.plan(panel, box)
            assertEquals(TouchStart.SETTLE_MS, plan.settleMs)
            assertTrue(plan.hide)
        }
    }
}
