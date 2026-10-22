package com.clu.quickanswer.ocr

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The layout templates learned so far. Pure Kotlin apart from org.json; the file handling lives
 * elsewhere.
 *
 * A template is looked up by the [ScreenFingerprint] of the screen (and the package, when known).
 * New ones start as CANDIDATE; [PROMOTE_AFTER] successes make them TRUSTED, and repeated failures
 * take a TRUSTED one back to CANDIDATE and finally remove a CANDIDATE. At most [capacity] are
 * kept; when full the least recently used CANDIDATE goes first, then the least recently used
 * TRUSTED one. Thread-safe.
 */
class LayoutTemplates(private val capacity: Int = 60) {

    private val templates = LinkedHashMap<String, LayoutTemplate>()

    /** Called (on the calling thread, outside the lock) after every change. */
    @Volatile var onChange: (() -> Unit)? = null

    val size: Int @Synchronized get() = templates.size

    /**
     * The templates for this screen: close enough in [ScreenFingerprint.distance] and, when both
     * packages are known, from the same package. TRUSTED first, then nearest, then most hits; at
     * most [MAX_FOUND]. [trustedOnly] leaves the candidates out.
     */
    @Synchronized
    fun find(fingerprint: String, packageName: String?, trustedOnly: Boolean): List<LayoutTemplate> {
        val scored = ArrayList<Pair<LayoutTemplate, Double>>()
        for (t in templates.values) {
            if (trustedOnly && t.state != TemplateState.TRUSTED) continue
            if (!samePackage(t.packageName, packageName)) continue
            val d = ScreenFingerprint.distance(t.fingerprint, fingerprint)
            if (d <= ScreenFingerprint.SAME_GAME_MAX) scored.add(t to d)
        }
        return scored
            .sortedWith(
                compareBy<Pair<LayoutTemplate, Double>> { if (it.first.state == TemplateState.TRUSTED) 0 else 1 }
                    .thenBy { it.second }
                    .thenByDescending { it.first.hits }
            )
            .take(MAX_FOUND)
            .map { it.first }
    }

    /**
     * Stores [t] and returns it, or returns the template already stored for the same thing (same
     * game, arrangement and option count, every edge within [SAME_RECT_TOLERANCE]) without adding
     * anything. A template with the id of a stored one replaces it.
     *
     * @throws IllegalArgumentException when [t] is not [LayoutTemplate.isValid].
     */
    fun add(t: LayoutTemplate): LayoutTemplate {
        require(t.isValid) { "invalid layout template" }
        synchronized(this) {
            for (e in templates.values) {
                if (e.id != t.id && isSame(e, t)) return e
            }
            templates.remove(t.id)
            templates[t.id] = t
            while (templates.size > capacity) {
                val victim = evictionCandidate(except = t.id) ?: break
                templates.remove(victim.id)
            }
        }
        onChange?.invoke()
        return t
    }

    /** [id] worked at [now]: counts as a hit, and enough of them make a CANDIDATE TRUSTED. */
    fun recordSuccess(id: String, now: Long) {
        synchronized(this) {
            val t = templates[id] ?: return
            val hits = t.hits + 1
            templates[id] = t.copy(
                hits = hits,
                misses = 0,
                lastUsedAt = now,
                state = if (t.state == TemplateState.CANDIDATE && hits >= PROMOTE_AFTER) TemplateState.TRUSTED else t.state,
            )
        }
        onChange?.invoke()
    }

    /**
     * [id] failed to read a screen. After [DEMOTE_AFTER] failures in a row a TRUSTED template goes
     * back to CANDIDATE (hits and misses reset) and a CANDIDATE is removed.
     */
    fun recordFailure(id: String, now: Long) {
        synchronized(this) {
            val t = templates[id] ?: return
            val misses = t.misses + 1
            when {
                misses < DEMOTE_AFTER -> templates[id] = t.copy(misses = misses)
                t.state == TemplateState.TRUSTED -> templates[id] = t.copy(state = TemplateState.CANDIDATE, hits = 0, misses = 0)
                else -> templates.remove(id)
            }
        }
        onChange?.invoke()
    }

    @Synchronized
    fun get(id: String): LayoutTemplate? = templates[id]

