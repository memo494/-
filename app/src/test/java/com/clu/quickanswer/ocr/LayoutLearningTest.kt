package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for "layout learning, part 1": parsing the model's layout reply and building a template from it. */
class LayoutLearningTest {

    // ---------- parsing ----------

    private val gridJson = """
        {"kind":"choices","language":"ar",
         "question":{"box":[405,340,461,745],"text":"من هو قائد جيش المسلمين في معركة حطين؟"},
         "options":[
           {"box":[537,533,636,703],"text":"صلاح الدين الايوبي"},
           {"box":[537,349,636,519],"text":"عمرو بن العاص"},
           {"box":[658,533,756,703],"text":"طارق بن زياد"},
           {"box":[658,349,756,519],"text":"نور الدين زنكي"}]}
    """.trimIndent()

    private val numericJson = """
        {"kind":"numeric","language":"ar",
         "question":{"box":[374,306,478,555],"text":"في أي عام تم الانتهاء من هدم جدار برلين ؟"},
         "options":[],
         "numeric_field":{"box":[520,345,617,529]}}
    """.trimIndent()

    @Test fun parsesAChoicesReply() {
        val r = LayoutReplyParser.parse(gridJson)!!
        assertEquals(LayoutKind.CHOICES, r.kind)
        assertEquals("ar", r.language)
        assertEquals(Box1000(405, 340, 461, 745), r.question)
        assertEquals("من هو قائد جيش المسلمين في معركة حطين؟", r.questionText)
        assertEquals(4, r.options.size)
        assertEquals(LayoutOption(Box1000(537, 349, 636, 519), "عمرو بن العاص"), r.options[1])
        assertNull(r.numericField)
    }

    @Test fun parsesANumericReply_andIgnoresOptionsThere() {
        val withOptions = numericJson.replace("\"options\":[]", "\"options\":[{\"box\":[1,1,2,2],\"text\":\"x\"}]")
        for (json in listOf(numericJson, withOptions)) {
            val r = LayoutReplyParser.parse(json)!!
            assertEquals(LayoutKind.NUMERIC, r.kind)
            assertTrue(r.options.isEmpty())
            assertEquals(Box1000(520, 345, 617, 529), r.numericField)
        }
    }

    @Test fun toleratesFencesAndWordsAroundTheJson() {
        assertNotNull(LayoutReplyParser.parse("```json\n$gridJson\n```"))
        assertNotNull(LayoutReplyParser.parse("Here is the layout:\n$gridJson\nDone."))
    }

    @Test fun roundsDecimalsAndClampsSmallOvershoot() {
        val r = LayoutReplyParser.parse(gridJson.replace("[405,340,461,745]", "[404.6,-3,461.2,1004]"))!!
        assertEquals(Box1000(405, 0, 461, 1000), r.question)
    }

    @Test fun normalisesLanguage() {
        assertEquals("en", LayoutReplyParser.parse(gridJson.replace("\"ar\"", "\"EN-us\""))!!.language)
        assertNull(LayoutReplyParser.parse(gridJson.replace("\"ar\"", "\"fr\""))!!.language)
        assertNull(LayoutReplyParser.parse(gridJson.replace("\"language\":\"ar\",", ""))!!.language)
    }

