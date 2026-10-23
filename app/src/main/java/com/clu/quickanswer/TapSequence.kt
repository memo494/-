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
        // Let the game apply the final digit before its confirmation action is pressed.
        const val SUBMIT_GAP_MS = 120L
        // A lost platform callback must not leave auto mode permanently busy or replay a digit.
        const val CALLBACK_TIMEOUT_MS = 750L
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
    ) {
        var index = 0
    }

    private var active: Run? = null

    fun start(
        boxes: List<PxRect>,
        submitLast: Boolean = false,
        canContinue: () -> Boolean = { true },
        result: (Boolean) -> Unit,
    ): Boolean {
        if (active != null || boxes.size !in 1..MAX_TAPS ||
            (submitLast && boxes.size < 2) || boxes.any { !valid(it) }
        ) return false
        val run = Run(boxes.toList(), submitLast, canContinue, result)
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
        val allowed = try { run.canContinue() } catch (_: Exception) { false }
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
                    } else RELEASE_GAP_MS
                    later(run, delay) { tap(run) }
                }
            }
        }
        val accepted = try {
            dispatch(run.boxes[index], pressDurationMs, completed)
        } catch (_: Exception) {
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
        } catch (_: Exception) {
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
