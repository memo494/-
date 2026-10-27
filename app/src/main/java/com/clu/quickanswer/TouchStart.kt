package com.clu.quickanswer

/**
 * What happens between an answer and its first tap. The answer used to be shown first and then hidden
 * again for the taps: 80 ms to show it, 100 ms after hiding it, then a wait for the first frame drawn
 * without it (236-272 ms on the owner's phone, 26 Oct 2026) so the checks compare the question with a
 * clean screen. About 0.4 s per touched answer. Now an answer that will be tapped is not shown first,
 * and the waits apply only when something of ours is still on screen. Pure Kotlin.
 */
object TouchStart {

    /** Lets a panel that was just shown be drawn (and seen) before it is taken away again. */
    const val SETTLE_MS = 80L

    /** After hiding the panel and the box, before looking for a frame without them. */
    const val AFTER_HIDE_MS = 100L

    /** [settleMs] first; [hide] the panel and box, wait [AFTER_HIDE_MS] and for a clean frame. */
    data class Plan(val settleMs: Long, val hide: Boolean)

    fun plan(panelShown: Boolean, boxShown: Boolean): Plan =
        if (panelShown || boxShown) Plan(SETTLE_MS, hide = true) else Plan(0L, hide = false)
}
