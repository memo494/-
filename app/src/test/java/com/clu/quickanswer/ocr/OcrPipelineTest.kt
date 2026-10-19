package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole Kotlin pipeline (layout -> text lines -> ONNX recogniser) on three real screenshots of
 * the game: S1 numeric question (portrait photo), S2 multiple choice (portrait photo), S3 multiple
 * choice (landscape capture with an old overlay over options 2-3). Ground truth is what is
 * actually rendered on screen (S2 option 3 is cut off on screen: "سورة ا خلا").
 */
class OcrPipelineTest {

    private companion object {
        const val MIN_ACCURACY = 0.95

        const val S1_Q = "كم عدد الأشخاص الذين يشاركون في سباق الماراثون الأولمبي؟"
        const val S2_Q = "ما هي السورة التي بفضلها اسلم الصحابي الجليل عمر بن الخطّاب؟"
        val S2_OPTIONS = listOf("سورة البقرة", "سورة طه", "سورة ا خلا", "سورة النور")
        const val S3_Q = "ما هي العلامة التي جعلها الله تعالى لموسى وهارون عليهم السلام ليلتقيا الخضر عليه السلام ؟"
        val S3_OPTIONS = listOf("ظهور طائر غريب في السماء", "انشقاق البحر", "عودة الحوت للبحر مفعما بالحياة", "انكسار عصا موسى عليه السلام")
    }

    @Before
    fun warmUp() {
        // Build the session and let the runtime warm up before anything is timed.
        repeat(3) { OcrFixtures.recognizer.recognize(IntArray(400 * 60) { 0xFFFFFFFF.toInt() }, 400, 60, PxRect(0, 0, 400, 60)) }
    }

    private class Run(val reading: ScreenReader.Reading, val layoutMs: Double, val recMs: Double)

    /** Best of [repeats] timed runs of the full pipeline. */
    private fun timed(img: TestImage, repeats: Int = 5): Run {
        var best: Run? = null
        repeat(repeats) {
            val r = OcrFixtures.reader.analyze(img.argb, img.width, img.height)
            if (best == null || r.layoutMs + r.recognizeMs < best!!.layoutMs + best!!.recMs) {
                best = Run(r, r.layoutMs, r.recognizeMs)
            }
        }
        return best!!
    }

    private fun log(label: String, img: TestImage, run: Run, truthQ: String, truthOptions: List<String>) {
        val q = run.reading.question
        val tag = "[OCR ${OcrFixtures.modelLabel} $label ${img.width}x${img.height}]"
        println("$tag layout %.0f ms + recognise %.0f ms = %.0f ms, confidence %s".format(
            run.layoutMs, run.recMs, run.layoutMs + run.recMs, q?.meanConfidence?.let { "%.3f".format(it) } ?: "n/a (${run.reading.failure})"))
        if (q == null) return
        println("$tag   numeric=${q.numeric}")
        println("$tag   Q: ${q.questionText}   acc=%.3f".format(ArabicText.charAccuracy(q.questionText, truthQ)))
        for ((i, o) in q.options.withIndex()) {
            println("$tag   ${i + 1}) $o   acc=%.3f   rect=${q.optionRects[i]}".format(ArabicText.charAccuracy(o, truthOptions.getOrElse(i) { "" })))
        }
    }

    private fun assertRectsOrderedAndInside(q: Question, img: TestImage) {
        for (r in q.optionRects) {
            assertTrue("$r not inside ${img.width}x${img.height}", r.left >= 0 && r.top >= 0 && r.right <= img.width && r.bottom <= img.height)
            assertTrue("$r is empty", r.width > 0 && r.height > 0)
        }
        for ((a, b) in q.optionRects.zipWithNext()) {
            assertTrue("options not ordered top to bottom: $a then $b", a.top < b.top && a.bottom <= b.top)
        }
    }

    private fun checkMultipleChoice(label: String, truthQ: String, truthOptions: List<String>) {
        val img = OcrFixtures.image(label.lowercase())
        val run = timed(img)
        log(label, img, run, truthQ, truthOptions)
        val q = run.reading.question
        assertNotNull("$label not read: ${run.reading.failure}", q)
        q!!
        assertFalse("$label wrongly numeric", q.numeric)
        assertEquals("$label options", truthOptions.size, q.options.size)
        assertEquals(q.options.size, q.optionRects.size)
        assertTrue("$label question accuracy", ArabicText.charAccuracy(q.questionText, truthQ) >= MIN_ACCURACY)
        for ((i, o) in q.options.withIndex()) {
            assertTrue("$label option ${i + 1} '$o' accuracy", ArabicText.charAccuracy(o, truthOptions[i]) >= MIN_ACCURACY)
        }
        assertRectsOrderedAndInside(q, img)
        assertTrue("$label confidence ${q.meanConfidence}", q.meanConfidence >= ScreenReader.DEFAULT_MIN_CONFIDENCE)
        assertTrue(q.meanConfidence <= 1f)
    }

    @Test
    fun s2_portraitMultipleChoice_isReadWithFourOptions() = checkMultipleChoice("S2", S2_Q, S2_OPTIONS)

    @Test
    fun s3_landscapeMultipleChoice_isReadWithFourOptions() = checkMultipleChoice("S3", S3_Q, S3_OPTIONS)

