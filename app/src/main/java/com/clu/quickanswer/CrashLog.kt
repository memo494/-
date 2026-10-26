package com.clu.quickanswer

/**
 * The text of the on-device crash log ("سجل الأعطال"): one entry per crash, newest last, kept
 * under [MAX_CHARS] so the file never grows without bound. The Android side (an uncaught-exception
 * handler that writes the file, then hands the crash on) stays thin; this part is pure Kotlin and
 * unit-tested.
 *
 * Each entry starts with [ENTRY_MARKER] on its own line, followed by the time, app version, thread
 * name and the full stack trace (causes included).
 */
object CrashLog {
    /** First line of every entry; also how [append] finds where entries start. */
    const val ENTRY_MARKER = "=== Clu crash ==="

    /** Cap for the whole log: a few dozen typical stack traces, small enough to share easily. */
    const val MAX_CHARS = 64 * 1024

    /**
     * One entry for [error] thrown on [threadName]. [timeUtc] is an ISO-8601 UTC timestamp supplied by
     * the caller (keeps this function deterministic). Must include [versionName], [threadName], the
     * exception class and message, and the stack trace of every cause.
     */
    fun entry(error: Throwable, threadName: String, timeUtc: String, versionName: String): String =
        TODO("Implement per the issue")

    /**
     * [existing] log text (may be empty) with [entry] appended as the newest entry. When the result
     * would exceed [maxChars], drop whole entries from the oldest end until it fits; never cut an
     * entry in half. A single entry longer than [maxChars] is truncated from its end so the log is
     * never empty after a crash.
     */
    fun append(existing: String, entry: String, maxChars: Int = MAX_CHARS): String =
        TODO("Implement per the issue")

    /** Number of entries in [log]. */
    fun count(log: String): Int = TODO("Implement per the issue")
}
