package com.clu.quickanswer

import org.json.JSONObject

/**
 * How a model wants to be told to think. MINIMAL/LOW are Gemini 3.x thinking levels, NONE means
 * "send no thinkingConfig at all" (Gemma), UNKNOWN means we have not found out yet.
 */
enum class ThinkingMode { MINIMAL, LOW, NONE, UNKNOWN }

/** One model we know something about, measured against a free key. */
data class GeminiModel(
    val id: String,
    /** Short name shown in the picker. */
    val label: String,
    /** Arabic note shown after the label (speed, limits, price). */
    val note: String,
    val thinking: ThinkingMode,
    /** Models that share one free-tier quota share a group, so a 429 on one blocks all of them. */
    val quotaGroup: String = id,
    val freeTier: Boolean = true,
    /** Part of the automatic fallback list (see [GeminiModels.fallbackOrder]). */
    val autoFallback: Boolean = false
)

/** A line in the settings picker. */
data class ModelEntry(
    val id: String,
    val label: String,
    val note: String,
    val known: Boolean,
    val freeTier: Boolean
) {
    /** "label — note", plus a marker when the quota tracker says the model is out of quota. */
    fun pickerText(blocked: Boolean): String =
        "$label — $note" + if (blocked) BLOCKED_SUFFIX else ""

    companion object {
        const val BLOCKED_SUFFIX = " (انتهى الحد مؤقتًا)"
    }
}

/** Which model to ask first, and which to fall back to. */
class RequestPlan(val first: String, val fallbacks: List<String>, val forced: Boolean)

/**
 * Pure logic about Gemini models: catalog, fallback order, ListModels filtering, thinking options and
 * retry delays. No Android dependencies, so it is unit tested on the JVM.
 */
object GeminiModels {

    const val DEFAULT_ID = "gemini-3-flash-preview"

    /** Lab figures (free key, minimal thinking) are in the notes. Free models first, paid-only last. */
    val CATALOG: List<GeminiModel> = listOf(
        GeminiModel(
            "gemini-3-flash-preview", "Gemini 3 Flash", "الأسرع (1–2 ث)",
            ThinkingMode.MINIMAL, autoFallback = true
        ),
        GeminiModel(
            "gemini-3.6-flash", "Gemini 3.6 Flash", "سريع، 20 طلب يوميًا",
            ThinkingMode.MINIMAL, autoFallback = true
        ),
        GeminiModel(
            "gemini-3.8-flash", "Gemini 3.8 Flash", "20 طلب يوميًا",
            ThinkingMode.LOW, quotaGroup = "gemini-3.8-flash", autoFallback = true
        ),
        GeminiModel(
            "gemini-flash-latest", "Flash (الأحدث)", "نفس حصة 3.8 Flash",
            ThinkingMode.LOW, quotaGroup = "gemini-3.8-flash"
        ),
        GeminiModel(
            "gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "دقيق لكنه أبطأ (5–10 ث)",
            ThinkingMode.MINIMAL, quotaGroup = "gemini-3.5-flash-lite", autoFallback = true
        ),
        GeminiModel(
            "gemini-flash-lite-latest", "Flash-Lite (الأحدث)", "نفس حصة 3.5 Flash-Lite",
            ThinkingMode.MINIMAL, quotaGroup = "gemini-3.5-flash-lite"
        ),
        GeminiModel(
            "gemini-3.7-flash", "Gemini 3.7 Flash", "5 طلبات في الدقيقة",
            ThinkingMode.LOW, autoFallback = true
        ),
        GeminiModel("gemini-3.5-flash", "Gemini 3.5 Flash", "بطيء جدًا (نحو 15 ث)", ThinkingMode.MINIMAL),
        GeminiModel(
            "gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite", "مشغول غالبًا (503)",
            ThinkingMode.UNKNOWN
        ),
        GeminiModel(
            "gemini-3.1-flash-lite-preview", "Gemini 3.1 Flash-Lite (معاينة)", "مشغول غالبًا (503)",
            ThinkingMode.UNKNOWN
        ),
        GeminiModel("gemma-4-26b-a4b-it", "Gemma 4 26B", "بطيء جدًا (25–90 ث)", ThinkingMode.NONE),
        GeminiModel("gemma-4-31b-it", "Gemma 4 31B", "بطيء جدًا (قد يتجاوز 90 ث)", ThinkingMode.NONE),
        // Paid-only: the free key has a limit of 0 requests on all of these.
        GeminiModel("gemini-pro-latest", "Pro (الأحدث)", "مدفوع فقط", ThinkingMode.LOW, freeTier = false),
        GeminiModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro", "مدفوع فقط", ThinkingMode.LOW, freeTier = false),
        GeminiModel(
            "gemini-omni-flash-preview", "Gemini Omni Flash", "مدفوع فقط",
            ThinkingMode.UNKNOWN, freeTier = false
        ),
        GeminiModel(
            "gemini-omni-1.1-flash", "Gemini Omni 1.1 Flash", "مدفوع فقط",
            ThinkingMode.UNKNOWN, freeTier = false
        ),
        GeminiModel("gemini-4-argon", "Gemini 4 Argon", "مدفوع فقط", ThinkingMode.UNKNOWN, freeTier = false)
    )

