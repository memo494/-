package com.clu.quickanswer

/** Hiding an overlay can leave its last captured frame queued; wait briefly before declaring a change. */
internal class TouchPreflight(private val schedule: (Long, () -> Unit) -> Unit) {
    fun awaitReady(isCurrent: () -> Boolean, ready: () -> Boolean, result: (Boolean) -> Unit) {
        var attempts = 0
        fun poll() {
            val current = try { isCurrent() } catch (_: Throwable) { false }
            if (!current) { result(false); return }
            val allowed = try { ready() } catch (_: Throwable) { false }
            attempts++
            if (allowed || attempts >= 3) result(allowed) else schedule(100L, ::poll)
        }
        poll()
    }
}