    @Synchronized
    fun all(): List<LayoutTemplate> = templates.values.toList()

    fun remove(id: String) {
        val removed = synchronized(this) { templates.remove(id) != null }
        if (removed) onChange?.invoke()
    }

    fun clear() {
        val had = synchronized(this) { templates.isNotEmpty().also { templates.clear() } }
        if (had) onChange?.invoke()
    }

    /** JSON of all templates, for [restore]. */
    @Synchronized
    fun encode(): String {
        val arr = JSONArray()
        for (t in templates.values) arr.put(t.toJson())
        return JSONObject().put("version", VERSION).put("templates", arr).toString()
    }

    /**
     * Replaces the contents with what [encode] produced. Entries that are not valid are skipped; a
     * file that cannot be read at all leaves the store empty. Never throws. Does not call [onChange].
     */
    @Synchronized
    fun restore(json: String) {
        templates.clear()
        try {
            val root = JSONObject(json)
            if (root.optInt("version", VERSION) != VERSION) return
            val arr = root.getJSONArray("templates")
            for (i in 0 until arr.length()) {
                val t = try {
                    arr.getJSONObject(i).let { LayoutTemplate.fromJson(it) }
                } catch (_: Exception) {
                    null
                } ?: continue
                templates.remove(t.id)
                templates[t.id] = t
            }
            while (templates.size > capacity) {
                val victim = evictionCandidate(except = null) ?: break
                templates.remove(victim.id)
            }
        } catch (_: Throwable) {
            // Not JSON, or so deeply nested or big that the parser gave up.
            templates.clear()
        }
    }

    /** What [merge] did: [added] templates are new; [skipped] were already known, invalid, or did not fit. */
    class MergeResult(val added: Int, val skipped: Int)

    /**
     * Adds the templates of [json] (what [encode] produced, e.g. on another phone) without removing
     * or changing any of yours. An incoming template is skipped when you have one with its id or
     * the same layout (the rule [add] uses), when it is not valid, or when the store is full (your
     * own templates always stay). Added templates keep their state and hits, with misses reset to 0.
     * Returns null when [json] is not readable (not JSON, another version, no "templates" array).
     * Calls [onChange] once when something was added. Never throws.
     */
    fun merge(json: String): MergeResult? = TODO("issue: bank file with templates")

    /** The least recently used CANDIDATE, else the least recently used TRUSTED one (never [except]). */
    private fun evictionCandidate(except: String?): LayoutTemplate? {
        val others = templates.values.filter { it.id != except }
        return others.filter { it.state == TemplateState.CANDIDATE }.minByOrNull { it.lastUsedAt }
            ?: others.minByOrNull { it.lastUsedAt }
    }

    private fun isSame(a: LayoutTemplate, b: LayoutTemplate): Boolean {
        if (a.arrangement != b.arrangement || a.options.size != b.options.size) return false
        if (!samePackage(a.packageName, b.packageName)) return false
        if (ScreenFingerprint.distance(a.fingerprint, b.fingerprint) > ScreenFingerprint.SAME_GAME_MAX) return false
        val ra = a.regions
        val rb = b.regions
        return ra.size == rb.size && ra.indices.all { near(ra[it], rb[it]) }
    }

    private fun near(a: RelRect, b: RelRect): Boolean =
        abs(a.left - b.left) <= SAME_RECT_TOLERANCE && abs(a.top - b.top) <= SAME_RECT_TOLERANCE &&
            abs(a.right - b.right) <= SAME_RECT_TOLERANCE && abs(a.bottom - b.bottom) <= SAME_RECT_TOLERANCE

    private fun samePackage(a: String?, b: String?): Boolean = a == null || b == null || a == b

    companion object {
        // A failure can be an animation frame, a dialog or a loading screen, not a wrong template,
        // so a trusted template is given up more slowly than it was believed.
        const val PROMOTE_AFTER = 3
        const val DEMOTE_AFTER = 5

        /** Most templates [find] returns. */
        const val MAX_FOUND = 3

        /** Two templates whose rectangles differ by less than this fraction of the capture on every edge are the same layout. */
        const val SAME_RECT_TOLERANCE = 0.03f

        private const val VERSION = 1
    }
}
