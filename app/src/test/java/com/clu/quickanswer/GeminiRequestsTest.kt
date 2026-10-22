package com.clu.quickanswer

import com.clu.quickanswer.ocr.LayoutPrompt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request that asks Gemini for the screen's layout. */
class GeminiRequestsTest {

    private fun body(
        model: String = "gemini-3-flash-preview",
        mode: ThinkingMode = ThinkingMode.MINIMAL,
        imageBase64: String = "QUJD"
    ) = JSONObject(GeminiRequests.layoutBody(model, imageBase64, mode))

    private fun config(model: String = "gemini-3-flash-preview", mode: ThinkingMode = ThinkingMode.MINIMAL) =
        body(model, mode).getJSONObject("generationConfig")

    @Test
    fun layoutBody_sendsThePromptFirst_thenTheJpeg() {
        val root = body()
        assertTrue(root.has("contents") && root.has("generationConfig"))
        val contents = root.getJSONArray("contents")
        assertEquals(1, contents.length())
        val parts = contents.getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        // The prompt has quotes and line breaks: they must survive the JSON round trip.
        assertEquals(LayoutPrompt.TEXT, parts.getJSONObject(0).getString("text"))
        val inline = parts.getJSONObject(1).getJSONObject("inline_data")
        assertEquals("image/jpeg", inline.getString("mime_type"))
        assertEquals("QUJD", inline.getString("data"))
    }

    @Test
    fun layoutBody_isDeterministicJson_withRoomForThinkingAndTheReply() {
        val gen = config()
        assertEquals(0.0, gen.getDouble("temperature"), 0.0)
        // Thinking tokens count toward the limit; the JSON reply itself is about 300 tokens.
        assertEquals(2048, gen.getInt("maxOutputTokens"))
        assertEquals("application/json", gen.getString("responseMimeType"))
    }

    @Test
    fun layoutBody_keepsTheDefaultMediaResolution_soSmallTextStaysReadable() {
        assertFalse(config().has("mediaResolution"))
        assertFalse(body().has("mediaResolution"))
    }

    @Test
    fun layoutBody_carriesTheThinkingConfigOfTheModelAndMode() {
        assertEquals(
            """{"thinkingLevel":"minimal"}""",
            config("gemini-3-flash-preview", ThinkingMode.MINIMAL).getJSONObject("thinkingConfig").toString()
        )
        assertEquals(
            """{"thinkingLevel":"low"}""",
            config("gemini-3.8-flash", ThinkingMode.LOW).getJSONObject("thinkingConfig").toString()
        )
        // UNKNOWN is tried as minimal, and Gemini 2.x takes a budget instead of a level.
        assertEquals(
            """{"thinkingLevel":"minimal"}""",
            config("gemini-9-new", ThinkingMode.UNKNOWN).getJSONObject("thinkingConfig").toString()
        )
        assertEquals(
            """{"thinkingBudget":0}""",
            config("gemini-2.5-flash", ThinkingMode.MINIMAL).getJSONObject("thinkingConfig").toString()
        )
    }

    @Test
    fun layoutBody_hasNoThinkingConfig_whenTheModelTakesNone() {
        // Gemma, and any model after the thinking detour ended on "none", get no thinkingConfig at all.
        assertNull(GeminiModels.thinkingConfig("gemma-4-31b-it", ThinkingMode.NONE))
        assertFalse(config("gemma-4-31b-it", ThinkingMode.NONE).has("thinkingConfig"))
        assertFalse(config("gemini-3-flash-preview", ThinkingMode.NONE).has("thinkingConfig"))
        // The rest of the config is still there.
        val gen = config("gemma-4-31b-it", ThinkingMode.NONE)
        assertEquals(2048, gen.getInt("maxOutputTokens"))
        assertEquals("application/json", gen.getString("responseMimeType"))
    }

    @Test
    fun layoutBody_thinkingConfigFollowsGeminiModels_forEveryCatalogModelAndMode() {
        for (m in GeminiModels.CATALOG) {
            for (mode in ThinkingMode.values()) {
                val gen = config(m.id, mode)
                val expected = GeminiModels.thinkingConfig(m.id, mode)
                if (expected == null) {
                    assertFalse("${m.id} $mode", gen.has("thinkingConfig"))
                } else {
                    assertEquals("${m.id} $mode", expected.toString(), gen.getJSONObject("thinkingConfig").toString())
                }
            }
        }
    }
}
