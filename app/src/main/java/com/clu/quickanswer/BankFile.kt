package com.clu.quickanswer

import org.json.JSONObject

/**
 * The file "تصدير بنك الأسئلة" writes and "استيراد بنك أسئلة" reads. Since version 2 it carries the
 * learned layout templates next to the answers, so moving to a new phone (or sharing with a friend
 * who plays the same games) keeps both:
 *
 * ```
 * {"format":"clu-bank","version":2,"memory":{...AnswerMemory.encode()...},"templates":{...LayoutTemplates.encode()...}}
 * ```
 *
 * Version 1 files are a bare [AnswerMemory.encode] object (`{"v":1,"e":[...]}`) and still import.
 * Pure Kotlin (org.json only), unit-tested.
 */
object BankFile {
    const val FORMAT = "clu-bank"
    const val VERSION = 2

    /** What a bank file holds; a part is null when the file has none. */
    class Parts(val memoryJson: String?, val templatesJson: String?)

    /**
     * One version-2 file from [memoryJson] (what [AnswerMemory.encode] produced) and [templatesJson]
     * (what [com.clu.quickanswer.ocr.LayoutTemplates.encode] produced), both embedded as JSON objects
     * (not strings), under the keys "memory" and "templates".
     */
    fun encode(memoryJson: String, templatesJson: String): String =
        JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("memory", JSONObject(memoryJson))
            .put("templates", JSONObject(templatesJson))
            .toString()

    /**
     * The parts of [text]. Never throws (garbage, huge or deeply nested input gives null).
     * - A version-2 file ("format" == [FORMAT]): `memoryJson` = the "memory" object as a string, or
     *   null when missing or not an object; `templatesJson` likewise for "templates". Null when both
     *   are missing. A newer "version" than [VERSION] is still read (unknown keys are ignored).
     * - A version-1 file (an object with an "e" array and no "format"): `Parts(text, null)`.
     * - Anything else: null.
     */
    fun decode(text: String): Parts? {
        val root = try {
            JSONObject(text)
        } catch (_: Throwable) {
            // Not JSON, or so deeply nested or big that the parser gave up.
            return null
        }
        if (!root.has("format")) {
            return if (root.optJSONArray("e") != null) Parts(text, null) else null
        }
        if (root.optString("format") != FORMAT) return null
        val memory = root.optJSONObject("memory")?.toString()
        val templates = root.optJSONObject("templates")?.toString()
        if (memory == null && templates == null) return null
        return Parts(memory, templates)
    }
}
