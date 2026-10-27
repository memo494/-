package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect

/**
 * Finds the control under a calibration tap ([com.clu.quickanswer.ocr.RegionSnap]) off the main
 * thread. One search grows up to nine regions over the whole capture (millions of pixel visits and a
 * capture-sized array each), which froze the screen when it ran in the touch callback (Codex review
 * of #47). One search at a time: a tap while one runs is ignored. A result that arrives after
 * [invalidate] (the mode changed, the screen closed) is dropped.
 *
 * [work] runs a job on a worker thread, [deliver] posts one to the main thread; [request],
 * [invalidate] and the results run on the main thread only, so no lock is needed. Pure Kotlin.
 */
class SnapQueue(private val work: (Runnable) -> Unit, private val deliver: (Runnable) -> Unit) {

    private var running = false
    private var generation = 0

    /** Whether a search is running (a new tap is ignored meanwhile). */
    val busy: Boolean get() = running

    /**
     * Starts [find] on the worker and hands its result (null when it found nothing or failed) to
     * [onResult] on the main thread. False, and nothing starts, while another search runs.
     */
    fun request(find: () -> PxRect?, onResult: (PxRect?) -> Unit): Boolean {
        if (running) return false
        running = true
        val asked = generation
        work(Runnable {
            val found = try {
                find()
            } catch (_: Throwable) {
                null
            }
            deliver(Runnable {
                running = false
                if (asked == generation) onResult(found)
            })
        })
        return true
    }

    /** Drops the result of every search started before now. */
    fun invalidate() {
        generation++
    }
}
