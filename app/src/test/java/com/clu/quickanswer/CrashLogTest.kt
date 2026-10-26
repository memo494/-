package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for the on-device crash log text. */
class CrashLogTest {

    private fun crash(message: String = "boom"): Throwable =
        IllegalStateException(message, IllegalArgumentException("root cause"))

    private fun entry(message: String = "boom") =
        CrashLog.entry(crash(message), "main", "2026-10-27T00:00:00Z", "1.0.1234")

    @Test fun entryStartsWithMarker() {
        assertTrue(entry().startsWith(CrashLog.ENTRY_MARKER))
    }

    @Test fun entryHasTimeVersionThreadAndError() {
        val e = entry("overlay died")
        assertTrue(e.contains("2026-10-27T00:00:00Z"))
        assertTrue(e.contains("1.0.1234"))
        assertTrue(e.contains("main"))
        assertTrue(e.contains("IllegalStateException"))
        assertTrue(e.contains("overlay died"))
    }

    @Test fun entryIncludesCauseAndStackFrames() {
        val e = entry()
        assertTrue("cause missing", e.contains("root cause"))
        assertTrue("no stack frames", e.contains("at "))
    }

    @Test fun appendToEmptyGivesOneEntry() {
        val log = CrashLog.append("", entry())
        assertEquals(1, CrashLog.count(log))
    }

    @Test fun appendKeepsOrderNewestLast() {
        val log = CrashLog.append(CrashLog.append("", entry("first")), entry("second"))
        assertEquals(2, CrashLog.count(log))
        assertTrue(log.indexOf("first") < log.indexOf("second"))
    }

    @Test fun dropsOldestWholeEntriesToFit() {
        var log = ""
        repeat(10) { log = CrashLog.append(log, entry("crash-$it"), maxChars = 4 * entry().length) }
        assertTrue(log.length <= 4 * entry().length)
        assertTrue("newest kept", log.contains("crash-9"))
        assertFalse("oldest dropped", log.contains("crash-0"))
        assertTrue("every entry is whole", log.trimStart().startsWith(CrashLog.ENTRY_MARKER))
    }

    @Test fun oversizedSingleEntryIsTruncatedNotLost() {
        val big = entry() + "x".repeat(10_000)
        val log = CrashLog.append("", big, maxChars = 500)
        assertTrue(log.length <= 500)
        assertTrue(log.startsWith(CrashLog.ENTRY_MARKER))
        assertEquals(1, CrashLog.count(log))
    }

    @Test fun countOfEmptyLogIsZero() {
        assertEquals(0, CrashLog.count(""))
    }
}
