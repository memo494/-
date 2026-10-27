package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

/** Synthetic reproduction of the reported 1280x576 capsule; no user image is stored. */
class RoundedGoldFieldRegressionTest {
    private val width = 1280
    private val height = 576
    private val capsule = PxRect(440, 299, 679, 360)
    private val field = PxRect(444, 300, 677, 356)
    private val parchment = 0xffedc783.toInt()
    private val dark = 0xff414141.toInt()
    private val gold = 0xfff5bc28.toInt()

    private fun scene(number: String): IntArray {
        val ic = Class.forName("java.awt.image.BufferedImage")
        val gc = Class.forName("java.awt.Graphics")
        val cc = Class.forName("java.awt.Color")
        val fc = Class.forName("java.awt.Font")
        val image = ic.getConstructor(Int::class.java, Int::class.java, Int::class.java)
            .newInstance(width, height, 1)
        val g = ic.getMethod("getGraphics").invoke(image)
        fun color(rgb: Int) = gc.getMethod("setColor", cc).invoke(g, cc.getConstructor(Int::class.java).newInstance(rgb))
        color(parchment)
        gc.getMethod("fillRect", Int::class.java, Int::class.java, Int::class.java, Int::class.java)
            .invoke(g, 0, 0, width, height)
        color(dark)
        gc.getMethod("fillRoundRect", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            Int::class.java, Int::class.java).invoke(g, capsule.left, capsule.top, capsule.width,
                capsule.height, capsule.height, capsule.height)
        gc.getMethod("setFont", fc).invoke(g,
            fc.getConstructor(String::class.java, Int::class.java, Int::class.java).newInstance("SansSerif", 1, 42))
        val draw = gc.getMethod("drawString", String::class.java, Int::class.java, Int::class.java)
        val start = field.centerX.toInt() - 56
        val baseline = field.centerY.toInt() + 15
        color(0x303030)
        for (i in 0..3) draw.invoke(g, "0", start + i * 28, baseline)
        color(gold)
        number.forEachIndexed { i, c -> draw.invoke(g, c.toString(), start + (4 - number.length + i) * 28, baseline) }
        gc.getMethod("dispose").invoke(g)
        return ic.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(image, 0, 0, width, height, null, 0, width) as IntArray
    }

    private fun state(pixels: IntArray, box: PxRect = field) =
        NumericFieldInk.inspect(pixels, width, height, box, NumericFieldReader.Ink.GOLD).state

    private fun noRead(pixels: IntArray, box: PxRect = field) {
        var calls = 0
        val reader = NumericFieldReader(LineRecognizer(CtcModel { _, _ ->
            calls++; error("incomplete or empty field must not run inference")
        }, listOf("0")))
        assertEquals("", reader.read(pixels, width, height, box, NumericFieldReader.Ink.GOLD).text)
        assertEquals(0, calls)
    }

    @Test fun parchmentCornersAndGrayZeroHintsDoNotMakeTheRoundedFieldOccupied() {
        val pixels = scene("")
        assertTrue("the rectangular crop really includes gold-coloured parchment",
            NumericFieldReader.isGold(pixels[field.top * width + field.left]))
        assertEquals(NumericFieldInk.State.EMPTY, state(pixels))
        noRead(pixels)
    }

    @Test fun theRealModelReadsEntered1000And0000WithoutTheirRoundedParchmentCorners() {
        val reader = NumericFieldReader(OcrFixtures.recognizer)
        for (number in listOf("1000", "0000", "0011")) {
            val pixels = scene(number)
            assertEquals("occupied $number", NumericFieldInk.State.FILLED, state(pixels))
            val result = reader.read(pixels, width, height, field, NumericFieldReader.Ink.GOLD)
            println("[rounded gold field] expected=$number observed=${result.text} confidence=${result.confidence}")
            assertEquals(number, result.text)
            assertTrue("$number confidence=${result.confidence}", result.confidence >= 0.75f)
        }
    }

    @Test fun productionSnapshotRetainsContextForEmptyCheckAndRealReadback() {
        val context = PxRect(field.left - 2, field.top - 2, field.right + 2, field.bottom + 2)
        val local = PxRect(2, 2, 2 + field.width, 2 + field.height)
        val reader = NumericFieldReader(OcrFixtures.recognizer)
        for (number in listOf("", "1000", "0000")) {
            val source = scene(number)
            val snapshot = IntArray(context.width * context.height) { i ->
                source[(context.top + i / context.width) * width + context.left + i % context.width]
            }
            val sample = NumericFieldInk.inspect(snapshot, context.width, context.height, local, NumericFieldReader.Ink.GOLD)
            assertEquals(if (number.isEmpty()) NumericFieldInk.State.EMPTY else NumericFieldInk.State.FILLED, sample.state)
            val read = reader.read(snapshot, context.width, context.height, local, NumericFieldReader.Ink.GOLD)
            assertEquals(number, read.text)
            if (number.isNotEmpty()) assertTrue(read.confidence >= 0.75f)
        }
    }

    @Test fun theReportedCapsuleRemainsEmptyWhenTheCaptureIsScaled() {
        val pixels = scene("")
        for (scale in listOf(0.75, 1.5)) {
            val w = (width * scale).toInt(); val h = (height * scale).toInt()
            val resized = IntArray(w * h) { i -> pixels[(i / w / scale).toInt() * width + (i % w / scale).toInt()] }
            fun coordinate(value: Int) = (value * scale).toInt()
            val box = PxRect(coordinate(field.left), coordinate(field.top), coordinate(field.right), coordinate(field.bottom))
            assertEquals("scale=$scale", NumericFieldInk.State.EMPTY,
                NumericFieldInk.inspect(resized, w, h, box, NumericFieldReader.Ink.GOLD).state)
        }
    }

    @Test fun clippedEnteredDigitsAreUnknownAndCannotReachTheModel() {
        val pixels = scene("1000")
        for (box in listOf(field.copy(right = field.centerX.toInt() + 35), field.copy(top = 330))) {
            assertEquals(NumericFieldInk.State.UNKNOWN, state(pixels, box))
            noRead(pixels, box)
        }
    }

    @Test fun goldJoinedToACornerAndExtendingIntoTheFieldIsNeverDiscarded() {
        val pixels = scene("")
        for (y in field.top until field.top + 36) for (x in field.left + 2 until field.left + 7)
            pixels[y * width + x] = gold
        assertEquals(NumericFieldInk.State.UNKNOWN, state(pixels))
        noRead(pixels)
    }

    @Test fun aGoldCornerMustContinueIntoTheParchmentOutsideBothCropEdges() {
        val pixels = scene("")
        for (x in field.left until field.right) pixels[(field.top - 1) * width + x] = dark
        assertEquals(NumericFieldInk.State.UNKNOWN, state(pixels))
        noRead(pixels)
    }
}
