package com.clu.quickanswer

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiClient(private val apiKey: String, private val model: String) {

    private class HttpResult(val code: Int, val body: String)

    /** Returns the short answer, or a short Arabic error message. Never throws. */
    fun answer(bitmap: Bitmap): String {
        return try {
            val b64 = encode(bitmap)
            var res = post(buildBody(b64, true))
            if (res.code == 400 && res.body.contains("thinking", ignoreCase = true)) {
                res = post(buildBody(b64, false))
            }
            handle(res)
        } catch (e: IOException) {
            "لا يوجد اتصال"
        } catch (e: Exception) {
            "خطأ: ${e.javaClass.simpleName}"
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

    private fun post(body: String): HttpResult {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
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

    private fun handle(res: HttpResult): String {
        if (res.code !in 200..299) {
            val msg = try {
                JSONObject(res.body).optJSONObject("error")?.optString("message") ?: ""
            } catch (e: Exception) {
                ""
            }
            return when {
                res.code == 429 -> "تم تجاوز الحد المجاني، انتظر قليلاً"
                (res.code == 400 || res.code == 403) &&
                    (msg.contains("API key", ignoreCase = true) ||
                        msg.contains("permission", ignoreCase = true) ||
                        res.code == 403) -> "مفتاح API غير صالح"
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
