package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

/** White input, gray frame, and pale outlined digits as in the reported numeric screen. */
class LightNumericReadbackTest {
    private val w = 280; private val h = 90
    private val field = PxRect(5, 5, 275, 85)

    private fun image(number: String): IntArray {
        val ic = Class.forName("java.awt.image.BufferedImage")
        val gc = Class.forName("java.awt.Graphics")
        val cc = Class.forName("java.awt.Color")
        val fc = Class.forName("java.awt.Font")
        val image = ic.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(w, h, 1)
        val g = ic.getMethod("getGraphics").invoke(image)
        fun color(rgb: Int) = gc.getMethod("setColor", cc).invoke(g, cc.getConstructor(Int::class.java).newInstance(rgb))
        color(0xffffff)
        gc.getMethod("fillRect", Int::class.java, Int::class.java, Int::class.java, Int::class.java).invoke(g, 0, 0, w, h)
        gc.getMethod("setFont", fc).invoke(g,
            fc.getConstructor(String::class.java, Int::class.java, Int::class.java).newInstance("Serif", 1, 38))
        color(0)
        val draw = gc.getMethod("drawString", String::class.java, Int::class.java, Int::class.java)
        number.forEachIndexed { i, c -> draw.invoke(g, c.toString(), 245 - (number.length - i - 1) * 24, 60) }
        gc.getMethod("dispose").invoke(g)
        val glyph = ic.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java).invoke(image, 0, 0, w, h, null, 0, w) as IntArray
        val p = IntArray(w * h) { -1 }
        for (y in field.top until field.bottom) for (x in field.left until field.right) {
            if (x < field.left + 3 || x >= field.right - 3 || y < field.top + 3 || y >= field.bottom - 3)
                p[y * w + x] = 0xff969fa4.toInt()
        }
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if ((glyph[y * w + x] and 255) < 128) p[y * w + x] = 0xffdce4d4.toInt()
            else if ((-1..1).any { dy -> (-1..1).any { dx -> (glyph[(y + dy) * w + x + dx] and 255) < 128 } })
                p[y * w + x] = 0xff718590.toInt()
        }
        return p
    }

    @Test fun theNativeModelReadsOutlinedDigitsIncludingTheReported1355() {
        val reader = NumericFieldReader(OcrFixtures.recognizer)
        for (number in listOf("4", "71", "1355", "0011", "0000", "1950")) {
            val p = image(number)
            assertEquals(NumericFieldInk.State.FILLED, NumericFieldInk.inspect(p, w, h, field, NumericFieldReader.Ink.LIGHT).state)
            val read = reader.read(p, w, h, field, NumericFieldReader.Ink.LIGHT)
            println("[light field] expected=$number observed=${read.text} confidence=${read.confidence}")
            assertEquals(number, read.text)
            assertTrue(read.confidence >= 0.75f)
        }
    }

    @Test fun anEmptyFramedFieldIsProvenEmptyWithoutCallingTheModel() {
        val p = image("")
        assertEquals(NumericFieldInk.State.EMPTY, NumericFieldInk.inspect(p, w, h, field, NumericFieldReader.Ink.LIGHT).state)
        val reader = NumericFieldReader(LineRecognizer(CtcModel { _, _ -> error("blank field must not run inference") }, listOf("0")))
        assertEquals("", reader.read(p, w, h, field, NumericFieldReader.Ink.LIGHT).text)
        // The frame can sit a pixel inside a learned box, rather than exactly at its edge.
        assertEquals(NumericFieldInk.State.EMPTY, NumericFieldInk.inspect(p, w, h,
            PxRect(field.left - 1, field.top - 1, field.right + 1, field.bottom + 1), NumericFieldReader.Ink.LIGHT).state)
    }

    @Test fun grayZeroHintsAreEmptyButGoldZeroesAndOtherTypedDigitsAreNot() {
        val p = IntArray(w * h) { 0xff414141.toInt() }
        for (y in 25..55) for (x in 80..200) if (x % 12 < 3) p[y * w + x] = 0xff292929.toInt()
        assertEquals(NumericFieldInk.State.EMPTY, NumericFieldInk.inspect(p, w, h, field, NumericFieldReader.Ink.GOLD).state)
        for (y in 25..55) for (x in 175..190) p[y * w + x] = 0xfff5bc28.toInt()
        assertEquals(NumericFieldInk.State.FILLED, NumericFieldInk.inspect(p, w, h, field, NumericFieldReader.Ink.GOLD).state)
        assertEquals(NumericFieldInk.State.UNKNOWN, NumericFieldInk.inspect(p, w, h,
            field.copy(right = 185), NumericFieldReader.Ink.GOLD).state)
    }

    @Test fun wrongBackgroundAndClippedCharactersCannotAuthorizeDirectEntry() {
        val p = image("1355")
        assertEquals(NumericFieldInk.State.UNKNOWN, NumericFieldInk.inspect(p, w, h,
            field.copy(right = 258), NumericFieldReader.Ink.LIGHT).state)
        assertEquals(NumericFieldInk.State.UNKNOWN, NumericFieldInk.inspect(IntArray(w * h) { 0xff444444.toInt() },
            w, h, field, NumericFieldReader.Ink.LIGHT).state)
    }
}
