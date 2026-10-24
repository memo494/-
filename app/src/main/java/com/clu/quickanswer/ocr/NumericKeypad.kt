package com.clu.quickanswer.ocr

import kotlin.math.abs

/**
 * Recognises the *game's* on-screen number pad, not a generic Android keyboard.
 * Its rows are 7 8 9 0 [erase], 4 5 6, 1 2 3 (see the s1.jpg fixture).
 * No tap coordinates are inferred if the detected white keys do not form this grid.
 */
object NumericKeypad {
    fun detect(field: PxRect, candidates: List<PxRect>): Map<Char, PxRect> {
        if (field.width <= 0 || field.height <= 0) return emptyMap()
        val keys = candidates.filter {
            it.width > 0 && it.height > 0 && it.top >= field.bottom &&
                it.centerX >= field.left - field.width * 0.55 &&
                it.centerX <= field.right + field.width * 0.55 &&
                it.top <= field.bottom + field.height * 6 &&
                it.width.toDouble() / it.height in 0.65..1.6
        }.sortedWith(compareBy<PxRect> { it.centerY }.thenBy { it.centerX })
        if (keys.size !in 10..15) return emptyMap()
        val medianH = keys.map { it.height }.sorted()[keys.size / 2]
        val medianW = keys.map { it.width }.sorted()[keys.size / 2]
        if (medianH <= 0 || medianW <= 0) return emptyMap()
        val sized = keys.filter {
            abs(it.width - medianW) <= medianW * 0.3 &&
                abs(it.height - medianH) <= medianH * 0.3
        }
        val rows = mutableListOf<MutableList<PxRect>>()
        for (key in sized) {
            val row = rows.firstOrNull { abs(it.first().centerY - key.centerY) <= medianH * 0.35 }
            if (row != null) row.add(key) else rows.add(mutableListOf(key))
        }
        if (rows.size != 3 || rows[0].size !in 4..5 || rows[1].size != 3 || rows[2].size != 3) {
            return emptyMap()
        }
        val top = rows[0].sortedBy { it.centerX }
        val middle = rows[1].sortedBy { it.centerX }
        val bottom = rows[2].sortedBy { it.centerX }
        val first = top.take(3)
        val pitch = (first[2].centerX - first[0].centerX) / 2f
        if (pitch < medianW * 0.8 || pitch > medianW * 2.0) return emptyMap()
        if (abs((top[3].centerX - first[2].centerX) - pitch) > pitch * 0.4) return emptyMap()
        for (row in listOf(first, middle, bottom)) {
            for (i in 0..2) if (abs(row[i].centerX - first[i].centerX) > pitch * 0.3) return emptyMap()
        }
        val rowPitch = middle[0].centerY - first[0].centerY
        if (rowPitch < medianH * 0.75 || rowPitch > medianH * 2.1 ||
            abs((bottom[0].centerY - middle[0].centerY) - rowPitch) > medianH * 0.45
        ) return emptyMap()
        return mapOf(
            '7' to top[0], '8' to top[1], '9' to top[2], '0' to top[3],
            '4' to middle[0], '5' to middle[1], '6' to middle[2],
            '1' to bottom[0], '2' to bottom[1], '3' to bottom[2],
        )
    }
}
