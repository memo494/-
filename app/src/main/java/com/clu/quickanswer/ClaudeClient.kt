package com.clu.quickanswer

import android.graphics.Bitmap
import android.os.SystemClock
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Claude through the Messages API with plain HttpURLConnection (the request and reply themselves are
 * in [ClaudeApi]). Like [GeminiClient], the platform keeps the TLS connection in its pool, and
 * [warmUp] opens it before the first question.
 */
class ClaudeClient(apiKey: String, model: String) : Answerer {

    private val apiKey = Prefs.cleanKey(apiKey)
    private val model = Prefs.cleanModel(model).ifEmpty { Prefs.DEFAULT_CLAUDE_MODEL }

    /** The image last sent to Claude (after cropping/scaling), so the user can check it. */
    @Volatile override var lastSent: Bitmap? = null
        private set

    /** The model that served the last answer (a server-side fallback may have answered instead). */
    @Volatile override var lastModel: String? = null
        private set

    /** The model's answer for the screenshot, or a short Arabic error message. Never throws. */
    override fun answer(bitmap: Bitmap, prompt: ImagePrompt): Reply {
        val prepared = try {
            ScreenImage.prepare(bitmap) ?: return Reply.error(ScreenImage.BLACK_SCREEN)
        } catch (e: Exception) {
            return Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
        lastSent = prepared.kept
        return send(ClaudeApi.requestBody(model, prompt.text, prepared.base64))
    }

    /** A question already read on the device: text only, no image. */
    override fun answerText(prompt: String): Reply = send(ClaudeApi.requestBody(model, prompt))

    /**
     * A question already read on the device, with its picture (see [Answerer.answerPicture]): the
     * picture and the prompt in one request, sent, retried and mapped to errors like the others.
     * [lastSent] is the picture as sent.
     */
    override fun answerPicture(prompt: String, picture: Bitmap): Reply {
        val prepared = try {
            ScreenImage.preparePicture(picture)
        } catch (e: Exception) {
            return Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
        lastSent = prepared.kept
        return send(ClaudeApi.requestBody(model, prompt, prepared.base64))
    }

    /**
     * The screen's layout, for learning a template (see [Answerer.layout]): ONE request, without
     * [send]'s retry, so learning in the background never takes time from the answers. [lastSent]
     * and [lastModel] stay as they are. Null on any failure: a black screen, a status other than 2xx,
     * a dropped connection, a refusal or an empty reply.
     */
    override fun layout(bitmap: Bitmap): LayoutAnswer? {
        return try {
            if (apiKey.isEmpty()) return null
            val prepared = ScreenImage.prepare(bitmap) ?: return null
            val res = call("${ClaudeApi.BASE_URL}/messages", ClaudeApi.layoutRequestBody(model, prepared.base64))
            if (res.code !in 200..299) return null
            val (reply, _) = ClaudeApi.parseSuccess(res.body)
            if (reply.isAnswer) LayoutAnswer(reply.text, prepared.geometry) else null
        } catch (e: Throwable) {
            // Background work: whatever went wrong (even an OutOfMemoryError on a big capture) only
            // means there is no layout this time.
            null
        }
    }

    /**
     * Opens the TLS connection ahead of the first question with a free List Models call. Only warms a
     * cold pool, at most once per [WARM_UP_INTERVAL_MS] (same reasoning as [GeminiClient.warmUp]).
     * Blocks, so call it off the main thread.
     */
    override fun warmUp() {
        if (apiKey.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (inFlight.get() > 0) return
        val done = lastDone.get()
        if (done != NEVER && now - done < POOL_WARM_MS) return
        while (true) {
            val last = lastWarmUp.get()
            if (last != NEVER && now - last < WARM_UP_INTERVAL_MS) return
            if (lastWarmUp.compareAndSet(last, now)) break
        }
        try {
            call("${ClaudeApi.BASE_URL}/models?limit=1", null)
        } catch (e: Exception) {
            // Only a warm-up; the real request reports any problem.
        }
    }

    /** One request, retried once after a short pause on a dropped connection or a transient status. Never throws. */
    private fun send(body: String): Reply {
        if (apiKey.isEmpty()) return Reply.error("مفتاح Claude غير صالح")
        for (attempt in 0..1) {
            val last = attempt == 1
            try {
                val res = call("${ClaudeApi.BASE_URL}/messages", body)
                if (res.code in 200..299) {
                    val (reply, served) = ClaudeApi.parseSuccess(res.body)
                    if (reply.isAnswer) lastModel = served ?: model
                    return reply
                }
                if (last || !ClaudeApi.isRetryable(res.code)) return ClaudeApi.replyForError(res.code, res.body)
            } catch (e: IOException) {
                if (last) return Reply.error(NO_CONNECTION)
            } catch (e: Exception) {
                return Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
            }
            try {
                Thread.sleep(RETRY_DELAY_MS)
            } catch (e: InterruptedException) {
                return Reply.error(NO_CONNECTION)
            }
        }
        return Reply.error(NO_CONNECTION)
    }

    private class Response(val code: Int, val body: String)

    /**
     * GET when [body] is null, else POST. The body is read to the end on every path and the connection
     * is not disconnect()ed on success, so the platform can reuse it for the next call.
     */
    private fun call(url: String, body: String?): Response {
        inFlight.incrementAndGet()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", ClaudeApi.API_VERSION)
            if (body != null) {
                if (ClaudeApi.usesFallback(model)) conn.setRequestProperty("anthropic-beta", ClaudeApi.FALLBACK_BETA)
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else {
                conn.requestMethod = "GET"
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            lastDone.set(SystemClock.elapsedRealtime())
            return Response(code, text)
        } catch (e: Exception) {
            // A broken connection must not go back to the pool.
            conn.disconnect()
            throw e
        } finally {
            inFlight.decrementAndGet()
        }
    }

    companion object {
        private const val NO_CONNECTION = "لا يوجد اتصال"
        private const val RETRY_DELAY_MS = 600L
        private const val WARM_UP_INTERVAL_MS = 60_000L
        private const val POOL_WARM_MS = 4 * 60_000L
        private const val NEVER = -1L
        private val lastWarmUp = AtomicLong(NEVER)
        private val lastDone = AtomicLong(NEVER)
        private val inFlight = AtomicInteger(0)
    }
}
