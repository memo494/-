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
            if (ink == Ink.GOLD || ink == Ink.LIGHT) {
                val sample = NumericFieldInk.inspect(argb, width, height, field, ink)
                if (sample.state != NumericFieldInk.State.FILLED) return empty()
                // Both entry readiness and readback must exclude the same verified field frame.
                // Read the complete measured ink directly: a line cutter can remove a bold '1'.
                return digitsOnly(recognizer.recognize(sample.pixels, sample.width, sample.height,
                    PxRect(0, 0, sample.width, sample.height)))
            }
            val lines = TextLines.findDetailed(argb, width, height, field, bounded = true)
            if (lines.clipped || lines.lines.size != 1) return empty()
            digitsOnly(recognizer.recognize(argb, width, height, lines.lines.single()))
        } catch (_: Throwable) { empty() }
    }

    /**
     * A number field holds digits only; the recogniser sometimes puts a gap between widely spaced
     * digits ("1 000" for 1000, measured on another CPU, 27 Oct 2026). The gap is dropped, nothing
     * else: any other character stays and makes the reading not match.
     */
    private fun digitsOnly(line: RecognizedLine): RecognizedLine =
        if (line.text.none(Char::isWhitespace)) line
        else RecognizedLine(line.text.filterNot(Char::isWhitespace), line.probSum, line.charCount)

    companion object {
        fun isGold(c: Int): Boolean {
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            return r >= 170 && g >= 90 && r - g >= 20 && g - b >= 30
        }
    }
}
