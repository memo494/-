package com.clu.quickanswer.ocr

import kotlin.math.abs

/**
 * The box of the button or field the user TAPPED, from the capture's pixels: the surface around
 * the tap, grown while its colour stays the same, so one tap gives the whole field or confirm
 * button instead of a box drawn by hand (hard on a phone, 26 Oct 2026).
 *
 * The surface colour is the median of a small window around the tap, so a tap that lands on the
 * field's hint digits or the button's word still takes the surface, and the region grows around
 * that writing. Fail closed: a region that keeps growing (the tap was on the background) or stays
 * tiny gives null, and the caller falls back to a plain square.
 *
 * Pure Kotlin, no android.*.
 */
object RegionSnap {

    /** Largest summed |ΔR|+|ΔG|+|ΔB| from the surface colour that is still the same surface. */
    const val COLOUR_TOLERANCE = 60

    /** A region over this share of the capture is the background, not a control. */
    private const val MAX_SHARE = 0.12f

    /** Half-side of the window whose median colour is the surface (px). */
    private const val SAMPLE_RADIUS = 10

    /** Smallest side (px) of a control found; anything smaller is noise. */
    private const val MIN_SIDE = 16

    /**
     * The control under the tap at ([x], [y]), or null. A tap on a button's word samples the word,
     * so the surface is also tried a little away from the tap on eight sides; the largest region
     * found that still contains the tap wins (measured on the confirm button: on its word nothing,
     * beside it the whole button).
     */
    fun around(argb: IntArray, width: Int, height: Int, x: Int, y: Int): PxRect? {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        if (x !in 0 until width || y !in 0 until height) return null
        val step = maxOf(20, (minOf(width, height) * 0.035f).toInt())
        var found: PxRect? = null
        for ((dx, dy) in listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
            val sx = x + dx * step; val sy = y + dy * step
            if (sx !in 0 until width || sy !in 0 until height) continue
            val r = from(argb, width, height, sx, sy) ?: continue
            if (x !in r.left until r.right || y !in r.top until r.bottom) continue
            if (found == null || r.width.toLong() * r.height > found.width.toLong() * found.height) found = r
        }
        return found
    }

    private fun from(argb: IntArray, width: Int, height: Int, x: Int, y: Int): PxRect? {
        val surface = medianColour(argb, width, height, x, y)
        fun same(c: Int): Boolean = abs(((c shr 16) and 255) - ((surface shr 16) and 255)) +
            abs(((c shr 8) and 255) - ((surface shr 8) and 255)) + abs((c and 255) - (surface and 255)) <= COLOUR_TOLERANCE
        // Seed on the surface pixel nearest the tap (the tap may be on writing).
        var seed = -1
        var best = Int.MAX_VALUE
        for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
            val sx = x + dx; val sy = y + dy
            if (sx !in 0 until width || sy !in 0 until height) continue
            val d = dx * dx + dy * dy
            if (d < best && same(argb[sy * width + sx])) { best = d; seed = sy * width + sx }
        }
        if (seed < 0) return null
        val limit = (width.toLong() * height * MAX_SHARE).toInt()
        val visited = BooleanArray(width * height)
        val queue = IntArray(limit + 1)
        var head = 0; var tail = 0
        queue[tail++] = seed; visited[seed] = true
        var left = width; var right = -1; var top = height; var bottom = -1
        while (head < tail) {
            val at = queue[head++]
            val px = at % width; val py = at / width
            if (px < left) left = px
            if (px > right) right = px
            if (py < top) top = py
            if (py > bottom) bottom = py
            for (k in 0 until 4) {
                val nx = px + (if (k == 0) -1 else if (k == 1) 1 else 0)
                val ny = py + (if (k == 2) -1 else if (k == 3) 1 else 0)
                if (nx !in 0 until width || ny !in 0 until height) continue
                val next = ny * width + nx
                if (visited[next] || !same(argb[next])) continue
                if (tail >= limit) return null
                visited[next] = true
                queue[tail++] = next
            }
        }
        if (right - left + 1 < MIN_SIDE || bottom - top + 1 < MIN_SIDE) return null
        return PxRect(left, top, right + 1, bottom + 1)
    }

    private fun medianColour(argb: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
        val r = ArrayList<Int>(); val g = ArrayList<Int>(); val b = ArrayList<Int>()
        for (sy in maxOf(0, y - SAMPLE_RADIUS)..minOf(height - 1, y + SAMPLE_RADIUS)) {
            for (sx in maxOf(0, x - SAMPLE_RADIUS)..minOf(width - 1, x + SAMPLE_RADIUS)) {
                val c = argb[sy * width + sx]
                r.add((c shr 16) and 255); g.add((c shr 8) and 255); b.add(c and 255)
            }
        }
        r.sort(); g.sort(); b.sort()
        val m = r.size / 2
        return (0xFF shl 24) or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }
}
