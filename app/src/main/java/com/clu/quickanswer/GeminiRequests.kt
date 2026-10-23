package com.clu.quickanswer

import com.clu.quickanswer.ocr.LayoutPrompt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini request bodies as pure functions (org.json only, no network, no Android), so they are unit
 * tested. [GeminiClient] does the HTTP and the thinking-mode detours.
 */
object GeminiRequests {

    // Thinking tokens count toward maxOutputTokens, and the layout reply is a JSON object of about 300
    // tokens (the boxes and texts of the question and its options), so this leaves plenty of room.
    private const val LAYOUT_MAX_OUTPUT_TOKENS = 2048

    /**
     * Thinking tokens count toward maxOutputTokens, so this leaves room beyond the short answer. The
     * limit of every answer request ([GeminiClient] uses it for the text and the screenshot too).
     */
    const val ANSWER_MAX_OUTPUT_TOKENS = 1024

    /**
     * The body that asks [model] a question that was read on the device together with its picture: the
     * [prompt] ([QuestionPrompt.buildPicture]), then the JPEG [imageBase64] (the order of the screenshot
     * request). A short plain-text answer, so no response type, and the default media resolution on
     * purpose, as in GeminiClient.imageBody: a small picture must not be read at a lower one. The thinking
     * option comes from [GeminiModels.thinkingConfig] for [mode], and is left out when that is null.
     */
    fun pictureBody(model: String, prompt: String, imageBase64: String, mode: ThinkingMode): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)
                )
            )
        val gen = JSONObject()
            .put("temperature", 0)
            .put("maxOutputTokens", ANSWER_MAX_OUTPUT_TOKENS)
        GeminiModels.thinkingConfig(model, mode)?.let { gen.put("thinkingConfig", it) }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }

    /**
     * The body that asks [model] for the screen's layout: [LayoutPrompt.TEXT], then the JPEG
     * [imageBase64] (the same order as the answer request). The reply is JSON, so the response type is
     * set to it. The thinking option comes from [GeminiModels.thinkingConfig] for [mode], and is left out
     * when that is null (Gemma takes none).
     *
     * No mediaResolution: the default resolution on purpose, small question text must stay readable
     * (see GeminiClient.imageBody).
     */
    fun layoutBody(model: String, imageBase64: String, mode: ThinkingMode): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", LayoutPrompt.TEXT))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)
                )
            )
        val gen = JSONObject()
            .put("temperature", 0)
            .put("maxOutputTokens", LAYOUT_MAX_OUTPUT_TOKENS)
            .put("responseMimeType", "application/json")
        GeminiModels.thinkingConfig(model, mode)?.let { gen.put("thinkingConfig", it) }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }
}
