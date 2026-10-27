package com.clu.quickanswer

import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * What a [ModelRace] ended with: the answer ([ok], from [model]), or a short Arabic error in [text].
 * [final] marks errors that would hit any other request too (quota, key, region), so trying the image
 * instead is pointless. [timedOut]: the answer budget ran out first ([text] is empty then; the caller
 * says what the panel shows).
 */
internal class RaceOutcome(
    val text: String,
    val ok: Boolean,
    val final: Boolean,
    val model: String? = null,
    val timedOut: Boolean = false
)

/**
 * Asks Gemini models for one request until one of them answers, and never waits past [deadline].
 *
 * Free-tier models are often overloaded (503) or out of quota (429), and quota is per model. The
 * [first] model goes alone, so a healthy call costs exactly one request (free-tier quota is counted
 * per request). [fallbacks] start only if it fails fast or is still silent after [primaryWaitMs]; then
 * up to [maxExtraInFlight] of them run at once and the first answer wins. A key or region error stops
 * the race at once (no other model can fix it).
 *
 * The deadline bounds every wait, and when the race returns, for any reason, every request still
 * running is cancelled so the losers do not go on using the connection and the quota. No fallback
 * starts once the deadline has passed. [preferred] is the user's own model, whose error is reported
 * first when everything fails (it is not always [first]: a blocked user model is skipped).
 *
 * Pure Kotlin, no android.*: [executor], [clock], [isBlocked] and [ask] are given, so the whole race
 * runs on the JVM in tests. Single use per call of [run]; blocks, so call it off the main thread.
 */
internal class ModelRace(
    private val executor: Executor,
    private val clock: () -> Long,
    private val first: String,
    private val fallbacks: List<String>,
    private val preferred: String,
    private val isBlocked: (String) -> Boolean,
    private val ask: (String) -> HttpResult,
    private val primaryWaitMs: Long,
    private val maxExtraInFlight: Int,
    private val deadline: AnswerDeadline
) {

    fun run(): RaceOutcome {
        val started = clock()
        // What each model did, for the diagnostic log: "model:code:ms" (code 0 = no reply, an exception;
        // "cancel" = still running when the race ended).
        val trace = Collections.synchronizedList(ArrayList<String>())
        val done = ExecutorCompletionService<Pair<String, HttpResult>>(executor)
        // Every request started, to cancel the ones still running on the way out. Only this thread touches it.
        val submitted = ArrayList<Pair<String, Future<*>>>()
        val pending = fallbacks.toMutableList()
        var outstanding = 0
        var primaryInFlight = true
        var fannedOut = false
        var timedOut = false

        fun timed(m: String): Pair<String, HttpResult> {
            val t0 = clock()
            val r = try {
                ask(m)
            } catch (e: Exception) {
                trace.add("$m:0:${clock() - t0}")
                throw e
            }
            trace.add("$m:${r.code}:${clock() - t0}")
            return m to r
        }

        fun start(m: String) {
            submitted += m to done.submit { timed(m) }
            outstanding++
        }

        fun extrasInFlight() = outstanding - if (primaryInFlight) 1 else 0

        fun launchNext(): Boolean {
            // Nothing new once the budget is spent: it could not finish in time, and would only use quota.
            while (pending.isNotEmpty() && !deadline.expired) {
                val m = pending.removeAt(0)
                // A 429 seen since the plan was made may have blocked this one.
                if (isBlocked(m)) continue
                start(m)
                return true
            }
            return false
        }

        fun fanOut() {
            fannedOut = true
            while (extrasInFlight() < maxExtraInFlight && launchNext()) { /* keep launching */ }
        }

        fun timeout(): RaceOutcome {
            timedOut = true
            return RaceOutcome("", ok = false, final = true, timedOut = true)
        }

        try {
            if (deadline.expired) return timeout()
            start(first)
            val errors = mutableListOf<Pair<String, HttpResult>>()
            while (true) {
                if (outstanding == 0) {
                    fanOut()
                    if (outstanding == 0) break
                    continue
                }
                // Both waits are cut to the deadline: the primary's own wait, and the wait for any answer.
                val waitMs = if (fannedOut) {
                    deadline.remainingMs()
                } else {
                    minOf(
                        primaryWaitMs,
                        deadline.remainingMs()
                    )
                }
                val future = done.poll(waitMs, TimeUnit.MILLISECONDS)
                if (future == null) {
                    if (deadline.expired) return timeout()
                    // Woke before the primary's wait was over (the deadline cut it, a clock tick early): wait on.
                    if (!fannedOut && waitMs == primaryWaitMs) fanOut()
                    continue
                }
                outstanding--
                val (m, res) = try {
                    future.get()
                } catch (e: ExecutionException) {
                    // A call that died because its time ran out (its socket timeout is cut to the deadline)
                    // is a timeout, not a lost connection.
                    if (deadline.expired) return timeout()
                    throw e.cause ?: e
                }
                if (m == first) primaryInFlight = false
                if (res.code in 200..299) {
                    val text = GeminiClient.handle(res)
                    if (text !=
                        GeminiClient.EMPTY
                    ) {
                        return RaceOutcome(text, ok = true, final = false, model = m)
                    }
                }
                errors += m to res
                if (GeminiClient.isKeyOrRegionError(res)) {
                    return RaceOutcome("$m: ${GeminiClient.handle(res)}", ok = false, final = true)
                }
                // After the first failure keep up to maxExtraInFlight fallbacks running.
                fanOut()
            }
            // Every model that was asked is out of free quota (404s only mean "no such model").
            val real = errors.filter { it.second.code != 404 }
            if (real.isNotEmpty() && real.all { it.second.code == 429 }) {
                return RaceOutcome(GeminiClient.ALL_QUOTA, ok = false, final = true)
            }
            // Report the most telling error: the user's own model first, never a missing model (404).
            val (m, res) = errors.sortedBy { (em, er) ->
                (if (er.code == 404) 2 else 0) +
                    (if (em == preferred) 0 else 1)
            }
                .first()
            return RaceOutcome("$m: ${GeminiClient.handle(res)}", ok = false, final = false)
        } finally {
            val elapsed = clock() - started
            for ((m, future) in submitted) {
                if (future.isDone) continue
                trace.add("$m:cancel:$elapsed")
                future.cancel(true)
            }
            if (timedOut) trace.add("timeout")
            DiagLog.add(
                "Gemini ${elapsed}ms: " +
                    synchronized(trace) { trace.joinToString(" ") }.ifEmpty { "-" }
            )
        }
    }
}
