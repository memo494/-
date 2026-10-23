package com.clu.quickanswer.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The picture a quiz question shows (see [LayoutTemplate.picture]): whether one is pinned, and a
 * short fingerprint of it. A question about a picture cannot be answered from its words, so the
 * picture is sent with it ([isShown] decides when), and the answer is remembered per picture
 * ([hash] and [distance] say which one). Pure Kotlin, no android.*.
 *
 * Every function fails closed on a rect or an image that cannot be used: [isShown] is false, and
 * [distance] is [Int.MAX_VALUE], so a question is never taken for one with a picture, or for the
 * same picture, on a guess.
 */
object PictureRegion {

    /**
     * Lowest luma standard deviation (0..255 scale) at which [isShown] says a picture is pinned.
     * Measured on the owner's 2712 x 1220 captures of the parchment game, over [detectArea]: with no
     * picture (plain parchment) 4.4 to 11.4; with a picture 31.5 (a white diagram, the weakest) to 89.
     * 20 sits in the middle of that gap, closer to the plain side because a missed picture is the worse
     * mistake (its question would be answered from words alone).
     */
    const val MIN_STD = 20.0

    /**
     * Largest [distance] at which two [hash]es are the same picture, of [HASH_BITS] (about 9 %). The
     * same picture seen twice differs by a few bits (screen-capture compression, a notification
     * darkening a corner); two different pictures differ by about half of them. A guess to be
     * recalibrated on more captures.
     */
    const val SAME_PICTURE_MAX = 24

    /** Cells per side of the [hash] grid: 16 x 16 is the usual average-hash size, 256 bits in 64 hex characters. */
    const val HASH_SIDE = 16

    /** Bits in a [hash], and its length in characters (four bits per hex character). */
    const val HASH_BITS = HASH_SIDE * HASH_SIDE
    const val HASH_CHARS = HASH_BITS / 4

    /**
     * Where to look for a picture inside [picture] (the rect where the game pins it): its right half.
     * With no photo pinned the right half is plain parchment, but the left part of the area is the dark
     * sea behind it on the parchment game, so the whole rect is never uniform.
     */
    fun detectArea(picture: PxRect): PxRect = PxRect((picture.left + picture.right) / 2, picture.top, picture.right, picture.bottom)

    /**
     * Whether [rect] of [argb] (row-major ARGB, [width] x [height]) shows a picture: the standard
     * deviation of its luma is at least [MIN_STD] (see [lumaStd]). False for a rect with no pixel inside
     * the image and for an array that is too small for the size given.
     */
    fun isShown(argb: IntArray, width: Int, height: Int, rect: PxRect): Boolean {
        val std = lumaStd(argb, width, height, rect) ?: return false
        return std >= MIN_STD
    }

    /**
     * The standard deviation of the luma of [rect] (clamped to the image), or null when no pixel of it
     * is inside the image or [argb] is smaller than [width] x [height]. Every pixel is read: the area
     * is a few tens of thousands of pixels, and thin lines (a diagram) must not slip between samples.
     */
    internal fun lumaStd(argb: IntArray, width: Int, height: Int, rect: PxRect): Double? {
        val r = clamp(argb, width, height, rect) ?: return null
        var n = 0L
        var sum = 0L
        var sumSq = 0L
        for (y in r.top until r.bottom) {
            val row = y * width
            for (x in r.left until r.right) {
                val l = luma(argb[row + x])
                sum += l
                sumSq += l.toLong() * l
                n++
            }
        }
        val mean = sum.toDouble() / n
        // Exact for the integer levels it is given; max() only guards a rounding error below zero.
        return sqrt(max(0.0, sumSq.toDouble() / n - mean * mean))
    }

    /**
     * A 16 x 16 average hash of the luma of [rect] (clamped to the image): the rect is cut into
     * [HASH_SIDE] x [HASH_SIDE] cells, each cell's mean luma is compared with the mean of all cells, and
     * a brighter cell is a 1. Row by row, four cells to a hex digit, most significant first: 64
     * lowercase hex characters. Insensitive to brightness and to scale, so the same picture shown
     * twice gives (almost) the same hash; compare two with [distance].
     *
     * @throws IllegalArgumentException when no pixel of [rect] is inside the image; check [isShown]
     *   first (it is false then).
     */
    fun hash(argb: IntArray, width: Int, height: Int, rect: PxRect): String {
        val r = requireNotNull(clamp(argb, width, height, rect)) { "no picture area to hash" }
        val w = r.width
        val h = r.height
        val cells = DoubleArray(HASH_BITS)
        for (cy in 0 until HASH_SIDE) {
            // The same cell edges as ScreenFingerprint; a rect under 16 pixels wide or high reuses pixels.
            val y0 = min(h - 1, cy * h / HASH_SIDE)
            val y1 = max(y0 + 1, (cy + 1) * h / HASH_SIDE)
            for (cx in 0 until HASH_SIDE) {
                val x0 = min(w - 1, cx * w / HASH_SIDE)
                val x1 = max(x0 + 1, (cx + 1) * w / HASH_SIDE)
                var sum = 0L
                for (y in y0 until y1) {
                    val row = (r.top + y) * width + r.left
                    for (x in x0 until x1) sum += luma(argb[row + x])
                }
                cells[cy * HASH_SIDE + cx] = sum.toDouble() / ((y1 - y0).toLong() * (x1 - x0))
            }
        }
        val mean = cells.average()
        val sb = StringBuilder(HASH_CHARS)
        for (i in 0 until HASH_CHARS) {
            var digit = 0
            for (k in 0 until 4) digit = (digit shl 1) or (if (cells[i * 4 + k] > mean) 1 else 0)
            sb.append(Character.forDigit(digit, 16))
        }
        return sb.toString()
    }

    /**
     * The number of bits in which two [hash]es differ, or [Int.MAX_VALUE] when either is not a hash
     * ([HASH_CHARS] hex characters, upper or lower case). Two empty or short strings are not "the same
     * picture".
     */
    fun distance(a: String, b: String): Int {
        if (a.length != HASH_CHARS || b.length != HASH_CHARS) return Int.MAX_VALUE
        var bits = 0
        for (i in 0 until HASH_CHARS) {
            val x = hexValue(a[i])
            val y = hexValue(b[i])
            if (x < 0 || y < 0) return Int.MAX_VALUE
            bits += Integer.bitCount(x xor y)
        }
        return bits
    }

    // Not Character.digit: that also reads digits of other scripts (Arabic-Indic, full-width).
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** 0..255, the weights [ScreenFingerprint] and the template signatures use. */
    private fun luma(p: Int): Int = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000

    /** [rect] cut to the image, or null when nothing of it is inside or [argb] is too small for the size. */
    private fun clamp(argb: IntArray, width: Int, height: Int, rect: PxRect): PxRect? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        val left = max(0, rect.left)
        val top = max(0, rect.top)
        val right = min(width, rect.right)
        val bottom = min(height, rect.bottom)
        return if (right > left && bottom > top) PxRect(left, top, right, bottom) else null
    }
}
