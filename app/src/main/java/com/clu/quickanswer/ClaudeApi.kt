package com.clu.quickanswer

import com.clu.quickanswer.ocr.LayoutPrompt
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Claude Messages API request and reply as pure functions (org.json only, no network, no
 * Android), so they are unit tested. [ClaudeClient] does the HTTP.
 */
object ClaudeApi {

    const val BASE_URL = "https://api.anthropic.com/v1"
    const val API_VERSION = "2023-06-01"

    /** Retry a refused request on Anthropic's recommended fallback model, inside the same request. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    // Room for the model's (adaptive) thinking as well as the short answer.
    private const val MAX_TOKENS = 1024

    // The layout reply is a JSON object of about 300 tokens (the boxes and texts of the question and
    // its options), and thinking counts toward max_tokens too.
    private const val LAYOUT_MAX_TOKENS = 2048

    /** Models with server-side refusal fallback. Claude Haiku 5.5 has none (a fallback list is a 400 there). */
    private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

    fun usesFallback(model: String): Boolean = model in FALLBACK_MODELS

    /**
     * The JSON body of one request: the JPEG [imageBase64] first when there is one, then [text].
     * Lowest effort = fastest answer. No thinking parameter: disabled thinking is a 400 on Opus 5.5.
     * [maxTokens] is only raised by [layoutRequestBody].
     */
    fun requestBody(model: String, text: String, imageBase64: String? = null, maxTokens: Int = MAX_TOKENS): String {
        val content = JSONArray()
        if (imageBase64 != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageBase64)
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", text))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("output_config", JSONObject().put("effort", "low"))
        if (usesFallback(model)) body.put("fallbacks", "default")
        return body.toString()
    }

    /**
     * The request that asks for the screen's layout: the JPEG [imageBase64], then [LayoutPrompt.TEXT].
     * The same shape as [requestBody] with a larger token limit, see [LAYOUT_MAX_TOKENS].
     */
    fun layoutRequestBody(model: String, imageBase64: String): String =
        requestBody(model, LayoutPrompt.TEXT, imageBase64, LAYOUT_MAX_TOKENS)

    /**
     * The reply of a successful (2xx) request as a [Reply] for the app, plus the model that answered.
     * A refusal or an empty reply is [Reply.Kind.EMPTY]: the screenshot path may still work.
     */
    fun parseSuccess(json: String): Pair<Reply, String?> {
        val root = JSONObject(json)
        val model = root.optString("model", "").ifEmpty { null }
        val blocks = root.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text", ""))
        }
        val text = sb.toString().trim()
        if (text.isEmpty() && root.optString("stop_reason") == "refusal") return Reply.empty(REFUSED) to model
        if (text.isEmpty()) return Reply.empty(EMPTY) to model
        return Reply.answer(text) to model
    }

    /** The error's own message from an error body, or "". */
    fun errorMessage(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message", "") ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * An error status as a short Arabic [Reply]. Errors that would hit the image request too (key,
     * permission, model name, credit, rate limit, overload) are [Reply.Kind.ERROR]; a 400 about the
     * request itself is [Reply.Kind.EMPTY], so the screenshot is still tried.
     */
    fun replyForError(code: Int, body: String): Reply {
        val msg = errorMessage(body)
        return when {
            code == 401 -> Reply.error("مفتاح Claude غير صالح")
            code == 403 -> Reply.error("مفتاح Claude لا يملك صلاحية لهذا النموذج")
            code == 404 -> Reply.error("اسم نموذج Claude غير صحيح")
            code == 402 || (code == 400 && (msg.contains("credit", true) || msg.contains("billing", true))) ->
                Reply.error("رصيد Claude غير كافٍ، اشحن حسابك")
            code == 429 -> Reply.error("تم تجاوز حد Claude، انتظر قليلاً")
            code == 500 || code == 504 || code == 529 -> Reply.error("خوادم Claude مشغولة، جرّب بعد قليل")
            code == 400 -> Reply.empty("Claude: ${msg.take(160)}")
            else -> Reply.empty("خطأ Claude $code: ${msg.take(120)}".trim())
        }
    }

    /** Statuses worth one quick retry (what the SDK used to retry). */
    fun isRetryable(code: Int): Boolean = code == 408 || code == 409 || code == 429 || code >= 500

    const val EMPTY = "لم يتم الحصول على إجابة"
    const val REFUSED = "رفض النموذج الإجابة"
}