    const val UNTESTED_NOTE = "(غير مجرّب)"

    private val byId: Map<String, GeminiModel> = CATALOG.associateBy { it.id }

    fun find(id: String): GeminiModel? = byId[id]

    /** Models sharing a quota share a group; an unknown model is a group of its own. */
    fun quotaGroup(id: String): String = byId[id]?.quotaGroup ?: id

    // ------------------------------------------------------------ saved model migration

    /**
     * The model to use for a saved name. Blank and Gemini 2.x names (closed to new users) become the
     * default. "gemini-3.5-flash" and "gemini-flash-latest" were the app's old defaults (the settings
     * screen saved them on every pause, so they look like user choices) rather than deliberate picks, so
     * they are also replaced, but only until the settings have been saved once with this version
     * ([alreadyMigrated]); after that they are real choices and are kept.
     */
    fun migrateSavedModel(saved: String, alreadyMigrated: Boolean): String = when {
        saved.isBlank() || saved.startsWith("gemini-2.") -> DEFAULT_ID
        !alreadyMigrated && saved in OLD_DEFAULTS -> DEFAULT_ID
        else -> saved
    }

    private val OLD_DEFAULTS = setOf("gemini-3.5-flash", "gemini-flash-latest")

    // ------------------------------------------------------------ fallback order

    /**
     * Models to try after [userModel], in order: the catalog's autoFallback models, skipping the user's
     * own model and any model whose quota group was already taken (by the user's model or an earlier
     * entry). Blocked models are not filtered here, see [plan].
     */
    fun fallbackOrder(userModel: String): List<String> {
        val seen = hashSetOf(quotaGroup(userModel))
        val out = ArrayList<String>()
        for (m in CATALOG) {
            if (!m.autoFallback || m.id == userModel) continue
            if (!seen.add(m.quotaGroup)) continue
            out += m.id
        }
        return out
    }

    /**
     * Which model starts the request and what remains for the fallbacks, given what the quota tracker
     * says is blocked. A blocked user model is skipped (the first free fallback starts at once). If
     * nothing is free the user's model is tried once anyway ([RequestPlan.forced]).
     */
    fun plan(userModel: String, isBlocked: (String) -> Boolean): RequestPlan {
        val open = fallbackOrder(userModel).filterNot(isBlocked)
        return when {
            !isBlocked(userModel) -> RequestPlan(userModel, open, false)
            open.isNotEmpty() -> RequestPlan(open.first(), open.drop(1), false)
            else -> RequestPlan(userModel, emptyList(), true)
        }
    }

    // ------------------------------------------------------------ thinking options

    /**
     * Learned choice first, then the catalog. An id in neither is NONE for Gemma (it takes no
     * thinkingConfig, and in the lab "minimal" on it came back as a 500 that would never trigger the
     * minimal -> low -> none steps), otherwise UNKNOWN.
     */
    fun thinkingModeFor(id: String, learned: Map<String, ThinkingMode>): ThinkingMode =
        learned[id] ?: byId[id]?.thinking
            ?: if (id.startsWith("gemma-")) ThinkingMode.NONE else ThinkingMode.UNKNOWN

    private fun isGemini2(id: String) = id.startsWith("gemini-2.")

    /**
     * The thinkingConfig to send, or null to send none. UNKNOWN is tried as "minimal" first. Gemini
     * 2.x (closed to new users, kept for completeness) takes a token budget instead of a level.
     */
    fun thinkingConfig(id: String, mode: ThinkingMode): JSONObject? = when {
        mode == ThinkingMode.NONE -> null
        isGemini2(id) -> JSONObject().put("thinkingBudget", 0)
        mode == ThinkingMode.LOW -> JSONObject().put("thinkingLevel", "low")
        else -> JSONObject().put("thinkingLevel", "minimal")
    }

    /**
     * What to try after [mode] was rejected: minimal, then low, then nothing. Null when nothing is
     * left. Gemini 2.x has no levels, so it goes straight to nothing.
     */
    fun nextThinkingMode(id: String, mode: ThinkingMode): ThinkingMode? = when (mode) {
        ThinkingMode.UNKNOWN, ThinkingMode.MINIMAL ->
            if (isGemini2(id)) ThinkingMode.NONE else ThinkingMode.LOW
        ThinkingMode.LOW -> ThinkingMode.NONE
        ThinkingMode.NONE -> null
    }

