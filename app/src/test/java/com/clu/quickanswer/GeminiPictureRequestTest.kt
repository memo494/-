package com.clu.quickanswer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Gemini request that asks a question together with its picture: [GeminiRequests.pictureBody]. */
class GeminiPictureRequestTest {

    private val prompt = "Question: \"؟\"\nOptions:\n1) أ\n2) ب"

    private fun body(model: String = "gemini-3-flash-preview", mode: ThinkingMode = ThinkingMode.MINIMAL) =
        JSONObject(GeminiRequests.pictureBody(model, prompt, "QUJD", mode))

    @Test fun theTextComesFirst_thenTheJpeg_inOneRequest() {
        val contents = body().getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals(prompt, parts.getJSONObject(0).getString("text"))
        val inline = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/jpeg", inline.getString("mime_type"))
        assertEquals("QUJD", inline.getString("data"))
    }

    @Test fun itIsAShortDeterministicAnswer_notJson() {
        val gen = body().getJSONObject("generationConfig")
        assertEquals(0.0, gen.getDouble("temperature"), 0.0)
        // Thinking tokens count toward the limit, like the other answer requests.
        assertEquals(1024, gen.getInt("maxOutputTokens"))
        assertFalse(gen.has("responseMimeType"))
        // Default resolution: a small picture must not be read at a lower one.
        assertFalse(gen.has("mediaResolution"))
    }

    @Test fun theThinkingOptionFollowsTheModelAndMode() {
        for (model in listOf("gemini-3-flash-preview", "gemini-2.5-flash", "gemma-3-27b-it")) {
            for (mode in ThinkingMode.values()) {
                val gen = body(model, mode).getJSONObject("generationConfig")
                val want = GeminiModels.thinkingConfig(model, mode)
                if (want == null) assertNull("$model $mode", gen.optJSONObject("thinkingConfig"))
                else assertEquals("$model $mode", want.toString(), gen.getJSONObject("thinkingConfig").toString())
            }
        }
        assertTrue(body(mode = ThinkingMode.MINIMAL).getJSONObject("generationConfig").has("thinkingConfig"))
    }
}
