package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

/** Synthetic glyphs only; exercises the shipped native model, not a predetermined OCR reply. */
class GoldNumericReadbackTest {
    private val w = 260
    private val h = 80
    private val field = PxRect(5, 5, 255, 75)

    // Android's compile stubs have no AWT; the test JVM provides these public desktop classes.
    private fun image(number: String): IntArray {
        val imageClass = Class.forName("java.awt.image.BufferedImage")
        val graphicsClass = Class.forName("java.awt.Graphics")
        val colorClass = Class.forName("java.awt.Color")
        val fontClass = Class.forName("java.awt.Font")
        val image = imageClass.getConstructor(Int::class.java, Int::class.java, Int::class.java).newInstance(w, h, 1)
        val g = imageClass.getMethod("getGraphics").invoke(image)
        fun color(rgb: Int) = graphicsClass.getMethod("setColor", colorClass).invoke(g,
            colorClass.getConstructor(Int::class.java).newInstance(rgb))
        color(0x414141)
        graphicsClass.getMethod("fillRect", Int::class.java, Int::class.java, Int::class.java, Int::class.java)
            .invoke(g, 0, 0, w, h)
        graphicsClass.getMethod("setFont", fontClass).invoke(g,
            fontClass.getConstructor(String::class.java, Int::class.java, Int::class.java).newInstance("SansSerif", 1, 42))
        val draw = graphicsClass.getMethod("drawString", String::class.java, Int::class.java, Int::class.java)
        color(0x303030)
        for (i in 0..3) draw.invoke(g, "0", 70 + i * 28, 57)
        color(0xf5bc28)
        number.forEachIndexed { i, c -> draw.invoke(g, c.toString(), 70 + (4 - number.length + i) * 28, 57) }
        graphicsClass.getMethod("dispose").invoke(g)
        return imageClass.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java).invoke(image, 0, 0, w, h, null, 0, w) as IntArray
    }

    @Test fun theRealModelReadsOnlyGoldDigitsWhileGrayZeroHintsRemainVisible() {
        val reader = NumericFieldReader(OcrFixtures.recognizer)
        for (number in listOf("1", "4", "71", "0011", "1950", "88", "0000")) {
            val read = reader.read(image(number), w, h, field, NumericFieldReader.Ink.GOLD)
            println("[gold field] expected=$number observed=${read.text} confidence=${read.confidence}")
            assertEquals(number, read.text)
            assertTrue("$number confidence=${read.confidence}", read.confidence >= 0.75f)
        }
    }

    @Test fun anUnchangedGrayHintCannotReachTheRecognizer() {
        var calls = 0
        val reader = NumericFieldReader(LineRecognizer(CtcModel { _, _ -> calls++; error("no entered ink") }, listOf("0")))
        assertEquals("", reader.read(image(""), w, h, field, NumericFieldReader.Ink.GOLD).text)
        assertEquals(0, calls)
    }

    @Test fun aGrayFieldBevelDoesNotBecomeAnotherTextLineOrTrimTheEnteredDigit() {
        val p = image("4")
        for (y in field.top until field.bottom) for (x in field.left until field.right) {
            if (y < field.top + 7 || y >= field.bottom - 7 || x < field.left + 5 || x >= field.right - 5)
                p[y * w + x] = 0xff999999.toInt()
        }
        val read = NumericFieldReader(OcrFixtures.recognizer).read(p, w, h, field, NumericFieldReader.Ink.GOLD)
        assertEquals("4", read.text)
        assertTrue(read.confidence >= 0.75f)
    }

    @Test fun goldRowsAndClippedInkDoNotReachTheRecognizer() {
        var calls = 0
        val reader = NumericFieldReader(LineRecognizer(CtcModel { _, _ -> calls++; error("incomplete field") }, listOf("0")))
        val p = IntArray(w * h) { 0xff414141.toInt() }
        for (top in listOf(15, 48)) for (y in top until top + 18) for (x in 90..110)
            p[y * w + x] = 0xfff5bc28.toInt()
        assertEquals("", reader.read(p, w, h, field, NumericFieldReader.Ink.GOLD).text)
        val clipped = image("4")
        assertEquals("", reader.read(clipped, w, h, field.copy(bottom = 45), NumericFieldReader.Ink.GOLD).text)
        assertEquals(0, calls)
    }
}