    /** True when a 400's message names the thinking option itself (thinking, level or budget). */
    fun namesThinkingOption(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("thinking") || m.contains("level") || m.contains("budget")
    }

    /**
     * True when a 400 is about the thinking option: it names it ([namesThinkingOption]), or it is
     * Google's bare "invalid argument" (some models reject thinkingBudget that way).
     */
    fun isThinkingError(message: String): Boolean =
        namesThinkingOption(message) || message.lowercase().contains("invalid argument")

    // ------------------------------------------------------------ learned thinking, persisted

    fun encodeLearned(map: Map<String, ThinkingMode>): String =
        map.entries.filter { it.value != ThinkingMode.UNKNOWN }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value.name}" }

    fun decodeLearned(s: String): Map<String, ThinkingMode> {
        val out = LinkedHashMap<String, ThinkingMode>()
        for (part in s.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val mode = try {
                ThinkingMode.valueOf(part.substring(i + 1).trim())
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (mode != ThinkingMode.UNKNOWN) out[part.substring(0, i).trim()] = mode
        }
        return out
    }

    // ------------------------------------------------------------ ListModels

    private val DROP_TERMS = listOf(
        "tts", "image", "embedding", "live", "robotics", "computer-use", "deep-research",
        "antigravity", "lyria", "transcribe", "customtools", "nano-banana", "aqa"
    )

    /**
     * Ids of the models in a ListModels response that can answer a screenshot: generateContent,
     * gemini-* or gemma-*, and not an audio/image/embedding/live/agent/legacy variant. Server order,
     * no duplicates. Returns an empty list for anything unparseable.
     */
    fun filterListModels(json: String): List<String> {
        val models = try {
            JSONObject(json).optJSONArray("models")
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            val id = m.optString("name", "").removePrefix("models/")
            if (!(id.startsWith("gemini-") || id.startsWith("gemma-"))) continue
            val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
            var canGenerate = false
            for (j in 0 until methods.length()) {
                if (methods.optString(j) == "generateContent") canGenerate = true
            }
            if (!canGenerate) continue
            if (DROP_TERMS.any { id.contains(it) }) continue
            if (id.startsWith("gemini-2.") || id.startsWith("gemini-1.")) continue
            out += id
        }
        return out.toList()
    }

    /** The page token of a ListModels response, if there is another page. */
    fun nextPageToken(json: String): String? = try {
        JSONObject(json).optString("nextPageToken", "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** The picker entry for one id: its catalog entry, or an untested one. */
    fun entryFor(id: String): ModelEntry {
        val m = byId[id] ?: return ModelEntry(id, id, UNTESTED_NOTE, known = false, freeTier = true)
        return ModelEntry(m.id, m.label, m.note, known = true, freeTier = m.freeTier)
    }

    /**
     * Picker entries for the models a ListModels call returned ([available], from
     * [filterListModels]): catalog models in catalog order (free first, paid-only last), then the
     * rest alphabetically, marked untested. Catalog models the server did not list are left out.
     * With no list yet (never refreshed) the whole catalog is shown.
     */
    fun mergeWithCatalog(available: List<String>): List<ModelEntry> {
        if (available.isEmpty()) return CATALOG.map { entryFor(it.id) }
        val have = available.toSet()
        val known = CATALOG.filter { it.id in have }.map { entryFor(it.id) }
        val extras = available.filter { it !in byId }.distinct().sorted().map { entryFor(it) }
        return known + extras
    }

    // ------------------------------------------------------------ 429 retry delay

    /**
     * The delay in ms from the google.rpc.RetryInfo detail of an error body ("5s", "28945s",
     * "1.5s"), or null when there is none.
     */
    fun retryDelayMs(body: String): Long? {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }
        val details = (root.optJSONObject("error") ?: root).optJSONArray("details") ?: return null
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            if (!d.optString("@type", "").endsWith("RetryInfo")) continue
            return parseDurationMs(d.optString("retryDelay", ""))
        }
        return null
    }

    private val DURATION = Regex("""^\s*(\d+(?:\.\d+)?)s\s*$""")

    /** Protobuf JSON duration ("5s", "1.5s") to ms, rounded up; null if not in that form. */
    fun parseDurationMs(s: String): Long? {
        val m = DURATION.matchEntire(s) ?: return null
        return java.math.BigDecimal(m.groupValues[1]).multiply(java.math.BigDecimal(1000))
            .setScale(0, java.math.RoundingMode.CEILING).toLong()
    }
}
