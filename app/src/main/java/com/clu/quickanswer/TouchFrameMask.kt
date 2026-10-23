package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import kotlin.math.ceil
import kotlin.math.floor

/** Exclude the same overlay pixels from captured and live fingerprints, including resize bleed. */
internal object TouchFrameMask {
    /** Rounded bubble bounds can graze a photo frame without covering its content. */
    fun obscuresPicture(area: PxRect, ignored: List<PxRect>): Boolean {
        val px = maxOf(1, area.width / 50)
        val py = maxOf(1, area.height / 50)
        val inner = PxRect(area.left + px, area.top + py, area.right - px, area.bottom - py)
        return ignored.any { it.left < inner.right && inner.left < it.right &&
            it.top < inner.bottom && inner.top < it.bottom }
    }

    fun apply(stamp: IntArray, area: PxRect, ignored: List<PxRect>): IntArray {
        require(stamp.size == TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT)
        require(area.width > 0 && area.height > 0)
        for (rect in ignored) {
            val left = maxOf(area.left, rect.left)
            val top = maxOf(area.top, rect.top)
            val right = minOf(area.right, rect.right)
            val bottom = minOf(area.bottom, rect.bottom)
            if (left >= right || top >= bottom) continue
            // One sample on each edge covers bilinear interpolation beside a painted mask.
            val x0 = (floor((left - area.left).toDouble() * TouchFrameGuard.WIDTH / area.width).toInt() - 1)
                .coerceIn(0, TouchFrameGuard.WIDTH)
            val y0 = (floor((top - area.top).toDouble() * TouchFrameGuard.HEIGHT / area.height).toInt() - 1)
                .coerceIn(0, TouchFrameGuard.HEIGHT)
            val x1 = (ceil((right - area.left).toDouble() * TouchFrameGuard.WIDTH / area.width).toInt() + 1)
                .coerceIn(0, TouchFrameGuard.WIDTH)
            val y1 = (ceil((bottom - area.top).toDouble() * TouchFrameGuard.HEIGHT / area.height).toInt() + 1)
                .coerceIn(0, TouchFrameGuard.HEIGHT)
            for (y in y0 until y1) for (x in x0 until x1) stamp[y * TouchFrameGuard.WIDTH + x] = -1
        }
        return stamp
    }
}
