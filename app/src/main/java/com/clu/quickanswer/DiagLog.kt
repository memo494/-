package com.clu.quickanswer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the last answers did, step by step (capture, reading on the device, model, touch), kept in
 * memory only. The owner copies it from the settings ("نسخ سجل التشخيص") when something goes wrong,
 * so a failure on the phone can be traced instead of guessed. The answer panel stays short: details
 * go here, not on the screen. Never written to disk; the newest [MAX_LINES] lines are kept.
 *
 * Pure Kotlin, no android.*.
 */
object DiagLog {
    const val MAX_LINES = 80

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Adds [line] with the time of day. Lines longer than [MAX_LINE_CHARS] are cut. */
    @Synchronized
    fun add(line: String) {
        lines.addLast(clock.format(Date()) + " " + line.replace('\n', ' ').take(MAX_LINE_CHARS))
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    const val MAX_LINE_CHARS = 300
}
