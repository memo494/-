package com.clu.quickanswer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Messages API request and reply handling that replaced the Anthropic SDK. */
class ClaudeApiTest {

    @Test
    fun textRequest_hasModelLowEffortAndOneTextBlock() {
        val body = JSONObject(ClaudeApi.requestBody("claude-haiku-5-5", "سؤال"))
        assertEquals("claude-haiku-5-5", body.getString("model"))
        assertEquals(1024, body.getInt("max_tokens"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertFalse("no thinking param: disabled thinking is a 400 on Opus 5.5", body.has("thinking"))
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val content = messages.getJSONObject(0).getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("سؤال", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun imageRequest_putsTheJpegBeforeThePrompt() {
        val content = JSONObject(ClaudeApi.requestBody("claude-haiku-5-5", "p", "QUJD"))
            .getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        val source = content.getJSONObject(0).getJSONObject("source")
        assertEquals("image", content.getJSONObject(0).getString("type"))
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals("QUJD", source.getString("data"))
        assertEquals("p", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun serverSideFallback_onlyOnModelsThatHaveIt() {
        assertEquals("default", JSONObject(ClaudeApi.requestBody("claude-opus-5-5", "x")).getString("fallbacks"))
        assertTrue(ClaudeApi.usesFallback("claude-sonnet-5-5"))
        // Haiku 5.5 has no server-side fallback.
        assertFalse(JSONObject(ClaudeApi.requestBody("claude-haiku-5-5", "x")).has("fallbacks"))
        assertFalse(ClaudeApi.usesFallback("claude-haiku-5-5"))
    }

    @Test
    fun success_joinsTheTextBlocks_andNamesTheModelThatServedIt() {
        val (reply, model) = ClaudeApi.parseSuccess(
            """{"model":"claude-opus-5","stop_reason":"end_turn","content":[
               {"type":"fallback","from":"claude-opus-5-5"},{"type":"text","text":" 2 "}]}"""
        )
        assertTrue(reply.isAnswer)
        assertEquals("2", reply.text)
        assertEquals("claude-opus-5", model)
    }

    @Test
    fun refusalOrNothing_isEmpty_soTheImagePathCanStillTry() {
        val (refused, _) = ClaudeApi.parseSuccess("""{"stop_reason":"refusal","content":[]}""")
        assertEquals(Reply.Kind.EMPTY, refused.kind)
        assertEquals(ClaudeApi.REFUSED, refused.text)
        val (empty, model) = ClaudeApi.parseSuccess("""{"content":[{"type":"thinking","thinking":""}]}""")
        assertEquals(Reply.Kind.EMPTY, empty.kind)
        assertNull(model)
    }

    private fun err(type: String, message: String) =
        """{"type":"error","error":{"type":"$type","message":"$message"},"request_id":"req_1"}"""

    @Test
    fun errors_thatWouldHitTheImageRequestToo_areFinal() {
        assertEquals("مفتاح Claude غير صالح", ClaudeApi.replyForError(401, err("authentication_error", "invalid x-api-key")).text)
        assertEquals("اسم نموذج Claude غير صحيح", ClaudeApi.replyForError(404, err("not_found_error", "model: x")).text)
        assertEquals("رصيد Claude غير كافٍ، اشحن حسابك", ClaudeApi.replyForError(402, err("billing_error", "x")).text)
        assertEquals(
            "رصيد Claude غير كافٍ، اشحن حسابك",
            ClaudeApi.replyForError(400, err("invalid_request_error", "Your credit balance is too low")).text
        )
        for (code in listOf(401, 402, 403, 404, 429, 500, 504, 529)) {
            assertEquals("status $code", Reply.Kind.ERROR, ClaudeApi.replyForError(code, err("x", "y")).kind)
        }
    }

    @Test
    fun aBadRequest_showsClaudesReason_andLetsTheImageTry() {
        val reply = ClaudeApi.replyForError(400, err("invalid_request_error", "messages: too long"))
        assertEquals(Reply.Kind.EMPTY, reply.kind)
        assertEquals("Claude: messages: too long", reply.text)
        assertEquals("", ClaudeApi.errorMessage("not json"))
    }

    @Test
    fun retries_onlyTransientStatuses() {
        for (code in listOf(408, 409, 429, 500, 529)) assertTrue("$code", ClaudeApi.isRetryable(code))
        for (code in listOf(400, 401, 402, 403, 404)) assertFalse("$code", ClaudeApi.isRetryable(code))
    }
}