    @Test fun declinesWhatIsNotAUsableLayout() {
        val bad = listOf(
            "",
            "not json at all",
            "{\"kind\":\"none\"}",
            gridJson.replace("\"choices\"", "\"essay\""),
            gridJson.replace("[405,340,461,745]", "[461,340,405,745]"), // ymin > ymax
            gridJson.replace("[405,340,461,745]", "[405,340,461]"), // 3 numbers
            gridJson.replace("\"من هو قائد جيش المسلمين في معركة حطين؟\"", "\"  \""), // blank question
            gridJson.replace("\"عمرو بن العاص\"", "\"\""), // blank option
            gridJson.replace("[537,349,636,519]", "[537,349,537,519]"), // empty option box
            numericJson.replace(",\n \"numeric_field\":{\"box\":[520,345,617,529]}", ""),
        )
        for (s in bad) assertNull(s, LayoutReplyParser.parse(s))
        val oneOption = """{"kind":"choices","question":{"box":[1,1,100,100],"text":"q q"},
            "options":[{"box":[200,1,300,100],"text":"a"}]}"""
        assertNull(LayoutReplyParser.parse(oneOption))
        val nine = (0 until 9).joinToString(",") { """{"box":[${100 + it * 90},1,${180 + it * 90},100],"text":"o$it"}""" }
        assertNull(LayoutReplyParser.parse("""{"kind":"choices","question":{"box":[1,1,90,100],"text":"q q"},"options":[$nine]}"""))
    }

    // ---------- geometry ----------

    @Test fun mapsBoxesThroughTheCrop() {
        // 1000 x 2000 capture; rows 500..1500 were sent.
        val g = SentGeometry(1000, 2000, 500, 1500)
        val r = g.toRelRect(Box1000(0, 100, 1000, 900))
        assertEquals(0.1f, r.left, 1e-6f)
        assertEquals(0.25f, r.top, 1e-6f)
        assertEquals(0.9f, r.right, 1e-6f)
        assertEquals(0.75f, r.bottom, 1e-6f)
        assertEquals(0.5f, SentGeometry.whole(1000, 2000).toRelRect(Box1000(500, 0, 600, 10)).top, 1e-6f)
    }

    // ---------- order and arrangement ----------

    private fun opt(y0: Int, x0: Int, y1: Int, x1: Int, t: String) = LayoutOption(Box1000(y0, x0, y1, x1), t)

    private fun choices(options: List<LayoutOption>, language: String? = "ar") = LayoutReply(
        LayoutKind.CHOICES, language, Box1000(100, 100, 200, 900), "question text here", options, null
    )

    @Test fun gridIsReadRowByRowRightToLeftInArabic_leftToRightInEnglish() {
        // Given in a scrambled order.
        val opts = listOf(opt(500, 100, 600, 450, "BL"), opt(300, 550, 400, 900, "TR"), opt(500, 550, 600, 900, "BR"), opt(300, 100, 400, 450, "TL"))
        assertEquals(listOf("TR", "TL", "BR", "BL"), TemplateLearner.ordered(choices(opts, "ar")).map { it.text })
        assertEquals(listOf("TR", "TL", "BR", "BL"), TemplateLearner.ordered(choices(opts, null)).map { it.text })
        assertEquals(listOf("TL", "TR", "BL", "BR"), TemplateLearner.ordered(choices(opts, "en")).map { it.text })
        assertEquals(Arrangement.GRID, TemplateLearner.arrangementOf(choices(opts)))
    }

    @Test fun slightlyMisalignedButtonsStillShareARow() {
        // Right button 30 units lower than the left one: overlap 70 of 100 -> same row.
        val opts = listOf(opt(300, 100, 400, 450, "L"), opt(330, 550, 430, 900, "R"))
        assertEquals(listOf("R", "L"), TemplateLearner.ordered(choices(opts)).map { it.text })
        assertEquals(Arrangement.GRID, TemplateLearner.arrangementOf(choices(opts)))
    }

    @Test fun stackedButtonsAreVertical() {
        val opts = listOf(opt(500, 300, 580, 700, "2"), opt(400, 300, 480, 700, "1"), opt(600, 300, 680, 700, "3"))
        assertEquals(listOf("1", "2", "3"), TemplateLearner.ordered(choices(opts)).map { it.text })
        assertEquals(Arrangement.VERTICAL, TemplateLearner.arrangementOf(choices(opts)))
    }

