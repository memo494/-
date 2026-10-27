package com.clu.quickanswer

/**
 * One buffer of a given size, kept and handed out again while the size stays the same; a new one is
 * made (and the old one dropped) only when the size changes. Screen capture used to allocate a whole
 * new frame (about 10 MB for 1080 x 2400, twice with padded rows) for every changed frame, twice a
 * second in auto mode, which kept the garbage collector busy. Not thread-safe. Pure Kotlin.
 */
class ReusedBuffer<T : Any>(private val make: (Int, Int) -> T, private val drop: (T) -> Unit = {}) {

    private var buffer: T? = null
    private var width = -1
    private var height = -1

    /** The kept buffer when it is [w] x [h], else a new one (the old one is dropped first). */
    fun get(w: Int, h: Int): T {
        buffer?.let {
            if (w == width && h == height) return it
            drop(it)
        }
        buffer = null
        return make(w, h).also { buffer = it; width = w; height = h }
    }

    /** Drops the kept buffer; the next [get] makes a new one. */
    fun clear() {
        buffer?.let(drop)
        buffer = null
        width = -1
        height = -1
    }
}
