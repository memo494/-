package com.clu.quickanswer.ocr

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs the line recognitions of one screen. Each line is independent (same crop, same network, same
 * decode), so the order they run in never changes what is read; only the wall time changes.
 */
fun interface LineBatch {
    /** Runs every job and returns their results in the order of [jobs]. */
    fun recognize(jobs: List<() -> RecognizedLine>): List<RecognizedLine>

    companion object {
        /** One line after the other on the calling thread. */
        val SEQUENTIAL = LineBatch { jobs -> jobs.map { it() } }
    }
}

/**
 * Recognises the lines of a screen on [threads] threads at once: the calling thread takes the
 * first line, a small pool the rest. A short line costs almost as much fixed overhead as a long one,
 * so several small runs side by side beat one run after the other with more threads each.
 *
 * A failure in a job (including an [Error]) is rethrown on the calling thread, like
 * [LineBatch.SEQUENTIAL] would.
 */
class ParallelLines(threads: Int) : LineBatch, AutoCloseable {

    private val pool: ExecutorService = Executors.newFixedThreadPool(maxOf(1, threads - 1)) { r ->
        Thread(r, "clu-ocr-line").apply { isDaemon = true }
    }

    override fun recognize(jobs: List<() -> RecognizedLine>): List<RecognizedLine> {
        if (jobs.size <= 1) return jobs.map { it() }
        val rest = jobs.drop(1).map { pool.submit(Callable(it)) }
        val first = try {
            jobs[0]()
        } catch (t: Throwable) {
            rest.forEach { it.cancel(true) }
            throw t
        }
        return listOf(first) + rest.map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    override fun close() {
        pool.shutdownNow()
    }
}
