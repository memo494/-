package com.clu.quickanswer

import kotlin.math.abs

/**
 * Pure maths for the auto mode's "did the screen change?" check. A signature is a small grid of
 * luminance values; cells that one of our own overlays (bubble, answer panel, image preview)
 * touches hold [IGNORED] instead, so those overlays appearing, moving or disappearing never counts
 * as the game changing.
 */
object FrameSignature {

    const val IGNORED = -1

    /**
     * How often auto mode looks at the screen. [judge] counts in ticks: "stable" means unchanged
     * between two consecutive ticks, so a picture must hold still for one tick ([TICK_MS]) before
     * it is sent, and a change is noticed at most one tick after it happens. A new question is
     * therefore sent 1 to 2 ticks after it appears (0.5 to 1 s), or 2 to 3 ticks when our answer
     * panel was in the frame (the first tick only hides it). Shorter ticks react faster but also
     * shrink how long a transient screen (an answer-reveal animation, a loading card) may last
     * before it is treated as a settled one. Everything measured in ticks (the simulations in the
     * tests) derives its wall-clock time from this one constant.
     *
     * 400 ms (was 500): a new question is sent 0.4-0.8 s after it appears instead of 0.5-1 s, now that
     * a tick no longer allocates a full-screen frame (ScreenCapture keeps its buffers). Kept above the
     * typical answer-reveal / card animation step, so a screen still has to hold still for 0.4 s.
     */
    const val TICK_MS = 400L

    /**
     * Marks every cell of the [size]x[size] grid [sig] that the rectangle (in pixels of an image
     * [imgW]x[imgH]) touches. Parts of the rectangle outside the image are ignored.
     */
    fun ignoreRect(
        sig: IntArray, size: Int, imgW: Int, imgH: Int,
        left: Int, top: Int, right: Int, bottom: Int
    ) {
        val l = maxOf(left, 0)
        val t = maxOf(top, 0)
        val r = minOf(right, imgW)
        val b = minOf(bottom, imgH)
        if (l >= r || t >= b || imgW <= 0 || imgH <= 0) return
        val x0 = (l.toLong() * size / imgW).toInt().coerceIn(0, size - 1)
        val x1 = ((r - 1).toLong() * size / imgW).toInt().coerceIn(0, size - 1)
        val y0 = (t.toLong() * size / imgH).toInt().coerceIn(0, size - 1)
        val y1 = ((b - 1).toLong() * size / imgH).toInt().coerceIn(0, size - 1)
        for (y in y0..y1) for (x in x0..x1) sig[y * size + x] = IGNORED
    }

    /** Mean absolute luminance difference over the cells neither signature ignores (0 if none). */
    fun diff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        var n = 0
        for (i in a.indices) {
            if (a[i] == IGNORED || b[i] == IGNORED) continue
            sum += abs(a[i] - b[i])
            n++
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /** True when both signatures ignore exactly the same cells (same overlays in the same places). */
    fun sameMask(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if ((a[i] == IGNORED) != (b[i] == IGNORED)) return false
        return true
    }

    /** What one auto-mode tick should do, see [judge]. */
    enum class Verdict {
        /** The game looks the same as what was last sent: nothing to do. */
        IDLE,

        /** The game changed but has not settled yet: look again on the next tick. */
        WAIT,

        /**
         * The game changed while our answer panel was in the frame. The panel is painted black in
         * what we send, so it could black out part of the new question: hide it and judge again
         * on the next ticks, with a frame that no longer contains it. Hiding is not final: if the
         * change was a blip, the service brings the answer back (see [HiddenAnswer]).
         */
        HIDE_PANEL,

        /** The game changed, the answer panel is not in the frame, and the picture is stable: send it. */
        SEND
    }

    /**
     * Decides what an auto-mode tick does with the signature [sig] of the current frame.
     *
     * - [prev]: signature of the previous tick (null if none), [lastSent]: signature of the frame
     *   that was sent last (null if none yet).
     * - [panelInFrame]: our answer panel was showing when the frame was taken.
     *
     * "Changed" is measured against [lastSent] over the cells neither side ignores. A change with
     * the panel showing only hides the panel (never sends). The next ticks see a frame without the
     * panel, whose ignored cells differ from the panel frame's, so [sameMask] fails once and the
     * tick after that is the first that can be stable: the picture is judged stable only when two
     * consecutive ticks ignore the same cells and match each other.
     *
     * This converges: the panel only comes back after an answer, and an answer only comes after a
     * SEND, so there is at most one HIDE_PANEL per SEND and never a hide/send cycle.
     */
    fun judge(
        sig: IntArray,
        prev: IntArray?,
        lastSent: IntArray?,
        panelInFrame: Boolean,
        changeThreshold: Double,
        stableThreshold: Double
    ): Verdict {
        val changed = lastSent == null || diff(sig, lastSent) > changeThreshold
        if (!changed) return Verdict.IDLE
        if (panelInFrame) return Verdict.HIDE_PANEL
        val stable = prev != null && sameMask(sig, prev) && diff(sig, prev) < stableThreshold
        return if (stable) Verdict.SEND else Verdict.WAIT
    }
}
