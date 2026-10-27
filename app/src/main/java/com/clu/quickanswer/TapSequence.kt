package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect

/**
 * One gesture at a time: the UP callback and a gap separate even identical keys. Call on one
 * thread. Every accepted run reports one result; a refused run reports none and never taps.
 */
internal class TapSequence(
    private val dispatch: (PxRect, Long, (Boolean) -> Unit) -> Boolean,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val pressDurationMs: Long = PRESS_MS,
) {
    companion object {
        // Shorter than a long press, with an explicit release interval for game input polling.
        const val PRESS_MS = 45L
        // At 15 fps a game polls every 67ms: a 45ms DOWN can fall entirely between two frames.
        // Accessibility game taps use 90ms while preserving the explicit UP gap between repeats.
        const val GAME_PRESS_MS = 90L
        const val RELEASE_GAP_MS = 40L
        // Before the confirmation: just past the final digit's UP. The field is then read back
        // (NumericEntryCheck.READBACK_*) until it shows the number, so the game's render time is
        // waited for there, not here; nothing is confirmed unread (was 120 ms of fixed wait).
        const val SUBMIT_GAP_MS = 40L
        // Give the reset a render interval before the first digit and its field baseline capture.
        const val RESET_GAP_MS = 120L
        // A lost platform callback must not leave auto mode permanently busy or replay a digit.
        const val CALLBACK_TIMEOUT_MS = 750L
        const val VERIFICATION_TIMEOUT_MS = 2_000L
        const val MAX_TAPS = 9 // four backspaces, four digits, then confirmation
    }

    init {
        require(pressDurationMs in 45L..200L) { "Tap duration must stay below long-press timing" }
    }

    private class Run(
        val boxes: List<PxRect>,
        val submitLast: Boolean,
        val canContinue: () -> Boolean,
        val result: (Boolean) -> Unit,
        val beforeSubmit: ((Boolean) -> Unit) -> Unit,
        val resetCount: Int,
    ) {
        var index = 0
        var verified = false
    }

    private var active: Run? = null

    fun start(
        boxes: List<PxRect>,
        submitLast: Boolean = false,
        canContinue: () -> Boolean = { true },
        beforeSubmit: ((Boolean) -> Unit) -> Unit = { complete -> complete(true) },
        resetCount: Int = 0,
        result: (Boolean) -> Unit,
    ): Boolean {
        if (active != null || boxes.size !in 1..MAX_TAPS ||
            (submitLast && boxes.size < 2) || boxes.any { !valid(it) } ||
            resetCount !in 0..4 || resetCount >= boxes.size - (if (submitLast) 1 else 0)
        ) return false
        val run = Run(boxes.toList(), submitLast, canContinue, result, beforeSubmit, resetCount)
        active = run
        tap(run)
        return true
    }

    /** An already dispatched short stroke can finish, but no later key or submit is sent. */
    fun cancel() {
        active?.let { finish(it, false) }
    }

    private fun tap(run: Run) {
        if (active !== run) return
        if (run.submitLast && run.index == run.boxes.lastIndex && !run.verified) {
            var delivered = false
            val verified: (Boolean) -> Unit = verify@{ allowed ->
                if (active !== run || delivered) return@verify
                delivered = true
                if (!allowed) finish(run, false) else {
                    run.verified = true
                    tap(run)
                }
            }
            try { run.beforeSubmit(verified) } catch (_: Throwable) { verified(false) }
            if (!delivered && active === run) later(run, VERIFICATION_TIMEOUT_MS) { verified(false) }
            return
        }
        val allowed = try { run.canContinue() } catch (_: Throwable) { false }
        // A guard can itself cancel the run (for example when capture stops).
        if (active !== run) return
        if (!allowed) {
            finish(run, false)
            return
        }
        val index = run.index
        var delivered = false
        val completed: (Boolean) -> Unit = completed@{ success ->
            if (active !== run || run.index != index || delivered) return@completed
            delivered = true
            if (!success) {
                finish(run, false)
            } else {
                run.index++
                if (run.index == run.boxes.size) {
                    finish(run, true)
                } else {
                    val delay = if (run.submitLast && run.index == run.boxes.lastIndex) {
                        SUBMIT_GAP_MS
                    } else if (run.index == run.resetCount) {
                        RESET_GAP_MS
                    } else RELEASE_GAP_MS
                    later(run, delay) { tap(run) }
                }
            }
        }
        val accepted = try {
            dispatch(run.boxes[index], pressDurationMs, completed)
        } catch (_: Throwable) {
            false
        }
        if (!accepted) {
            completed(false)
        } else if (!delivered && active === run) {
            later(run, CALLBACK_TIMEOUT_MS) { completed(false) }
        }
    }

    private fun later(run: Run, delay: Long, action: () -> Unit) {
        try {
            schedule(delay) { if (active === run) action() }
        } catch (_: Throwable) {
            finish(run, false)
        }
    }

    private fun finish(run: Run, success: Boolean) {
        if (active !== run) return
        active = null
        run.result(success)
    }

    private fun valid(rect: PxRect): Boolean =
        rect.left >= 0 && rect.top >= 0 && rect.width >= 8 && rect.height >= 8 &&
            rect.centerX >= rect.left && rect.centerX < rect.right &&
            rect.centerY >= rect.top && rect.centerY < rect.bottom
}
