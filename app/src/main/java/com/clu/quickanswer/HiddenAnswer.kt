package com.clu.quickanswer

/**
 * The answer that auto mode hid because the screen changed under it. If the change turns out to be
 * a blip (a heads-up notification, a peek at the shade) and the screen goes back to what the answer
 * was for, the answer can come back for the time it had left. Pure Kotlin: times are plain
 * milliseconds on whatever clock the caller uses.
 */
class HiddenAnswer {

    /** An answer to show again, with how long it should stay. */
    data class Restore(val text: String, val leftMs: Long)

    private var text: String? = null
    private var deadline = 0L

    /** Remembers [text], which was due to disappear by itself at [deadlineMs]. */
    fun remember(text: String, deadlineMs: Long) {
        this.text = text
        this.deadline = deadlineMs
    }

    /** Forget it: a new answer is coming, or the user asked again. */
    fun clear() {
        text = null
    }

    /**
     * The remembered answer if it still has at least [minLeftMs] to live at [now], else null. Either
     * way it is forgotten, so an answer comes back at most once.
     */
    fun take(now: Long, minLeftMs: Long): Restore? {
        val t = text ?: return null
        text = null
        val left = deadline - now
        return if (left >= minLeftMs) Restore(t, left) else null
    }
}
