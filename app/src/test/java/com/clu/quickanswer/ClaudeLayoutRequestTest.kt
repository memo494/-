package com.clu.quickanswer

import com.clu.quickanswer.ocr.LayoutPrompt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request that asks Claude for the screen's layout ([ClaudeApi.layoutRequestBody]). */
class ClaudeLayoutRequestTest {

    private fun body(model: String = "claude-haiku-5-5") = JSONObject(ClaudeApi.layoutRequestBody(model, "QUJD"))

    @Test
    fun layoutRequest_putsTheJpegBeforeTheLayoutPrompt() {
        val root = body()
        assertEquals("claude-haiku-5-5", root.getString("model"))
        val messages = root.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        val image = content.getJSONObject(0)
        assertEquals("image", image.getString("type"))
        assertEquals("base64", image.getJSONObject("source").getString("type"))
        assertEquals("image/jpeg", image.getJSONObject("source").getString("media_type"))
        assertEquals("QUJD", image.getJSONObject("source").getString("data"))
        // The prompt has quotes and line breaks: they must survive the JSON round trip.
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals(LayoutPrompt.TEXT, content.getJSONObject(1).getString("text"))
    }

    @Test
    fun layoutRequest_hasRoomForThinkingAndTheJsonReply() {
        assertEquals(2048, body().getInt("max_tokens"))
        // The answer requests keep their smaller limit.
        assertEquals(1024, JSONObject(ClaudeApi.requestBody("claude-haiku-5-5", "x", "QUJD")).getInt("max_tokens"))
        assertEquals(1024, JSONObject(ClaudeApi.requestBody("claude-haiku-5-5", "x")).getInt("max_tokens"))
    }

    @Test
    fun layoutRequest_isLowEffort_withoutAThinkingParameter() {
        val root = body("claude-opus-5-5")
        assertEquals("low", root.getJSONObject("output_config").getString("effort"))
        assertFalse("disabled thinking is a 400 on Opus 5.5", root.has("thinking"))
    }

    @Test
    fun layoutRequest_serverSideFallback_onlyOnModelsThatHaveIt() {
        assertEquals("default", body("claude-opus-5-5").getString("fallbacks"))
        assertTrue(ClaudeApi.usesFallback("claude-opus-5-5"))
        // Haiku 5.5 has no server-side fallback: a fallback list is a 400 there.
        assertFalse(body("claude-haiku-5-5").has("fallbacks"))
        assertFalse(ClaudeApi.usesFallback("claude-haiku-5-5"))
    }

    @Test
    fun layoutRequest_isTheAnswerRequestShape_withOnlyThePromptAndTheLimitChanged() {
        val layout = body("claude-opus-5-5")
        val answer = JSONObject(ClaudeApi.requestBody("claude-opus-5-5", LayoutPrompt.TEXT, "QUJD"))
        assertEquals(1024, answer.getInt("max_tokens"))
        answer.put("max_tokens", 2048)
        assertTrue("the layout request differs from the answer request in more than max_tokens", answer.similar(layout))
    }
}
