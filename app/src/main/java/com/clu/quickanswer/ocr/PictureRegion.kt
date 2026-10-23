package com.clu.quickanswer.ocr

/**
 * The picture a quiz question shows (see [LayoutTemplate.picture]): whether one is pinned, and a
 * short fingerprint of it, so a question about a picture is asked with the picture and its answer is
 * remembered per picture. Pure Kotlin, no android.*.
 */
object PictureRegion {

    /** Lowest luma standard deviation at which an area shows a picture. */
    const val MIN_STD = 20.0

    /** Largest [distance], in bits of [HASH_BITS], at which two hashes are the same picture. */
    const val SAME_PICTURE_MAX = 24

    /** Cells per side of the [hash] grid. */
    const val HASH_SIDE = 16

    /** Bits in a [hash] and characters in it (four bits per hex character). */
    const val HASH_BITS = HASH_SIDE * HASH_SIDE
    const val HASH_CHARS = HASH_BITS / 4

    /** The part of [picture] that is plain background when no photo is pinned: its right half. */
    fun detectArea(picture: PxRect): PxRect = TODO()

    fun isShown(argb: IntArray, width: Int, height: Int, rect: PxRect): Boolean = TODO()

    fun hash(argb: IntArray, width: Int, height: Int, rect: PxRect): String = TODO()

    fun distance(a: String, b: String): Int = TODO()
}
