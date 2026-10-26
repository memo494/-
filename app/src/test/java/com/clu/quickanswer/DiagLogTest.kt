package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagLogTest {
    @Test fun keepsTheNewestLinesOnOneLineEach() {
        DiagLog.clear()
        repeat(DiagLog.MAX_LINES + 5) { DiagLog.add("step $it\nnext") }
        val lines = DiagLog.text().lines()
        assertEquals(DiagLog.MAX_LINES, lines.size)
        assertTrue(lines.first().endsWith("step 5 next"))
        assertTrue(lines.last().endsWith("step ${DiagLog.MAX_LINES + 4} next"))
        DiagLog.clear()
        DiagLog.add("x".repeat(1000))
        assertTrue(DiagLog.text().length < DiagLog.MAX_LINE_CHARS + 20)
    }
}
