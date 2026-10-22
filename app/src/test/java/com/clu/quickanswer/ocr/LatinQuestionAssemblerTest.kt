package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class LatinQuestionAssemblerTest {
    private val banner = PxRect(100, 100, 900, 250)
    private val buttons = listOf(
        PxRect(240, 270, 760, 335),
        PxRect(240, 345, 760, 410),
        PxRect(240, 420, 760, 485),
        PxRect(240, 495, 760, 560)
    )
    private val layout = ScreenLayout(
        banner = banner,
        buttons = buttons,
        numericField = null,
        questionLines = listOf(PxRect(200, 160, 800, 190)),
        optionLines = buttons.map { listOf(it) }
    )
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, confidence: Float = 0.95f) =
        LatinQuestionAssembler.Segment(text, PxRect(l, t, r, b), confidence)

    private fun valid() = listOf(
        line("What is the capital of Jordan?", 190, 165, 750, 193),
        line("AMMAN", 320, 285, 480, 320),
        line("Doha", 320, 360, 480, 395),
        line("Cairo", 320, 435, 480, 470),
        line("Beirut", 320, 510, 480, 545),
        // HUD labels outside the banner and answer buttons must not be read.
        line("TIME: 00:12", 0, 5, 150, 30)
    )

    @Test fun readsEnglishQuestionAndAllFourOptions() {
        val q = LatinQuestionAssembler.assemble(layout, valid())!!
        assertEquals("What is the capital of Jordan?", q.questionText)
        assertEquals(listOf("AMMAN", "Doha", "Cairo", "Beirut"), q.options)
        assertEquals(buttons, q.optionRects)
        assertFalse(q.numeric)
        assertEquals(0.95f, q.meanConfidence, 0.001f)
    }

    @Test fun refusesOccludedQuestionOrOptions() {
        assertNull(LatinQuestionAssembler.assemble(layout, valid(),
            listOf(PxRect(350, 170, 410, 200))))
        assertNull(LatinQuestionAssembler.assemble(layout, valid(),
            listOf(PxRect(300, 360, 440, 390))))
    }

    @Test fun refusesMissingOptionAndDuplicatedOption() {
        assertNull(LatinQuestionAssembler.assemble(layout, valid().drop(4)))
        val duplicate = valid().toMutableList()
        duplicate[2] = duplicate[2].copy(text = "amman")
        assertNull(LatinQuestionAssembler.assemble(layout, duplicate))
    }

    @Test fun arabicNoiseOrHudIsNotAcceptedAsEnglishQuestion() {
        val segments = valid().toMutableList()
        segments[0] = segments[0].copy(text = "ما هي عاصمة الأردن؟")
        assertNull(LatinQuestionAssembler.assemble(layout, segments))
        assertNull(LatinQuestionAssembler.assemble(layout,
            listOf(line("Hello", 200, 180, 350, 204))))
    }

    @Test fun numericEnglishQuestionHasNoChoiceButtons() {
        val numeric = ScreenLayout(
            banner, emptyList(), PxRect(370, 223, 630, 269),
            listOf(PxRect(200, 160, 800, 190)), emptyList()
        )
        val answer = LatinQuestionAssembler.assemble(numeric,
            listOf(line("How many weeks are there in one year?", 180, 160, 780, 192)))
        assertNotNull(answer)
        assertTrue(answer!!.numeric)
        assertTrue(answer.options.isEmpty())
    }

    private val threeButtons = ScreenLayout(
        banner = banner,
        buttons = buttons.take(3),
        numericField = null,
        questionLines = layout.questionLines,
        optionLines = buttons.take(3).map { listOf(it) }
    )
    private fun threeOptions() = valid().filterNot { it.text == "Beirut" }

    @Test fun refusesMaskInTheSlotWhereAnotherButtonWouldBe() {
        // Third button ends at y=485; the next slot is y=485..560, x=240..760. This mask touches
        // neither a button nor the banner.
        val slotMask = PxRect(300, 500, 440, 540)
        assertNull(LatinQuestionAssembler.assemble(threeButtons, threeOptions(), listOf(slotMask)))
        val far = PxRect(0, 900, 100, 1000)
        assertNotNull(LatinQuestionAssembler.assemble(threeButtons, threeOptions(), listOf(far)))
    }

    @Test fun refusesMaskOnTheBannerOutsideTheQuestionLines() {
        assertNull(LatinQuestionAssembler.assemble(layout, valid(), listOf(PxRect(120, 110, 180, 130))))
    }

    @Test fun refusesALowConfidenceQuestionLine() {
        val s = valid().toMutableList()
        s[0] = s[0].copy(confidence = 0.4f)
        assertNull(LatinQuestionAssembler.assemble(layout, s))
    }

    @Test fun refusesALowConfidenceOptionLine() {
        val s = valid().toMutableList()
        s[2] = s[2].copy(confidence = 0.4f)
        assertNull(LatinQuestionAssembler.assemble(layout, s))
    }

    @Test fun refusesUnavailableConfidence() {
        assertNull(LatinQuestionAssembler.assemble(layout, valid().map { it.copy(confidence = 0f) }))
    }

    @Test fun refusesAMeanJustBelowTheThreshold() {
        // Every line is above the per-line floor, the weighted mean is not.
        assertNull(LatinQuestionAssembler.assemble(layout, valid().map { it.copy(confidence = 0.74f) }))
        assertNotNull(LatinQuestionAssembler.assemble(layout, valid().map { it.copy(confidence = 0.76f) }))
    }

    @Test fun refusesAWeakQuestionEvenWhenTheOptionsAreStrong() {
        val s = valid().toMutableList()
        s[0] = s[0].copy(confidence = 0.65f)
        s[1] = s[1].copy(confidence = 1f)
        s[2] = s[2].copy(confidence = 1f)
        s[3] = s[3].copy(confidence = 1f)
        s[4] = s[4].copy(confidence = 1f)
        // Overall mean is above 0.75 but the question alone is 0.65.
        assertNull(LatinQuestionAssembler.assemble(layout, s))
    }
}
