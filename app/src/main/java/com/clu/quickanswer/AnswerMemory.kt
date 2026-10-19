package com.clu.quickanswer

import com.clu.quickanswer.ocr.ArabicText
import org.json.JSONArray
import org.json.JSONObject

/**
 * The answers to questions seen before, so a question that comes again is answered without the
 * network. Pure Kotlin apart from org.json; the file handling is in [PersistentAnswerMemory].
 *
 * A question is looked up by its text and, for multiple choice, its set of options (not their
 * order: the game shuffles them). The answer is kept as the option's TEXT, never its position, or
 * as the number for a numeric question.
 *
 * - Exact lookup: same normalised question and same normalised options (a numeric question has none).
 * - Fuzzy lookup, when no exact one exists: the same option set (numeric: any numeric question)
 *   and a question that is at least [QUESTION_SIMILARITY] similar, as long as it keeps the same
 *   numbers and the same negation words (see [Entry.guard]); "in 1990" and "in 1991" are
 *   different questions.
 * - At most [capacity] entries; the one least recently used or stored goes first.
 *
 * Thread-safe.
 */
class AnswerMemory(private val capacity: Int = DEFAULT_CAPACITY) {

    /** One remembered question. [key] identifies it for exact lookup and for [remove]. */
    class Entry(val question: String, val options: List<String>, val numeric: Boolean, val answer: String) {
        private val questionKey = keyOf(question)
        private val optionKeys = if (numeric) emptyList() else options.map { keyOf(it) }.sorted()

        /** Same for every ordering of the options. */
        val key: String = (if (numeric) "N" else "M") + SEP + questionKey + SEP + optionKeys.joinToString(SEP.toString())

        /** Questions with a different one of these are never the same question. */
        internal val guard: String = guardOf(question)

        /** Same for entries that offer the same options (all numeric questions share one). */
        internal val optionSet: String = if (numeric) "" else optionKeys.joinToString(SEP.toString())
        internal val questionKeyLength: Int get() = questionKey.length
        internal fun similarityTo(q: String): Double = ArabicText.similarity(questionKey, q)
    }

    /** A remembered answer: the option's text or the number, and the [key] of the entry it came from. */
    class Hit(val answer: String, val key: String, val exact: Boolean)

    // Access order: a lookup that hits makes the entry the most recently used.
    private val entries = LinkedHashMap<String, Entry>(256, 0.75f, true)

    /** Called (on the calling thread, outside the lock) after every change. */
    @Volatile var onChange: (() -> Unit)? = null

    val size: Int @Synchronized get() = entries.size

    /**
     * The remembered answer for a question, or null. [options] are the option texts as read (any
     * order), ignored when [numeric].
     */
    @Synchronized
    fun lookup(question: String, options: List<String>, numeric: Boolean): Hit? {
        val probe = Entry(question, options, numeric, "")
        entries[probe.key]?.let { return Hit(it.answer, it.key, exact = true) }
        val qKey = keyOf(question)
        if (qKey.isEmpty()) return null
        var best: Entry? = null
        var bestSim = QUESTION_SIMILARITY
        for (e in entries.values) {
            if (e.numeric != numeric || e.optionSet != probe.optionSet || e.guard != probe.guard) continue
            // A similarity of 0.92 needs lengths within 8% of each other.
            val longest = maxOf(e.questionKeyLength, qKey.length)
            if (kotlin.math.abs(e.questionKeyLength - qKey.length) > longest * (1 - QUESTION_SIMILARITY)) continue
            val sim = e.similarityTo(qKey)
            if (sim >= bestSim && (best == null || sim > bestSim)) {
                best = e
                bestSim = sim
            }
        }
        val hit = best ?: return null
        entries[hit.key] // touch
        return Hit(hit.answer, hit.key, exact = false)
    }

    /**
     * Remembers [answer] (an option's text, or a number when [numeric]), replacing the entry for
     * the same question. Does nothing for an empty question or answer, or for options that cannot
     * be told apart after normalising (the answer would be ambiguous).
     */
    fun put(question: String, options: List<String>, numeric: Boolean, answer: String) {
        if (keyOf(question).isEmpty() || answer.isBlank()) return
        if (!numeric) {
            val keys = options.map { keyOf(it) }
            if (keys.size < 2 || keys.any { it.isEmpty() } || keys.toSet().size != keys.size) return
            if (keys.none { it == keyOf(answer) }) return
        }
        val entry = Entry(question, if (numeric) emptyList() else options, numeric, answer.trim())
        synchronized(this) {
            entries.remove(entry.key)
            entries[entry.key] = entry
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
        onChange?.invoke()
    }

    /** Forgets the entry with this [Hit.key]. */
    fun remove(key: String) {
        val removed = synchronized(this) { entries.remove(key) != null }
        if (removed) onChange?.invoke()
    }

    fun clear() {
        val had = synchronized(this) { entries.isNotEmpty().also { entries.clear() } }
        if (had) onChange?.invoke()
    }

    /** JSON of all entries, least recently used first, for [restore]. */
    @Synchronized
    fun encode(): String {
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(
                JSONObject().put("q", e.question).put("o", JSONArray(e.options)).put("n", if (e.numeric) 1 else 0)
                    .put("a", e.answer)
            )
        }
        return JSONObject().put("v", 1).put("e", arr).toString()
    }

    /**
     * Replaces the contents with what [encode] produced. Returns false (and leaves the memory
     * empty) when [json] is not readable; single bad entries are skipped. Does not call [onChange].
     */
    @Synchronized
    fun restore(json: String): Boolean {
        entries.clear()
        try {
            val arr = JSONObject(json).getJSONArray("e")
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    val opts = o.getJSONArray("o")
                    val e = Entry(
                        o.getString("q"), List(opts.length()) { opts.getString(it) }, o.optInt("n", 0) == 1, o.getString("a")
                    )
                    entries[e.key] = e
                } catch (_: Exception) {
                    // skip this entry
                }
            }
            while (entries.size > capacity) entries.remove(entries.keys.first())
            return true
        } catch (_: Exception) {
            entries.clear()
            return false
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 5000

        /** Fuzzy lookup: how alike two questions must be (see [ArabicText.similarity]). */
        const val QUESTION_SIMILARITY = 0.92

        private const val SEP = '\u0001'

        /** [text] without diacritics, punctuation, spaces and letter variants, with 0-9 digits: the form compared. */
        fun keyOf(text: String): String = ArabicText.key(AnswerParser.asciiDigits(text))

        private val NEGATIONS = setOf("ليس", "ليست", "ليسوا", "لا", "لم", "لن", "غير", "عدا", "الا", "بدون", "دون")
        private val DIGIT_RUN = Regex("\\d+(?:[.,]\\d+)*")

        /** The numbers and negation words of a question: two questions that differ here are different questions. */
        internal fun guardOf(question: String): String {
            val ascii = AnswerParser.asciiDigits(question)
            val numbers = DIGIT_RUN.findAll(ascii).joinToString(",") { it.value }
            val negations = ArabicText.normalize(ascii).split(' ').filter { it in NEGATIONS }.joinToString(",")
            return "$numbers|$negations"
        }
    }
}
