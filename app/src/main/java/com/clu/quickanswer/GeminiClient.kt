package com.clu.quickanswer

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiClient(apiKey: String, model: String) {

    // Pasted keys often carry stray spaces or a newline, which Google rejects with a 400.
    private val apiKey = Prefs.cleanKey(apiKey)
    private val model = Prefs.cleanModel(model).ifEmpty { Prefs.DEFAULT_MODEL }

    private class HttpResult(val code: Int, val body: String)

    /** Returns the short answer, or a short Arabic error message. Never throws. */
    fun answer(bitmap: Bitmap): String {
        return try {
            val b64 = encode(bitmap)
            // Free-tier models are often overloaded (503) or out of quota (429), and quota is
            // per model, so retry briefly and then fall back to the next model.
            // Report the first real error (normally the user's own model), not whatever the last
            // fallback said; a fallback model that doesn't exist (404) is never the story.
            var first: Pair<String, HttpResult>? = null
            for (m in (listOf(model) + FALLBACK_MODELS).distinct()) {
                for (attempt in 0 until 2) {
                    val res = ask(m, b64)
                    if (res.code in 200..299) return handle(res)
                    if (first == null || first.second.code == 404) first = m to res
                    if (res.code !in RETRYABLE || isKeyOrRegionError(res)) return "$m: ${handle(res)}"
                    if (res.code == 503 || res.code == 500) Thread.sleep(600) else break
                }
            }
            val (m, res) = first!!
            "$m: ${handle(res)}"
        } catch (e: IOException) {
            "لا يوجد اتصال"
        } catch (e: Exception) {
            "خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim()
        }
    }

    private fun encode(src: Bitmap): String {
        val maxSide = 1280
        val longest = maxOf(src.width, src.height)
        val bmp = if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(
                src,
                maxOf(1, (src.width * s).toInt()),
                maxOf(1, (src.height * s).toInt()),
                true
            )
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (bmp !== src) bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildBody(b64: String, withThinking: Boolean): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                )
            )
        val gen = JSONObject().put("temperature", 0).put("maxOutputTokens", 256)
        if (withThinking) {
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }

    private fun ask(m: String, b64: String): HttpResult {
        var res = post(m, buildBody(b64, true))
        // Turning thinking off makes answers much faster, but not every model accepts the option,
        // and some reject it with only a generic "invalid argument", so retry any 400 without it.
        if (res.code == 400 && !isKeyOrRegionError(res)) {
            res = post(m, buildBody(b64, false))
        }
        return res
    }

    private fun post(m: String, body: String): HttpResult {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(res: HttpResult): String = try {
        JSONObject(res.body).optJSONObject("error")?.optString("message") ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Errors no other model can fix, so falling back would only waste time. */
    private fun isKeyOrRegionError(res: HttpResult): Boolean {
        if (res.code == 403) return true
        val msg = errorMessage(res)
        return res.code == 400 &&
            (msg.contains("API key", ignoreCase = true) || msg.contains("location", ignoreCase = true))
    }

    private fun handle(res: HttpResult): String {
        if (res.code !in 200..299) {
            val msg = errorMessage(res)
            return when {
                msg.contains("location is not supported", ignoreCase = true) ->
                    "Gemini غير متاح في منطقتك، جرّب تشغيل VPN"
                res.code == 429 -> "تم تجاوز الحد المجاني، انتظر قليلاً"
                res.code == 503 || res.code == 500 || res.code == 504 ->
                    "خوادم Gemini مشغولة الآن، جرّب بعد قليل"
                (res.code == 400 || res.code == 403) &&
                    (msg.contains("API key", ignoreCase = true) ||
                        msg.contains("permission", ignoreCase = true) ||
                        res.code == 403) -> "مفتاح API غير صالح"
                // Show Google's own reason so unexpected errors can be diagnosed.
                msg.isNotEmpty() -> "خطأ ${res.code}: ${msg.take(160)}"
                else -> "خطأ: ${res.code}"
            }
        }
        val json = JSONObject(res.body)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) return EMPTY
        val partsArr = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: return EMPTY
        val sb = StringBuilder()
        for (i in 0 until partsArr.length()) {
            sb.append(partsArr.getJSONObject(i).optString("text", ""))
        }
        val text = sb.toString().trim()
        return if (text.isEmpty()) EMPTY else text
    }

    companion object {
        // Tried in order after the user's model. 404 (model not available) also moves on.
        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash-lite"
        )
        // 400 included: a request one model rejects (e.g. an unsupported option) may work on another.
        private val RETRYABLE = setOf(400, 404, 429, 500, 503, 504)

        private const val EMPTY = "لم يتم الحصول على إجابة"

        private const val PROMPT =
            "You see a phone screenshot. Read the question(s) on it, including any images, " +
                "diagrams or answer options. Reply with ONLY the final answer, in the same " +
                "language as the question: for multiple choice give the option letter/number " +
                "and its text; otherwise the short answer (number, word or phrase). No " +
                "explanation, no reasoning, no preamble. If there are several questions, put " +
                "each answer on its own line, numbered. If no question is visible, reply " +
                "exactly: لا يوجد سؤال"
    }
}
