package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calibration taps: the region search never runs in the touch callback (Codex review of #47). */
class SnapQueueTest {
    private val worker = ArrayDeque<Runnable>()
    private val main = ArrayDeque<Runnable>()
    private val queue = SnapQueue({ worker.addLast(it) }, { main.addLast(it) })
    private val box = PxRect(1, 2, 30, 40)

    private fun runAll() {
        while (worker.isNotEmpty()) worker.removeFirst().run()
        while (main.isNotEmpty()) main.removeFirst().run()
    }

    @Test fun theSearchRunsOnTheWorkerNotInTheCaller() {
        var searched = false
        var got: PxRect? = null
        assertTrue(queue.request({ searched = true; box }) { got = it })
        assertFalse("nothing ran in the touch callback", searched)
        runAll()
        assertTrue(searched)
        assertEquals(box, got)
    }

    @Test fun aTapWhileSearchingIsIgnored() {
        var second = false
        assertTrue(queue.request({ box }) {})
        assertTrue(queue.busy)
        assertFalse(queue.request({ second = true; box }) {})
        runAll()
        assertFalse(second)
        assertFalse(queue.busy)
        assertTrue("free again after the result", queue.request({ box }) {})
    }

    @Test fun aResultAfterTheModeChangedIsDropped() {
        var delivered = false
        queue.request({ box }) { delivered = true }
        queue.invalidate()
        runAll()
        assertFalse(delivered)
        assertFalse(queue.busy)
    }

    @Test fun aFailingSearchGivesNothing() {
        var got: PxRect? = box
        var called = false
        queue.request({ throw OutOfMemoryError("capture") }) { got = it; called = true }
        runAll()
        assertTrue(called)
        assertNull(got)
    }
}
