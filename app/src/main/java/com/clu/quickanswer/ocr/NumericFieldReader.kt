package com.clu.quickanswer.ocr

/** Reads one complete field line; a hint/missing digit is checked separately against the expected value. */
class NumericFieldReader(private val recognizer: LineRecognizer) {
    enum class Ink { AUTO, GOLD, LIGHT }

    fun read(argb: IntArray, width: Int, height: Int, field: PxRect, ink: Ink = Ink.AUTO): RecognizedLine {
        fun empty() = RecognizedLine("", 0.0, 0)
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height ||
            field.left < 0 || field.top < 0 || field.right > width || field.bottom > height ||
            field.width <= 0 || field.height <= 0) return empty()
        return try {
            if (ink == Ink.GOLD) return readGold(argb, width, field)
            if (ink == Ink.LIGHT) {
                val sample = NumericFieldInk.inspect(argb, width, height, field, ink)
                if (sample.state != NumericFieldInk.State.FILLED) return empty()
                return recognizer.recognize(sample.pixels, sample.width, sample.height,
                    PxRect(0, 0, sample.width, sample.height))
            }
            val lines = TextLines.findDetailed(argb, width, height, field, bounded = true)
            if (lines.clipped || lines.lines.size != 1) return empty()
            recognizer.recognize(argb, width, height, lines.lines.single())
        } catch (_: Throwable) { empty() }
    }

    /** Gray zero placeholders and the input's bevel are not typed digits. Never strip OCR zeroes. */
    private fun readGold(argb: IntArray, width: Int, field: PxRect): RecognizedLine {
        fun empty() = RecognizedLine("", 0.0, 0)
        val mask = BooleanArray(field.width * field.height)
        val rows = IntArray(field.height)
        var left = field.width; var right = -1; var top = field.height; var bottom = -1
        var count = 0
        for (y in 0 until field.height) for (x in 0 until field.width) {
            if (!isGold(argb[(field.top + y) * width + field.left + x])) continue
            mask[y * field.width + x] = true
            rows[y]++
            count++
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        val inkHeight = bottom - top + 1
        if (count < 8 || inkHeight < maxOf(4, field.height / 5) ||
            left <= 0 || right >= field.width - 1 || top <= 0 || bottom >= field.height - 1 ||
            right - left + 1 > inkHeight * 5) return empty()
        // Digits have a continuous vertical extent; two rows of unrelated gold UI are not a field.
        if ((top..bottom).any { rows[it] == 0 }) return empty()
        val pad = maxOf(2, inkHeight / 4)
        val cleanWidth = right - left + 1 + pad * 2
        val cleanHeight = inkHeight + 4
        val clean = IntArray(cleanWidth * cleanHeight) { -1 }
        for (y in top..bottom) for (x in left..right) if (mask[y * field.width + x]) {
            clean[(y - top + 2) * cleanWidth + x - left + pad] = 0xff101010.toInt()
        }
        // The colour adapter already found the complete ink bounds. A generic line cutter can
        // remove a single bold '1' as a vertical border, so read this measured crop directly.
        return recognizer.recognize(clean, cleanWidth, cleanHeight, PxRect(0, 0, cleanWidth, cleanHeight))
    }

    companion object {
        fun isGold(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r >= 170 && g >= 90 && r - g >= 20 && g - b >= 30
        }
    }
}