    @Test fun numericHasNoOptions() {
        val r = LayoutReplyParser.parse(numericJson)!!
        assertTrue(TemplateLearner.ordered(r).isEmpty())
        assertEquals(Arrangement.NUMERIC, TemplateLearner.arrangementOf(r))
    }

    // ---------- building ----------

    private val w = 2000
    private val h = 900

    /** A dark screen with lighter boxes where [gridJson] puts the question and the four buttons. */
    private fun gridScreen(): IntArray {
        val c = Canvas(w, h, 0xFF14202A.toInt())
        c.fill((0.34 * w).toInt(), (0.405 * h).toInt(), (0.745 * w).toInt(), (0.461 * h).toInt(), 0xFFEFE3C8.toInt())
        for ((x0, x1) in listOf(0.533 to 0.703, 0.349 to 0.519)) for ((y0, y1) in listOf(0.537 to 0.636, 0.658 to 0.756)) {
            c.fill((x0 * w).toInt(), (y0 * h).toInt(), (x1 * w).toInt(), (y1 * h).toInt(), 0xFF505050.toInt())
        }
        return c.px
    }

    private fun assertRect(expected: RelRect, actual: RelRect) {
        assertEquals(expected.left, actual.left, 1e-4f)
        assertEquals(expected.top, actual.top, 1e-4f)
        assertEquals(expected.right, actual.right, 1e-4f)
        assertEquals(expected.bottom, actual.bottom, 1e-4f)
    }

    @Test fun buildsACandidateFromAGridReply() {
        val reply = LayoutReplyParser.parse(gridJson)!!
        val t = TemplateLearner.build(reply, SentGeometry.whole(w, h), gridScreen(), w, h, emptyList(), "t1", 42L)!!
        assertTrue(t.isValid)
        assertEquals("t1", t.id)
        assertNull(t.packageName)
        assertEquals(Arrangement.GRID, t.arrangement)
        assertEquals("ar", t.language)
        assertEquals(TemplateState.CANDIDATE, t.state)
        assertEquals(0, t.hits)
        assertEquals(0, t.misses)
        assertEquals("model", t.source)
        assertEquals(42L, t.createdAt)
        assertEquals(42L, t.lastUsedAt)
        // Question box grown by the margins.
        assertEquals(0.340f - TemplateLearner.QUESTION_MARGIN_X, t.question.left, 1e-4f)
        assertEquals(0.405f - TemplateLearner.QUESTION_MARGIN_Y, t.question.top, 1e-4f)
        assertEquals(0.745f + TemplateLearner.QUESTION_MARGIN_X, t.question.right, 1e-4f)
        assertEquals(0.461f + TemplateLearner.QUESTION_MARGIN_Y, t.question.bottom, 1e-4f)
        // Options in reading order (top-right first), unchanged.
        assertRect(RelRect(0.533f, 0.537f, 0.703f, 0.636f), t.options[0])
        assertRect(RelRect(0.349f, 0.537f, 0.519f, 0.636f), t.options[1])
        assertRect(RelRect(0.349f, 0.658f, 0.519f, 0.756f), t.options[3])
        assertEquals(ScreenFingerprint.of(gridScreen(), w, h), t.fingerprint)
        assertEquals(5, t.signatures.size)
        assertEquals(
            TemplateReader.signature(gridScreen(), w, h, t.options[2].toPx(w, h)),
            t.signatures[3]
        )
    }

    @Test fun buildsANumericTemplate() {
        val t = TemplateLearner.build(LayoutReplyParser.parse(numericJson)!!, SentGeometry.whole(w, h), gridScreen(), w, h, emptyList(), "n", 1L)!!
        assertEquals(Arrangement.NUMERIC, t.arrangement)
        assertTrue(t.options.isEmpty())
        assertRect(RelRect(0.345f, 0.520f, 0.529f, 0.617f), t.numericField!!)
        assertEquals(2, t.signatures.size)
    }

