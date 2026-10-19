package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect

/**
 * The answer that auto mode hid because the screen changed under it. If the change turns out to be
 * a blip (a heads-up notification, a peek at the shade) and the screen goes back to what the answer
 * was for, the answer can come back for the time it had left, with the green box it had. Pure
 * Kotlin: times are plain milliseconds on whatever clock the caller uses.
 */
class HiddenAnswer {

    /** The green box of an answer: the option's button and the screen size it was measured on. */
    data class Box(val rect: PxRect, val screenW: Int, val screenH: Int)

    /** An answer to show again, with how long it should stay and the [box] to draw again (if it had one). */
    data class Restore(val text: String, val leftMs: Long, val box: Box? = null)

    private var text: String? = null
    private var deadline = 0L
    private var box: Box? = null

    /** Remembers [text], which was due to disappear by itself at [deadlineMs], and its [box]. */
    fun remember(text: String, deadlineMs: Long, box: Box? = null) {
        this.text = text
        this.deadline = deadlineMs
        this.box = box
    }

    /** Forget it: a new answer is coming, or the user asked again. */
    fun clear() {
        text = null
        box = null
    }

    /**
     * The remembered answer if it still has at least [minLeftMs] to live at [now], else null. Either
     * way it is forgotten, so an answer comes back at most once.
     */
    fun take(now: Long, minLeftMs: Long): Restore? {
        val t = text ?: return null
        val b = box
        text = null
        box = null
        val left = deadline - now
        return if (left >= minLeftMs) Restore(t, left, b) else null
    }
}
