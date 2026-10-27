package com.clu.quickanswer

/**
 * The pixels of one question's capture, read from it at most once. Reading the question and preparing
 * the taps each copied the whole capture into a new array (about 10 MB for 1080 x 2400); they now
 * share this one. [load] runs on the first [get] only, and not at all when nobody asks (no reading on
 * the device, no taps). One question, one thread: not thread-safe. Pure Kotlin.
 */
class PixelsOnce(private val load: () -> IntArray) {

    private var pixels: IntArray? = null

    /** The pixels, read now if they were not yet. Read-only for the caller. */
    fun get(): IntArray = pixels ?: load().also { pixels = it }
}
