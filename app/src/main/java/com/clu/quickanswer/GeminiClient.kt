package com.clu.quickanswer

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One HTTP reply: the status [code] and the [body] read to the end. */
internal class HttpResult(val code: Int, val body: String)

class GeminiClient(apiKey: String, model: String) : Answerer {

    // Pasted keys often carry stray spaces or a newline, which Google rejects with a 400.
    private val apiKey = Prefs.cleanKey(apiKey)
    private val model = Prefs.cleanModel(model).ifEmpty { Prefs.DEFAULT_MODEL }

    private val quota = GeminiRuntime.quota

    @Volatile override var lastModel: String? = null
        private set
    private val thinking = GeminiRuntime.thinking

    /** The image last sent to Gemini (after cropping/scaling), so the user can check it. */
    @Volatile override var lastSent: Bitmap? = null
        private set

    /** The model's answer for the screenshot, or a short Arabic error message. Never throws. */
    override fun answer(bitmap: Bitmap, prompt: ImagePrompt, deadline: AnswerDeadline): Reply {
        return try {
            // Before the JPEG is encoded: a request that cannot be sent in time costs nothing.
            if (deadline.expired) return Reply.error(AnswerDeadline.TIMEOUT_TEXT)
            val prepared = ScreenImage.prepare(bitmap, IMAGE_MAX_SIDE, IMAGE_QUALITY)
                ?: return Reply.error(ScreenImage.BLACK_SCREEN)
            lastSent = prepared.kept
            val b64 = prepared.base64
            race(deadline) { m, mode -> imageBody(m, prompt.text, b64, mode) }.toReply()
        } catch (e: IOException) {
            ioFailure(deadline)
        } catch (e: Exception) {
            Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
    }

    /** A question already read on the device: text only, no image (fewer tokens, faster). */
    override fun answerText(prompt: String, deadline: AnswerDeadline): Reply {
        return try {
            race(deadline, TEXT_PRIMARY_WAIT_MS) { m, mode -> textBody(m, prompt, mode) }.toReply()
        } catch (e: IOException) {
            ioFailure(deadline)
        } catch (e: Exception) {
            Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
    }

    /**
     * A question already read on the device, with its picture (see [Answerer.answerPicture]): the
     * prompt and the picture in ONE request, through the same [race] as the others. [lastSent] is the
     * picture as sent.
     */
    override fun answerPicture(prompt: String, picture: Bitmap, deadline: AnswerDeadline): Reply {
        return try {
            if (deadline.expired) return Reply.error(AnswerDeadline.TIMEOUT_TEXT)
            val prepared = ScreenImage.preparePicture(picture)
            lastSent = prepared.kept
            val b64 = prepared.base64
            race(deadline) { m, mode -> GeminiRequests.pictureBody(m, prompt, b64, mode) }.toReply()
        } catch (e: IOException) {
            ioFailure(deadline)
        } catch (e: Exception) {
            Reply.empty("خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
        }
    }

    /**
     * The screen's layout, for learning a template (see [Answerer.layout]): ONE request to the user's
     * own model, with no fallbacks and no retries (only [ask]'s thinking-mode detour), so learning in
     * the background never takes free quota or time from the answers. [lastSent] and [lastModel] stay
     * as they are. Null on any failure.
     */
    override fun layout(bitmap: Bitmap): LayoutAnswer? {
        return try {
            // Cheap checks first: no JPEG is encoded for a request that cannot be sent.
            if (apiKey.isEmpty() || quota.isBlocked(model)) return null
            val prepared = ScreenImage.prepare(bitmap, IMAGE_MAX_SIDE, IMAGE_QUALITY) ?: return null
            val b64 = prepared.base64
            val res = ask(model, null) { mode -> GeminiRequests.layoutBody(model, b64, mode) }
            if (res.code !in 200..299) return null
            val text = replyText(res.body) ?: return null
            LayoutAnswer(text, prepared.geometry)
        } catch (e: Throwable) {
            // Background work: whatever went wrong (even an OutOfMemoryError on a big capture) only
            // means there is no layout this time.
            null
        }
    }

    /**
     * What [race] ended with: the answer ([ok]), or a short Arabic error. [final] marks errors that
     * would hit any other request too (quota, key, region, out of time), so trying the image instead
     * is pointless.
     */
    private class Outcome(val text: String, val ok: Boolean, val final: Boolean)

    private fun Outcome.toReply(): Reply = when {
        ok -> Reply.answer(text)
        final -> Reply.error(text)
        else -> Reply.empty(text)
    }

    /**
     * A connection that failed. Out of time is not "no connection": the capped socket timeouts and the
     * calls not started because the budget was spent all end here as IOExceptions.
     */
    private fun ioFailure(deadline: AnswerDeadline): Reply =
        Reply.error(if (deadline.expired) AnswerDeadline.TIMEOUT_TEXT else NO_CONNECTION)

    /**
     * Asks the models for one request whose body [body] builds per model and thinking mode, within
     * [deadline]. The race itself (first model alone, fallbacks after a failure or [primaryWaitMs],
     * quota-blocked models skipped, the deadline, cancelling the losers) is [ModelRace]; if the user's
     * own model is blocked the first free fallback starts straight away (see [GeminiModels.plan]).
     * A timeout is final: the image request after a text one would have no time left either.
     */
    private fun race(
        deadline: AnswerDeadline,
        primaryWaitMs: Long = PRIMARY_WAIT_MS,
        body: (String, ThinkingMode) -> String,
    ): Outcome {
        val plan = GeminiModels.plan(model) { quota.isBlocked(it) }
        val result = ModelRace(
            executor = POOL,
            clock = SystemClock::elapsedRealtime,
            first = plan.first,
            fallbacks = plan.fallbacks,
            preferred = model,
            isBlocked = { quota.isBlocked(it) },
            ask = { m -> ask(m, deadline) { mode -> body(m, mode) } },
            primaryWaitMs = primaryWaitMs,
            maxExtraInFlight = MAX_EXTRA_IN_FLIGHT,
            deadline = deadline,
        ).run()
        if (result.timedOut) return Outcome(AnswerDeadline.TIMEOUT_TEXT, ok = false, final = true)
        if (result.ok) lastModel = result.model
        return Outcome(result.text, result.ok, result.final)
    }

    /**
     * Opens (and pools) a TLS connection to Google ahead of the first question, with a cheap
     * ListModels call (not a generateContent call, so it uses no answer quota). At most once per
     * [WARM_UP_INTERVAL_MS] across the process. Blocks, so call it off the main thread.
     *
     * Only warms a cold pool. HTTP/1.1 carries one request at a time per connection, so a warm-up that
     * starts while a connection sits idle in the pool takes that connection, and the real request
     * sent a moment later would have to open a new one. So it does nothing while a request is in
     * flight or when one finished less than [POOL_WARM_MS] ago.
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
            call("$BASE_URL/models?pageSize=1", apiKey, null)
        } catch (e: Exception) {
            // Only a warm-up; the real request reports any problem.
        }
    }

    private fun imageBody(m: String, prompt: String, b64: String, mode: ThinkingMode): String {
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                )
            )
        // Default media resolution on purpose. LOW (~250 tokens) saved only ~0.3 s and was fine for big
        // banner text, but on games with small question text on a busy screen the model could no longer
        // read it and answered "no question".
        return requestBody(m, parts, mode, mediaLow = false)
    }

    private fun textBody(m: String, prompt: String, mode: ThinkingMode): String =
        requestBody(m, JSONArray().put(JSONObject().put("text", prompt)), mode, mediaLow = false)

    private fun requestBody(m: String, parts: JSONArray, mode: ThinkingMode, mediaLow: Boolean): String {
        // Thinking tokens count toward maxOutputTokens, so leave room beyond the short answer.
        val gen = JSONObject()
            .put("temperature", 0)
            .put("maxOutputTokens", GeminiRequests.ANSWER_MAX_OUTPUT_TOKENS)
        if (mediaLow) gen.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
        // Least thinking = fastest answer. The option differs per model, see GeminiModels.
        GeminiModels.thinkingConfig(m, mode)?.let { gen.put("thinkingConfig", it) }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
    }

    /**
     * One model, one answer. Picks the thinking option from what is known about the model; if Google
     * rejects it, steps minimal -> low -> none and remembers what worked, so that detour happens at
     * most once per model. A rejection that names the thinking option is remembered at once (the next
     * mode becomes the one to start with), even if the retry then fails for another reason (429, 503,
     * timeout); a vaguer "invalid argument" is only remembered once a retry succeeds. A 429 blocks the
     * model (and its quota group) for the retryDelay Google gave.
     *
     * Every request, the detour's retries included, is cut to [deadline] (null: none, for background
     * work) and not started at all once it has passed: [call] then throws a SocketTimeoutException.
     */
    private fun ask(m: String, deadline: AnswerDeadline?, body: (ThinkingMode) -> String): HttpResult {
        val known = GeminiModels.thinkingModeFor(m, thinking.snapshot())
        val first = if (known == ThinkingMode.UNKNOWN) ThinkingMode.MINIMAL else known
        var mode = first
        var res = post(m, body(mode), deadline)
        while (isThinkingError(res)) {
            val named = GeminiModels.namesThinkingOption(errorMessage(res.body))
            mode = GeminiModels.nextThinkingMode(m, mode) ?: break
            if (named) thinking.learn(m, mode)
            res = post(m, body(mode), deadline)
        }
        if (res.code in 200..299) {
            quota.clear(m)
            if (mode != first || known == ThinkingMode.UNKNOWN) thinking.learn(m, mode)
        } else if (res.code == 429) {
            quota.block(m, GeminiModels.retryDelayMs(res.body))
        }
        return res
    }

    private fun post(m: String, body: String, deadline: AnswerDeadline?): HttpResult =
        call("$BASE_URL/models/$m:generateContent", apiKey, body, deadline)

    private fun isThinkingError(res: HttpResult): Boolean =
        res.code == 400 && !isKeyOrRegionError(res) && GeminiModels.isThinkingError(errorMessage(res.body))

    /** Result of [fetchModelIds]: the ids on success, otherwise a short Arabic error. */
    class ModelsResult(val ids: List<String>?, val error: String?)

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        // Full detail for small question text (same as Claude); 1024/72 lost readability on busy screens.
        private const val IMAGE_MAX_SIDE = 1280
        private const val IMAGE_QUALITY = 80

        // At most this many fallback models run next to (or instead of) the first one.
        private const val MAX_EXTRA_IN_FLIGHT = 2
        private const val PRIMARY_WAIT_MS = 2500L

        /**
         * A text request (question read on the device) normally answers in 1-2 s; on the owner's phone
         * some took 15-22 s (log, 26 Oct 2026) and the game had moved on. Fallbacks start sooner for
         * them; image requests keep [PRIMARY_WAIT_MS] (they take 2-3 s when healthy).
         */
        private const val TEXT_PRIMARY_WAIT_MS = 1500L

        private const val WARM_UP_INTERVAL_MS = 60_000L

        // How long after a finished request its connection is assumed to still be in the platform's
        // pool (HttpURLConnection keeps idle connections about 5 minutes), so no warm-up is needed.
        private const val POOL_WARM_MS = 4 * 60_000L
        private const val NEVER = -1L
        private val lastWarmUp = AtomicLong(NEVER)
        private val lastDone = AtomicLong(NEVER)
        private val inFlight = AtomicInteger(0)

        private const val MAX_LIST_PAGES = 5

        private val POOL = Executors.newCachedThreadPool { r ->
            Thread(r, "gemini").apply { isDaemon = true }
        }

        // Per HTTP call; cut to what is left of the answer's deadline (see AnswerDeadline.capTimeoutMs).
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        // Internal: ModelRace picks the outcome from these.
        internal const val EMPTY = "لم يتم الحصول على إجابة"
        private const val NO_CONNECTION = "لا يوجد اتصال"
        internal const val ALL_QUOTA = "انتهى الحد المجاني لكل النماذج حاليًا — جرّب لاحقًا أو استخدم Claude"

        /**
         * One request. The body is read to the end and the stream closed on every path, and the
         * connection is not disconnect()ed on success, so the platform can keep the TLS connection
         * for the next call (a cold handshake costs ~0.4 s, a warm one ~0.1 s). No streaming mode on
         * purpose: a buffered body lets the platform retry silently if the server dropped an idle
         * pooled connection.
         *
         * With a [deadline] (an answer's) the timeouts are cut to what is left of it, and nothing is
         * sent once it has passed: a SocketTimeoutException, like a call that ran out of time. Without
         * one (warm-up, model list, background learning) the plain timeouts apply.
         */
        private fun call(url: String, apiKey: String, body: String?, deadline: AnswerDeadline? = null): HttpResult {
            if (deadline != null && deadline.expired) throw SocketTimeoutException("answer deadline passed")
            inFlight.incrementAndGet()
            try {
                return callOnce(url, apiKey, body, deadline)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        private fun callOnce(url: String, apiKey: String, body: String?, deadline: AnswerDeadline?): HttpResult {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = deadline?.capTimeoutMs(CONNECT_TIMEOUT_MS) ?: CONNECT_TIMEOUT_MS
                conn.readTimeout = deadline?.capTimeoutMs(READ_TIMEOUT_MS) ?: READ_TIMEOUT_MS
                conn.setRequestProperty("x-goog-api-key", apiKey)
                if (body != null) {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                } else {
                    conn.requestMethod = "GET"
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                // The body is read and closed: the connection is back in the pool from here on.
                lastDone.set(SystemClock.elapsedRealtime())
                return HttpResult(code, text)
            } catch (e: Exception) {
                // A broken connection must not go back to the pool.
                conn.disconnect()
                throw e
            }
        }

        /**
         * Asks Google which models this key can use and filters the answer down to the ones that can
         * read a screenshot. Blocks: call it on a background thread. Never throws.
         */
        fun fetchModelIds(apiKey: String): ModelsResult {
            val key = Prefs.cleanKey(apiKey)
            return try {
                val ids = LinkedHashSet<String>()
                var token: String? = null
                for (page in 0 until MAX_LIST_PAGES) {
                    var url = "$BASE_URL/models?pageSize=200"
                    token?.let { url += "&pageToken=" + URLEncoder.encode(it, "UTF-8") }
                    val res = call(url, key, null)
                    if (res.code !in 200..299) return ModelsResult(null, errorText(res.code, res.body))
                    ids += GeminiModels.filterListModels(res.body)
                    token = GeminiModels.nextPageToken(res.body)
                    if (token == null) break
                }
                ModelsResult(ids.toList(), null)
            } catch (e: IOException) {
                ModelsResult(null, "لا يوجد اتصال")
            } catch (e: Exception) {
                ModelsResult(null, "خطأ: ${e.javaClass.simpleName} ${e.message?.take(120) ?: ""}".trim())
            }
        }

        /**
         * The text of a 2xx reply [body]: the parts of the first candidate that are not the model's
         * thoughts, joined and trimmed. Null when there is none.
         */
        internal fun replyText(body: String): String? {
            val candidates = JSONObject(body).optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return null
            val partsArr = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: return null
            val sb = StringBuilder()
            for (i in 0 until partsArr.length()) {
                val part = partsArr.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                sb.append(part.optString("text", ""))
            }
            return sb.toString().trim().ifEmpty { null }
        }

        /** What [res] means as text: the model's answer ([EMPTY] if a 2xx reply has none), or a short Arabic error. */
        internal fun handle(res: HttpResult): String {
            if (res.code !in 200..299) return errorText(res.code, res.body)
            return replyText(res.body) ?: EMPTY
        }

        internal fun isKeyOrRegionError(res: HttpResult): Boolean = isKeyOrRegionError(res.code, res.body)

        private fun errorMessage(body: String): String = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            ""
        }

        /** Errors no other model can fix, so falling back would only waste time. */
        private fun isKeyOrRegionError(code: Int, body: String): Boolean {
            // Google answers a bad or deleted key with 401 (older responses used 400 "API key not valid").
            if (code == 401 || code == 403) return true
            val msg = errorMessage(body)
            return code == 400 &&
                (msg.contains("API key", ignoreCase = true) || msg.contains("location", ignoreCase = true))
        }

        private fun errorText(code: Int, body: String): String {
            val msg = errorMessage(body)
            return when {
                msg.contains("location is not supported", ignoreCase = true) ->
                    "Gemini غير متاح في منطقتك، جرّب تشغيل VPN"
                code == 429 -> "تم تجاوز الحد المجاني، انتظر قليلاً"
                code == 503 || code == 500 || code == 504 ->
                    "خوادم Gemini مشغولة الآن، جرّب بعد قليل"
                code == 401 -> "مفتاح API غير صالح"
                (code == 400 || code == 403) &&
                    (msg.contains("API key", ignoreCase = true) ||
                        msg.contains("permission", ignoreCase = true) ||
                        code == 403) -> "مفتاح API غير صالح"
                // Show Google's own reason so unexpected errors can be diagnosed.
                msg.isNotEmpty() -> "خطأ $code: ${msg.take(160)}"
                else -> "خطأ: $code"
            }
        }
    }
}
