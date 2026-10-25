package com.clu.quickanswer.ocr

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a game's number pad from the digits printed on its keys, read by on-device OCR
 * ([LatinOcr.glyphs]), whatever the pad's order or skin: 1-2-3 on top or 7-8-9, a row of ten, a
 * zero on the side. Used by "اكتشاف تلقائي" on the calibration screen, so the user only checks the
 * ten boxes instead of drawing them.
 *
 * A screen has other digits (a score "0 / 4", a counter, a timer), so a pad is a GROUP: digits of
 * about the same size, each close to another, holding every digit 0..9 exactly once. Fail closed:
 * no such group, two of them, or a digit twice in the group, and nothing is proposed.
 *
 * Pure Kotlin, no android.*.
 */
object KeypadFinder {

    /** One word OCR read and where, in capture pixels. */
    data class Glyph(val text: String, val box: PxRect)

    /**
     * Two digits are neighbours when their centres are within this many glyph heights. Keys print
     * digits at 40-50% of their height and sit about 1.1 keys apart, so neighbouring keys are 2.2 to
     * 2.8 glyph heights apart; a field hint such as "0000" next to the pad is further.
     */
    private const val NEIGHBOUR_HEIGHTS = 3.2

    /** Keys of one pad print their digits at about the same size: at most this ratio of heights. */
    private const val MAX_HEIGHT_RATIO = 1.5

    /** The box proposed for a key: a square this many glyph heights wide, centred on the digit. */
    private const val KEY_SIDE_HEIGHTS = 1.6f

    /** Smallest digit height (px) taken for a key label rather than OCR noise. */
    private const val MIN_GLYPH = 8

    /** The ten keys, index = digit, or null when no single pad is on the capture ([width] x [height]). */
    fun find(glyphs: List<Glyph>, width: Int, height: Int): List<PxRect>? {
        if (width <= 0 || height <= 0) return null
        val digits = glyphs.flatMap { split(it) }.filter {
            it.second.height >= MIN_GLYPH && it.second.width > 0 &&
                it.second.left >= 0 && it.second.top >= 0 && it.second.right <= width && it.second.bottom <= height
        }
        if (digits.size < 10) return null
        // Connected groups of neighbouring, similar-sized digits.
        val group = IntArray(digits.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (group[r] != r) r = group[r]
            return r
        }
        for (i in digits.indices) for (j in i + 1 until digits.size) {
            val a = digits[i].second
            val b = digits[j].second
            val ratio = max(a.height, b.height).toDouble() / min(a.height, b.height)
            val near = hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble()) <=
                NEIGHBOUR_HEIGHTS * max(a.height, b.height)
            if (ratio <= MAX_HEIGHT_RATIO && near) group[root(i)] = root(j)
        }
        val pads = digits.indices.groupBy { root(it) }.values
            .map { members -> members.map { digits[it] } }
            .filter { members -> members.size == 10 && members.map { it.first }.toSet().size == 10 }
        val pad = pads.singleOrNull() ?: return null
        val keys = pad.sortedBy { it.first }.map { (_, box) -> square(box, width, height) }
        // Two proposed keys on top of each other cannot both be tapped right.
        for (i in keys.indices) for (j in i + 1 until keys.size) if (overlap(keys[i], keys[j])) return null
        return keys
    }

    /**
     * The digits of one glyph with their boxes: a single digit as it is; a run of 2..4 digits read
     * as one word ("123" for three keys side by side) split into equal parts. Arabic-Indic and
     * Persian digits count as their ASCII value. Anything else gives nothing.
     */
    private fun split(g: Glyph): List<Pair<Char, PxRect>> {
        val text = g.text.trim().map { ascii(it) ?: return emptyList() }
        if (text.size !in 1..4) return emptyList()
        if (text.size == 1) return listOf(text[0] to g.box)
        val part = g.box.width.toFloat() / text.size
        return text.mapIndexed { i, c ->
            c to PxRect((g.box.left + part * i).roundToInt(), g.box.top, (g.box.left + part * (i + 1)).roundToInt(), g.box.bottom)
        }
    }

    private fun ascii(c: Char): Char? = when (c) {
        in '0'..'9' -> c
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        else -> null
    }

    private fun square(box: PxRect, width: Int, height: Int): PxRect {
        // By height only: a digit is taller than wide, and a piece of a split run has no width of its own.
        val side = box.height * KEY_SIDE_HEIGHTS
        val half = side / 2f
        return PxRect(
            (box.centerX - half).roundToInt().coerceIn(0, width - 1),
            (box.centerY - half).roundToInt().coerceIn(0, height - 1),
            (box.centerX + half).roundToInt().coerceIn(1, width),
            (box.centerY + half).roundToInt().coerceIn(1, height),
        )
    }

    private fun overlap(a: PxRect, b: PxRect): Boolean {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return false
        val smaller = min(a.width * a.height, b.width * b.height).toFloat()
        return w * h > smaller * TemplateLearner.MAX_OVERLAP
    }
}
