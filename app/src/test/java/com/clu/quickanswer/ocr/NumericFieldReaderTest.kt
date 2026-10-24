package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class NumericFieldReaderTest {
    private val width = 240
    private val height = 100
    private val field = PxRect(10, 10, 230, 90)

    private fun logits(number: String): CtcLogits {
        val steps = number.length * 2
        val values = FloatArray(steps * 12)
        number.forEachIndexed { i, c ->
            values[i * 2 * 12 + (c - '0') + 1] = 1f
            values[(i * 2 + 1) * 12] = 1f
        }
        return CtcLogits(steps, 12, values)
    }

    private fun image(background: Int, ink: Int, twoLines: Boolean = false): IntArray {
        val pixels = IntArray(width * height) { background }
        val rows = if (twoLines) listOf(25, 60) else listOf(35)
        for (top in rows) for (y in top until top + 15) for (x in 65 until 175) {
            if (x % 12 < 3 || y >= top + 12) pixels[y * width + x] = ink
        }
        return pixels
    }

    @Test fun aSingleFieldLineUsesEitherPolarityAndPreservesRepeatedZeros() {
        for ((background, ink) in listOf(0xfffafafa.toInt() to 0xff303030.toInt(),
                0xff414141.toInt() to 0xfff5bc28.toInt())) {
            var calls = 0
            val reader = NumericFieldReader(LineRecognizer(CtcModel { input, w ->
                calls++
                assertEquals(3 * LineRecognizer.HEIGHT * w, input.size)
                logits("0011")
            }, ('0'..'9').map { it.toString() }))
            val read = reader.read(image(background, ink), width, height, field)
            assertEquals("0011", read.text)
            assertTrue(read.confidence >= 0.95f)
            assertEquals(1, calls)
        }
    }

    @Test fun blankMultilineAndOutOfBoundsFieldsNeverReachTheModel() {
        var calls = 0
        val reader = NumericFieldReader(LineRecognizer(CtcModel { _, _ -> calls++; error("must not read") }, listOf("0")))
        assertEquals("", reader.read(IntArray(width * height) { 0xffeeeeee.toInt() }, width, height, field).text)
        assertEquals("", reader.read(image(0xffeeeeee.toInt(), 0xff303030.toInt(), true), width, height, field).text)
        assertEquals("", reader.read(image(0xffeeeeee.toInt(), 0xff303030.toInt()), width, height, field.copy(left = -1)).text)
        assertEquals(0, calls)
    }

    @Test fun nativeReaderErrorsDeclineInsteadOfEscapingTheInputCheck() {
        val reader = NumericFieldReader(LineRecognizer(CtcModel { _, _ -> throw OutOfMemoryError("simulated") }, listOf("0")))
        assertEquals("", reader.read(image(0xffeeeeee.toInt(), 0xff303030.toInt()), width, height, field).text)
    }
}
