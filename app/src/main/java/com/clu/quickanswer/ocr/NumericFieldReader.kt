package com.clu.quickanswer.ocr

/** Reads one complete field line; a hint/missing digit is checked separately against the expected value. */
class NumericFieldReader(private val recognizer: LineRecognizer) {
    fun read(argb: IntArray, width: Int, height: Int, field: PxRect): RecognizedLine {
        fun empty() = RecognizedLine("", 0.0, 0)
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height ||
            field.left < 0 || field.top < 0 || field.right > width || field.bottom > height ||
            field.width <= 0 || field.height <= 0) return empty()
        return try {
            val lines = TextLines.findDetailed(argb, width, height, field, bounded = true)
            if (lines.clipped || lines.lines.size != 1) return empty()
            recognizer.recognize(argb, width, height, lines.lines.single())
        } catch (_: Throwable) { empty() }
    }
}