    @Test
    fun s1_numericQuestion_isDetectedAndItsQuestionRead() {
        val img = OcrFixtures.image("s1")
        val run = timed(img)
        log("S1", img, run, S1_Q, emptyList())
        val q = run.reading.question
        assertNotNull("S1 not read: ${run.reading.failure}", q)
        q!!
        assertTrue("S1 should be numeric", q.numeric)
        assertTrue(q.options.isEmpty())
        assertTrue(q.optionRects.isEmpty())
        assertTrue("S1 question accuracy", ArabicText.charAccuracy(q.questionText, S1_Q) >= MIN_ACCURACY)
        assertTrue(q.meanConfidence >= ScreenReader.DEFAULT_MIN_CONFIDENCE)
    }

    @Test
    fun optionRects_matchTheButtonsOfTheLabLayout() {
        // Reference boxes from the lab prototype (OpenCV) on the same photos. The JPEG decoder here
        // (javax.imageio) differs a little from OpenCV's, and the soft shadow under a button sits
        // right at the "white" threshold, so a button may end 10-15 px earlier; require a big overlap.
        // (On the same decoded pixels the Kotlin port reproduces the lab boxes exactly.)
        val s2 = OcrFixtures.reader.read(OcrFixtures.image("s2").let { Triple(it.argb, it.width, it.height) })!!
        val expectedS2 = listOf(
            PxRect(392, 1276, 834, 1341), PxRect(392, 1371, 834, 1436),
            PxRect(392, 1466, 834, 1532), PxRect(392, 1562, 834, 1627),
        )
        assertRectsClose(expectedS2, s2.optionRects)
        val s3 = OcrFixtures.reader.read(OcrFixtures.image("s3").let { Triple(it.argb, it.width, it.height) })!!
        val expectedS3 = listOf(
            PxRect(919, 447, 1813, 588), PxRect(919, 633, 1813, 778),
            PxRect(919, 824, 1813, 964), PxRect(919, 1009, 1813, 1150),
        )
        assertRectsClose(expectedS3, s3.optionRects)
    }

    private fun ScreenReader.read(t: Triple<IntArray, Int, Int>) = read(t.first, t.second, t.third)

    private fun assertRectsClose(expected: List<PxRect>, actual: List<PxRect>) {
        assertEquals(expected.size, actual.size)
        for ((e, a) in expected.zip(actual)) {
            val iw = minOf(e.right, a.right) - maxOf(e.left, a.left)
            val ih = minOf(e.bottom, a.bottom) - maxOf(e.top, a.top)
            val inter = maxOf(0, iw).toDouble() * maxOf(0, ih)
            val union = e.width.toDouble() * e.height + a.width.toDouble() * a.height - inter
            assertTrue("expected $e got $a (IoU ${inter / union})", inter / union >= 0.85)
        }
    }

    @Test
    fun smallerCaptures_ofTheSameScreens_stillRead() {
        // Phones capture at other sizes; the layout works relative to the image size. At 3/4 size
        // everything is still read perfectly; at half size a character or two may slip.
        for ((name, truthQ, truthOptions) in listOf(
            Triple("s2", S2_Q, S2_OPTIONS), Triple("s3", S3_Q, S3_OPTIONS),
        )) {
            for ((factor, minAccuracy) in listOf(0.75 to MIN_ACCURACY, 0.5 to 0.85)) {
                val img = OcrFixtures.scaled(OcrFixtures.image(name), factor)
                val run = timed(img, repeats = 2)
                log(name.uppercase(), img, run, truthQ, truthOptions)
                val q = run.reading.question
                assertNotNull("$name x$factor not read: ${run.reading.failure}", q)
                q!!
                assertEquals("$name x$factor options", 4, q.options.size)
                assertTrue("$name x$factor question", ArabicText.charAccuracy(q.questionText, truthQ) >= MIN_ACCURACY)
                for ((i, o) in q.options.withIndex()) {
                    assertTrue("$name x$factor option ${i + 1} '$o'", ArabicText.charAccuracy(o, truthOptions[i]) >= minAccuracy)
                }
                assertRectsOrderedAndInside(q, img)
            }
        }
    }

    @Test
    fun declinesWhenConfidenceIsBelowTheThreshold() {
        val strict = ScreenReader(OcrFixtures.recognizer, minConfidence = 1.01f)
        val img = OcrFixtures.image("s2")
        assertNull(strict.read(img.argb, img.width, img.height))
        assertTrue(strict.analyze(img.argb, img.width, img.height).failure!!.startsWith("low confidence"))
    }

    @Test
    fun declinesWhenThereIsNoQuestionOnScreen() {
        val w = 800
        val h = 1600
        val blank = IntArray(w * h) { 0xFF336699.toInt() }
        assertNull(OcrFixtures.reader.read(blank, w, h))
        assertEquals("no question banner", OcrFixtures.reader.analyze(blank, w, h).failure)
    }

    @Test
    fun cacheKeysOfTheSameQuestion_matchAcrossReadings() {
        val img = OcrFixtures.image("s3")
        val a = OcrFixtures.reader.read(img.argb, img.width, img.height)!!
        val b = OcrFixtures.reader.read(img.argb, img.width, img.height)!!
        assertEquals(ArabicText.key(a.questionText), ArabicText.key(b.questionText))
        assertEquals(1.0, ArabicText.similarity(a.questionText, S3_Q), 0.05)
    }
}
