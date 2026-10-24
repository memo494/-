package com.clu.quickanswer.ocr

import kotlin.math.max
import kotlin.math.min

/** Finds the large saturated green *play/submit* button next to the game's number pad. */
object NumericSubmit {
    fun detect(argb: IntArray, imageW: Int, imageH: Int, keys: Map<Char, PxRect>): PxRect? {
        if (keys.size != 10) return null
        val six = keys['6'] ?: return null
        val three = keys['3'] ?: return null
        val zero = keys['0'] ?: return null
        val keyW = six.width
        val keyH = six.height
        if (keyW < 8 || keyH < 8) return null
        val left = (six.right + keyW / 6).coerceIn(0, imageW)
        val right = min(imageW, zero.right + 2 * keyW)
        val top = max(0, six.top - keyH / 3)
        val bottom = min(imageH, three.bottom + keyH / 3)
        if (right - left < keyW || bottom - top < keyH) return null
        var minX = right
        var maxX = left
        var minY = bottom
        var maxY = top
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val c = argb[y * imageW + x]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            if (g > 105 && g > r * 1.55 && g > b * 1.3) {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                count++
            }
        }
        if (count < keyW * keyH || minX >= maxX || minY >= maxY) return null
        val box = PxRect(minX, minY, maxX + 1, maxY + 1)
        if (box.width < keyW * 1.1 || box.width > keyW * 3.0 ||
            box.height < keyH * 1.1 || box.height > keyH * 3.2 ||
            count < box.width * box.height * 0.40 ||
            box.centerX <= six.centerX || box.centerY < six.top || box.centerY > three.bottom
        ) return null
        return box
    }
}
