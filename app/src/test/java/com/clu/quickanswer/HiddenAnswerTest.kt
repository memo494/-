package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenAnswerTest {

    @Test
    fun nothingRemembered_givesNothing() {
        assertNull(HiddenAnswer().take(0, 1000))
    }

    @Test
    fun remembered_comesBackWithTheTimeItHasLeft() {
        val h = HiddenAnswer()
        h.remember("ب", deadlineMs = 15_000)
        assertEquals(HiddenAnswer.Restore("ب", 9_000), h.take(now = 6_000, minLeftMs = 1_000))
    }

    @Test
    fun comesBackAtMostOnce() {
        val h = HiddenAnswer()
        h.remember("ب", 15_000)
        h.take(0, 1000)
        assertNull(h.take(0, 1000))
    }

    @Test
    fun tooLittleTimeLeft_isForgotten() {
        val h = HiddenAnswer()
        h.remember("ب", 15_000)
        assertNull(h.take(now = 14_500, minLeftMs = 1_000))
        assertNull(h.take(now = 0, minLeftMs = 1_000)) // and stays forgotten
    }

    @Test
    fun exactlyTheMinimumLeft_stillComesBack() {
        val h = HiddenAnswer()
        h.remember("ب", 15_000)
        assertEquals(1_000L, h.take(now = 14_000, minLeftMs = 1_000)?.leftMs)
    }

    @Test
    fun clear_forgets() {
        val h = HiddenAnswer()
        h.remember("ب", 15_000)
        h.clear()
        assertNull(h.take(0, 1000))
    }

    @Test
    fun rememberingAgain_replacesTheOldAnswer() {
        val h = HiddenAnswer()
        h.remember("أ", 5_000)
        h.remember("ج", 20_000)
        assertEquals(HiddenAnswer.Restore("ج", 20_000), h.take(0, 1000))
    }
}
