package com.clu.quickanswer

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GeminiClient(apiKey: String, model: String) : Answerer {

    // Pasted keys often carry stray spaces or a newline, which Google rejects with a 400.
    private val apiKey = Prefs.cleanKey(apiKey)
    private val model = Prefs.cleanModel(model).ifEmpty { Prefs.DEFAULT_MODEL }

    private class HttpResult(val code: Int, val body: String)

    /** The image last sent to Gemini (after cropping/scaling), so the user can check it. */
    @Volatile override var lastSent: Bitmap? = null
        private set

    /** Returns the short answer, or a short Arabic error message. Never throws. */
    override fun answer(bitmap: Bitmap): String {
        return try {
            val prepared = ScreenImage.prepare(bitmap) ?: return ScreenImage.BLACK_SCREEN
            lastSent = prepared.kept
            val b64 = prepared.base64
            // Free-tier models are often overloaded (503) or out of quota (429), and quota is
            // per model. Ask a few models at once and take whichever answers first; only if
            // they all fail, try the rest.
            val models = (listOf(model) + FALLBACK_MODELS).distinct()
            val errors = mutableListOf<Pair<String, HttpResult>>()
            for (group in listOf(models.take(RACE_SIZE), models.drop(RACE_SIZE))) {
                if (group.isEmpty()) continue
                val done = java.util.concurrent.ExecutorCompletionService<Pair<String, HttpResult>>(POOL)
                group.forEach { m -> done.submit { m to ask(m, b64) } }
                repeat(group.size) {
                    val (m, res) = try {
                        done.take().get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        throw e.cause ?: e
                    }
                    if (res.code in 200..299) {
                        val text = handle(res)
                        if (text != EMPTY) return text
                    }
                    errors += m to res
                    if (isKeyOrRegionError(res)) return "$m: ${handle(res)}"
                }
            }
            // Report the most telling error: the user's own model first, never a missing model (404).
            val (m, res) = errors.sortedBy { (em, er) -> (if (er.code == 404) 2 else 0) + (if (em == model) 0 else 1) }
                .first()
            "$m: ${handle(res)}"
        } catch (e: IOException) {
            "لا يوجد اتصال"
        } catch (e: Exception) {
            "خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim()
        }
    }

    private fun buildBody(m: String, b64: String, withThinking: Boolean): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", ScreenImage.PROMPT))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                )
            )
        // Thinking tokens count toward maxOutputTokens, so leave room beyond the short answer.
        val gen = JSONObject().put("temperature", 0).put("maxOutputTokens", 1024)
        if (withThinking) {
            // Least thinking = fastest answer. Gemini 2.5 takes a token budget, 3.x a level.
            val cfg = if (m.startsWith("gemini-2.")) JSONObject().put("thinkingBudget", 0)
            else JSONObject().put("thinkingLevel", "minimal")
            gen.put("thinkingConfig", cfg)
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }

    private fun ask(m: String, b64: String): HttpResult {
        var res = post(m, buildBody(m, b64, true))
        // Turning thinking off makes answers much faster, but not every model accepts the option,
        // and some reject it with only a generic "invalid argument", so retry any 400 without it.
        if (res.code == 400 && !isKeyOrRegionError(res)) {
            res = post(m, buildBody(m, b64, false))
        }
        return res
    }

    private fun post(m: String, body: String): HttpResult {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
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
            val part = partsArr.getJSONObject(i)
            if (part.optBoolean("thought", false)) continue
            sb.append(part.optString("text", ""))
        }
        val text = sb.toString().trim()
        return if (text.isEmpty()) EMPTY else text
    }

    companion object {
        // Tried in order after the user's model. 404 (model not available) also moves on.
        // The first two join the user's model in the first race (fast and accurate in testing).
        private val FALLBACK_MODELS = listOf(
            "gemini-flash-lite-latest",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.8-flash",
            "gemini-flash-latest",
            "gemini-3.1-flash-lite"
        )
        private const val RACE_SIZE = 3
        private val POOL = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "gemini").apply { isDaemon = true }
        }

        private const val EMPTY = "لم يتم الحصول على إجابة"
    }
}