    @Test fun questionMarginIsClampedAtTheImageEdge() {
        val edge = gridJson.replace("[405,340,461,745]", "[0,0,60,745]")
        val t = TemplateLearner.build(LayoutReplyParser.parse(edge)!!, SentGeometry.whole(w, h), gridScreen(), w, h, emptyList(), "e", 1L)!!
        assertEquals(0f, t.question.left, 0f)
        assertEquals(0f, t.question.top, 0f)
    }

    @Test fun masksAreLeftOutOfTheFingerprint() {
        val masks = listOf(PxRect(0, 0, 600, 200))
        val t = TemplateLearner.build(LayoutReplyParser.parse(gridJson)!!, SentGeometry.whole(w, h), gridScreen(), w, h, masks, "m", 1L)!!
        assertEquals(ScreenFingerprint.of(gridScreen(), w, h, masks), t.fingerprint)
        assertTrue(t.fingerprint.contains('.'))
    }

    @Test fun declinesInsaneLayouts() {
        val g = SentGeometry.whole(w, h)
        val px = gridScreen()
        fun build(json: String) = LayoutReplyParser.parse(json)?.let { TemplateLearner.build(it, g, px, w, h, emptyList(), "x", 1L) }
        // Two options on top of each other.
        assertNull(build(gridJson.replace("[537,349,636,519]", "[540,540,630,700]")))
        // An option over the question.
        assertNull(build(gridJson.replace("[537,349,636,519]", "[400,349,470,519]")))
        // A sliver of an option.
        assertNull(build(gridJson.replace("[537,349,636,519]", "[537,349,545,519]")))
        // The number field over the question.
        assertNull(build(numericJson.replace("[520,345,617,529]", "[380,310,470,550]")))
        // Wrong capture size.
        assertNull(TemplateLearner.build(LayoutReplyParser.parse(gridJson)!!, SentGeometry.whole(w, h + 1), px, w, h, emptyList(), "x", 1L))
    }

    // ---------- confirming ----------

    private fun read(q: String, options: List<String>, numeric: Boolean = false) =
        Question(q, options, options.map { PxRect(0, 0, 1, 1) }, numeric, 0.95f)

    @Test fun confirmsWhenTheDeviceReadsWhatTheModelSaw() {
        val reply = LayoutReplyParser.parse(gridJson)!!
        // OCR-ish differences: letter variants and a missing diacritic-level detail.
        val ok = read(
            "من هو قائد جيش المسلمين فى معركة حطين؟",
            listOf("صلاح الدين الأيوبي", "عمرو بن العاص", "طارق بن زياد", "نور الدين زنكي")
        )
        assertTrue(TemplateLearner.confirms(ok, reply))
    }

    @Test fun doesNotConfirmADifferentReading() {
        val reply = LayoutReplyParser.parse(gridJson)!!
        val opts = listOf("صلاح الدين الايوبي", "عمرو بن العاص", "طارق بن زياد", "نور الدين زنكي")
        // Question cut in half.
        assertFalse(TemplateLearner.confirms(read("من هو قائد جيش", opts), reply))
        // Options in another order (the template would box the wrong button).
        assertFalse(TemplateLearner.confirms(read(reply.questionText, opts.reversed()), reply))
        // An option missing.
        assertFalse(TemplateLearner.confirms(read(reply.questionText, opts.dropLast(1)), reply))
        // Numeric reading of a choices screen.
        assertFalse(TemplateLearner.confirms(read(reply.questionText, emptyList(), numeric = true), reply))
    }

    @Test fun confirmsANumericReading() {
        val reply = LayoutReplyParser.parse(numericJson)!!
        assertTrue(TemplateLearner.confirms(read("في أى عام تم الانتهاء من هدم جدار برلين ؟", emptyList(), numeric = true), reply))
        assertFalse(TemplateLearner.confirms(read("في أى عام تم الانتهاء من هدم جدار برلين ؟", listOf("a", "b")), reply))
    }
}
