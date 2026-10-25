package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** [ParallelLines] keeps the order of the lines and the failures of [LineBatch.SEQUENTIAL]; see also OcrPipelineTest. */
class LineBatchTest {

    private fun line(text: String) = RecognizedLine(text, 0.9 * text.length, text.length)

    @Test
    fun results_comeBackInTheOrderOfTheJobs_evenWhenLaterJobsFinishFirst() {
        ParallelLines(4).use { batch ->
            val jobs = (0 until 9).map { i -> { Thread.sleep((9 - i) * 5L); line("l$i") } }
            assertEquals((0 until 9).map { "l$it" }, batch.recognize(jobs).map { it.text })
        }
    }

    @Test
    fun aFailingLine_isRethrownOnTheCallingThread() {
        ParallelLines(3).use { batch ->
            val jobs = listOf({ line("a") }, { throw OutOfMemoryError("test") }, { line("c") })
            assertThrows(OutOfMemoryError::class.java) { batch.recognize(jobs) }
        }
    }

    @Test
    fun aFailingFirstLine_isRethrownToo() {
        ParallelLines(2).use { batch ->
            val jobs = listOf<() -> RecognizedLine>({ throw IllegalStateException("first") }, { line("b") })
            assertThrows(IllegalStateException::class.java) { batch.recognize(jobs) }
        }
    }

    @Test
    fun noJobsAndOneJob_needNoPool() {
        ParallelLines(4).use { batch ->
            assertEquals(emptyList<RecognizedLine>(), batch.recognize(emptyList()))
            assertEquals(listOf("x"), batch.recognize(listOf({ line("x") })).map { it.text })
        }
    }
}
