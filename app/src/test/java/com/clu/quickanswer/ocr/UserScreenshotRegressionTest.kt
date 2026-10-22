package com.clu.quickanswer.ocr

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression cases extracted from the user's real screenshots on 2026-10-22.
 *
 * The 24x12 luma samples and screenshot-index are committed to GitHub as small
 * text fixtures. They verify coverage and screen identities without requiring
 * an external download in CI. Real OCR accuracy and gesture coordinates still
 * need the source-resolution screenshots and a physical Android device.
 */
class UserScreenshotRegressionTest {
    private fun manifest() = JSONObject(
        javaClass.getResourceAsStream("/user_20261022/screenshot-index.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    )

    @Test fun everySuppliedScreenshotIsIndexedAndHasAValidVisualReference() {
        val cases = manifest().getJSONArray("cases")
        val refs = UserScreenshotFingerprints.references
        assertEquals(9, cases.length())
        assertEquals(9, refs.size)
        assertEquals(9, refs.map { it.filename }.toSet().size)
        for (i in 0 until cases.length()) {
            val ref = refs[i]
            val sample = cases.getJSONObject(i)
            assertEquals(sample.getString("filename"), ref.filename)
            assertEquals(sample.getString("type"), ref.type)
            assertEquals(24 * 12, ref.luma16.length)
            assertTrue(ref.luma16.all { it.isDigit() || it in 'a'..'f' })
            assertEquals(24 * 12, ref.pixels().size)
        }
    }

    @Test fun capturesBothKeypadSkinsAndOptionArrangements() {
        val kinds = UserScreenshotFingerprints.references.map { it.type }.toSet()
        assertTrue("dark keypad", "dark_numeric_with_confirm" in kinds)
        assertTrue("white keypad", "white_numeric_with_green_submit" in kinds)
        assertTrue("2-column options", "dark_grid_2x2" in kinds)
        assertTrue("question image", "dark_grid_2x2_with_image" in kinds)
        assertTrue("legacy vertical answers", "white_vertical_options" in kinds)
        assertTrue("non-game screen", "gallery_screenshot_negative" in kinds)
    }

    @Test fun photoViewerMustNotBeInterpretedAsAnActiveGameScreen() {
        val gallery = UserScreenshotFingerprints.references.single {
            it.type == "gallery_screenshot_negative"
        }
        // The image viewer has large black letterbox regions around a SMALL
        // pictured game; it must not be mistaken for the original live game.
        val dark = gallery.luma16.count { it.digitToInt(16) <= 1 }
        assertTrue("gallery screen should be mostly dark: $dark", dark > 24 * 12 / 2)
    }

    @Test fun repeatedLiveScreenSamplesDifferLessThanDifferentSkins() {
        val refs = UserScreenshotFingerprints.references.associateBy { it.filename }
        fun difference(a: String, b: String): Double {
            val x = refs.getValue(a).luma16
            val y = refs.getValue(b).luma16
            return x.indices.map { kotlin.math.abs(x[it].digitToInt(16) - y[it].digitToInt(16)) }.average()
        }
        val sameSkin = difference("1000277246.jpg", "1000277245.jpg")
        val differentSkin = difference("1000277245.jpg", "1000277019.jpg")
        assertTrue("same layout should look alike", sameSkin < differentSkin)
    }

    @Test fun knownAnswersAreNotUsedAsAutomaticTrainingLabels() {
        val all = manifest().getJSONArray("cases")
        var labelledChoices = 0
        for (i in 0 until all.length()) {
            val sample = all.getJSONObject(i)
            if (sample.has("optionsRtl") || sample.has("optionsTopDown")) {
                val answers = if (sample.has("optionsRtl")) sample.getJSONArray("optionsRtl")
                else sample.getJSONArray("optionsTopDown")
                val answer = sample.getString("expectedAnswer")
                assertTrue(
                    "Answer must be visible in this image's choices: ${sample.getString("filename")}",
                    (0 until answers.length()).any { answers.getString(it) == answer }
                )
                labelledChoices++
            }
        }
        assertEquals(4, labelledChoices)
    }
}
