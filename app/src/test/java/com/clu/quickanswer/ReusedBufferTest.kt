package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Capture frames are copied into kept buffers: nothing is allocated per frame while the size holds. */
class ReusedBufferTest {
    private var made = 0
    private val dropped = mutableListOf<IntArray>()
    private val buffers = ReusedBuffer({ w, h -> made++; IntArray(w * h) }, { dropped += it })

    @Test fun theSameSizeGivesTheSameBufferWithoutMakingAnother() {
        val first = buffers.get(1080, 2400)
        repeat(100) { assertSame(first, buffers.get(1080, 2400)) }
        assertEquals(1, made)
        assertEquals(0, dropped.size)
    }

    @Test fun aNewSizeDropsTheOldBufferAndMakesOne() {
        val portrait = buffers.get(1080, 2400)
        val landscape = buffers.get(2400, 1080)
        assertNotSame(portrait, landscape)
        assertEquals(2, made)
        assertEquals(listOf(portrait), dropped)
    }

    @Test fun clearDropsTheBufferAndTheNextGetMakesANewOne() {
        val first = buffers.get(10, 10)
        buffers.clear()
        assertEquals(listOf(first), dropped)
        assertNotSame(first, buffers.get(10, 10))
        assertEquals(2, made)
        buffers.clear()
        buffers.clear() // nothing left to drop
        assertEquals(2, dropped.size)
    }
}
