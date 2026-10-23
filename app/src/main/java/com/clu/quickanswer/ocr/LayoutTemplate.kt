package com.clu.quickanswer.ocr

import org.json.JSONArray
import org.json.JSONObject

/** A rectangle as fractions (0..1) of the capture, so a template works at any capture size. */
data class RelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** In bounds and not empty. False for NaN. */
    val isValid: Boolean get() = left >= 0f && left < right && right <= 1f && top >= 0f && top < bottom && bottom <= 1f

    fun toPx(width: Int, height: Int): PxRect = PxRect(
        Math.round(left.toDouble() * width).toInt(),
        Math.round(top.toDouble() * height).toInt(),
        Math.round(right.toDouble() * width).toInt(),
        Math.round(bottom.toDouble() * height).toInt(),
    )

    companion object {
        fun fromPx(r: PxRect, width: Int, height: Int): RelRect =
            RelRect(r.left.toFloat() / width, r.top.toFloat() / height, r.right.toFloat() / width, r.bottom.toFloat() / height)
    }
}

/** How the answers of a game are laid out: a stack, a grid, or a number typed into a field. */
enum class Arrangement { VERTICAL, GRID, NUMERIC }

/**
 * A learned template is only used for reading once it has worked [LayoutTemplates.PROMOTE_AFTER]
 * times; until then it is a CANDIDATE that may be tried but is not relied on.
 */
enum class TemplateState { CANDIDATE, TRUSTED }

/**
 * What the frame of a region looks like, to tell a screen that merely resembles the game from the
 * game: the [background] (median luma 0..255 of the region's border ring) and the [uniformity]
 * (0..1, the share of that ring close to the background; a ring that crosses a picture or a
 * button's edge is less uniform). The inside is left out on purpose: the text, and what a player
 * has typed, change from screen to screen.
 */
data class RegionSignature(val background: Int, val uniformity: Float)

/**
 * Where one game puts its question and answer boxes, learned from a screen that was read once.
 *
 * @property fingerprint [ScreenFingerprint] of the screen it was learned on: which game.
 * @property signatures one per region, in the order question, options (in order), numeric field:
 *   what a region looked like when learned, to notice a screen that merely resembles the game.
 * @property language "ar", "en" or null when not known.
 * @property misses consecutive failures since the last success.
 * @property source "model" (found by the on-device model) or "manual" (drawn by the user).
 */
data class LayoutTemplate(
    val id: String,
    val packageName: String?,
    val fingerprint: String,
    val arrangement: Arrangement,
    val question: RelRect,
    val options: List<RelRect>,
    val numericField: RelRect?,
    val signatures: List<RegionSignature>,
    val language: String?,
    val state: TemplateState,
    val hits: Int,
    val misses: Int,
    val source: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    /**
     * The signatures of a second look of the same screen, or null: a game that greys its buttons
     * while the other players answer shows the same layout in other colours. [TemplateReader.fits]
     * accepts either look.
     */
    val altSignatures: List<RegionSignature>? = null,
    /**
     * The regions cover the whole area where the game writes, so text is only looked for inside
     * them: whatever is next to a region (a keypad, a note) is not a continuation of its text. True
     * for measured templates; a learned one is not sure of its edges and keeps looking beyond them.
     */
    val bounded: Boolean = false,
    /**
     * Where the game shows the picture of a question that has one, or null when its questions have
     * none. Not one of the [regions]: it has no signature and is never read as text.
     */
    val picture: RelRect? = null,
) {
    /** question, options in order, then the numeric field: the order of [signatures]. */
    val regions: List<RelRect> get() = listOf(question) + options + listOfNotNull(numericField)

    val isValid: Boolean
        get() {
            if (id.isEmpty() || hits < 0 || misses < 0) return false
            if (fingerprint.length != ScreenFingerprint.COLS * ScreenFingerprint.ROWS) return false
            if (!fingerprint.all { it == '.' || it.digitToIntOrNull(16) != null }) return false
            val shapeOk = when (arrangement) {
                Arrangement.VERTICAL, Arrangement.GRID -> options.size in MIN_OPTIONS..MAX_OPTIONS && numericField == null
                Arrangement.NUMERIC -> options.isEmpty() && numericField != null
            }
            if (!shapeOk) return false
            val all = regions
            fun ok(s: List<RegionSignature>) = s.size == all.size && s.all { it.background in 0..255 && it.uniformity in 0f..1f }
            return all.all { it.isValid } && ok(signatures) && (altSignatures == null || ok(altSignatures))
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("pkg", packageName ?: JSONObject.NULL)
        .put("fp", fingerprint)
        .put("arr", arrangement.name)
        .put("q", rectJson(question))
        .put("opts", JSONArray().also { a -> options.forEach { a.put(rectJson(it)) } })
        .put("num", numericField?.let { rectJson(it) } ?: JSONObject.NULL)
        .put("sig", sigJson(signatures))
        .put("lang", language ?: JSONObject.NULL)
        .put("state", state.name)
        .put("hits", hits)
        .put("misses", misses)
        .put("src", source)
        .put("created", createdAt)
        .put("used", lastUsedAt)
        .put("sig2", altSignatures?.let { sigJson(it) } ?: JSONObject.NULL)
        .put("bnd", bounded)

    companion object {
        const val MIN_OPTIONS = 2
        const val MAX_OPTIONS = 8

        private fun rectJson(r: RelRect) = JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())

        private fun sigJson(s: List<RegionSignature>) =
            JSONArray().also { a -> s.forEach { a.put(JSONArray().put(it.background).put(it.uniformity.toDouble())) } }

        private fun sigsOf(a: JSONArray) = List(a.length()) {
            val s = a.getJSONArray(it)
            require(s.length() == 2)
            RegionSignature(s.getInt(0), s.getDouble(1).toFloat())
        }

        private fun rectOf(a: JSONArray): RelRect {
            require(a.length() == 4)
            return RelRect(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
        }

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

        /** The template [toJson] wrote, or null when anything is missing, malformed or invalid. */
        fun fromJson(o: JSONObject): LayoutTemplate? {
            return try {
                val optsJson = o.getJSONArray("opts")
                val sigJson = o.getJSONArray("sig")
                val t = LayoutTemplate(
                    id = o.getString("id"),
                    packageName = o.stringOrNull("pkg"),
                    fingerprint = o.getString("fp"),
                    arrangement = Arrangement.valueOf(o.getString("arr")),
                    question = rectOf(o.getJSONArray("q")),
                    options = List(optsJson.length()) { rectOf(optsJson.getJSONArray(it)) },
                    numericField = if (o.isNull("num")) null else rectOf(o.getJSONArray("num")),
                    signatures = sigsOf(sigJson),
                    language = o.stringOrNull("lang"),
                    state = TemplateState.valueOf(o.getString("state")),
                    hits = o.getInt("hits"),
                    misses = o.getInt("misses"),
                    source = o.getString("src"),
                    createdAt = o.getLong("created"),
                    lastUsedAt = o.getLong("used"),
                    // Both optional: files written before they existed have neither.
                    altSignatures = if (!o.has("sig2") || o.isNull("sig2")) null else sigsOf(o.getJSONArray("sig2")),
                    bounded = o.optBoolean("bnd", false),
                )
                if (t.isValid) t else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
