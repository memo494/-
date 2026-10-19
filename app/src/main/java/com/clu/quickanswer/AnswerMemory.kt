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
 * - Exact lookup also needs the same numbers (see [guardOf]): "1.5 x 2" and "15 x 2" are different
 *   questions although punctuation is not part of the text compared.
 * - Fuzzy lookup, when no exact one exists: the same option set (numeric: any numeric question)
 *   and a question that is at least [QUESTION_SIMILARITY] similar, as long as it keeps the same
 *   numbers and the same negation words (see [Entry.guard]) and no single word is replaced by a
 *   different one (see [wordsCompatible]): "in 1990" and "in 1991", "the biggest planet" and "the
 *   smallest planet" are different questions. OCR noise is a letter or two, not another word.
 * - At most [capacity] entries; the one least recently used or stored goes first. Nothing that
 *   [isValidEntry] refuses is kept, however it came in (answer, restored file, imported file).
 *
 * Thread-safe.
 */
class AnswerMemory(private val capacity: Int = DEFAULT_CAPACITY) {

    /** One remembered question. [key] identifies it for exact lookup and for [remove]. */
    class Entry(val question: String, val options: List<String>, val numeric: Boolean, val answer: String) {
        private val questionKey = keyOf(question)
        private val optionKeys = if (numeric) emptyList() else options.map { keyOf(it) }.sorted()

        /** Questions with a different one of these are never the same question. */
        internal val guard: String = guardOf(question)

        /** Same for every ordering of the options. */
        val key: String =
            (if (numeric) "N" else "M") + SEP + questionKey + SEP + guard + SEP + optionKeys.joinToString(SEP.toString())

        /** The words of the question (normalised), for [wordsCompatible]. */
        internal val words: List<String> = wordsOf(question)

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
            if (sim >= bestSim && (best == null || sim > bestSim) && wordsCompatible(e.words, probe.words)) {
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
     * the same question. Does nothing for what [isValidEntry] refuses: an empty question or answer,
     * options that cannot be told apart after normalising (the answer would be ambiguous), an
     * answer that is not one of them or, for a numeric question, not a number.
     */
    fun put(question: String, options: List<String>, numeric: Boolean, answer: String) {
        if (!isValidEntry(question, options, numeric, answer)) return
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
     * empty) when [json] is not readable; single bad entries (see [isValidEntry]) are skipped, and
     * at most [maxEntries] are read. Does not call [onChange].
     */
    @Synchronized
    fun restore(json: String, maxEntries: Int = Int.MAX_VALUE): Boolean {
        entries.clear()
        try {
            val arr = JSONObject(json).getJSONArray("e")
            var read = 0
            for (i in 0 until arr.length()) {
                if (read >= maxEntries) break
                try {
                    val o = arr.getJSONObject(i)
                    val opts = o.getJSONArray("o")
                    val numeric = o.optInt("n", 0) == 1
                    val question = o.getString("q")
                    val options = if (numeric) emptyList() else List(opts.length()) { opts.getString(it) }
                    val answer = o.getString("a")
                    if (!isValidEntry(question, options, numeric, answer)) continue
                    val e = Entry(question, options, numeric, answer.trim())
                    entries[e.key] = e
                    read++
                } catch (_: Exception) {
                    // skip this entry
                }
            }
            while (entries.size > capacity) entries.remove(entries.keys.first())
            return true
        } catch (_: Throwable) {
            // Not JSON, or so deeply nested or big that the parser gave up.
            entries.clear()
            return false
        }
    }

    /** What [merge] did: [added] entries are new, [noRoom] more were left out because the memory is full. */
    class MergeResult(val added: Int, val noRoom: Int)

    /**
     * Adds the entries of [json] (what [encode] produced, e.g. a friend's exported memory) without
     * removing anything. An entry for a question already remembered is skipped: your own answer
     * wins. When the memory cannot hold them all, the entries of your own stay and only the most
     * recently used of the incoming ones that fit are added. Returns null when [json] is not readable.
     */
    fun merge(json: String): MergeResult? {
        val incoming = AnswerMemory(Int.MAX_VALUE)
        if (!incoming.restore(json, MAX_IMPORT_ENTRIES)) return null
        var added = 0
        var noRoom = 0
        synchronized(this) {
            val fresh = incoming.entries.values.filter { !entries.containsKey(it.key) }
            val room = maxOf(0, capacity - entries.size)
            val take = if (fresh.size > room) fresh.subList(fresh.size - room, fresh.size) else fresh
            for (e in take) entries[e.key] = e
            added = take.size
            noRoom = fresh.size - take.size
        }
        if (added > 0) onChange?.invoke()
        return MergeResult(added, noRoom)
    }

    companion object {
        const val DEFAULT_CAPACITY = 5000

        /** Fuzzy lookup: how alike two questions must be (see [ArabicText.similarity]). */
        const val QUESTION_SIMILARITY = 0.92

        // Limits of one entry. The game's questions are under 100 characters and have 4 options.
        const val MAX_QUESTION_CHARS = 500
        const val MAX_ANSWER_CHARS = 200
        const val MAX_OPTIONS = 8

        /** An imported file is not read beyond this many bytes, nor beyond this many entries. */
        const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
        const val MAX_IMPORT_ENTRIES = 20_000

        private val NUMERIC_ANSWER = Regex("-?\\d+(?:\\.\\d+)?")

        /**
         * Whether an entry may be kept: a question, a bounded size, and either a number (numeric) or at
         * least two options that differ after normalising, one of which is the answer.
         */
        fun isValidEntry(question: String, options: List<String>, numeric: Boolean, answer: String): Boolean {
            if (question.length > MAX_QUESTION_CHARS || keyOf(question).isEmpty()) return false
            val a = answer.trim()
            if (a.isEmpty() || a.length > MAX_ANSWER_CHARS) return false
            if (numeric) return NUMERIC_ANSWER.matches(AnswerParser.asciiDigits(a))
            if (options.size < 2 || options.size > MAX_OPTIONS || options.any { it.length > MAX_ANSWER_CHARS }) return false
            val keys = options.map { keyOf(it) }
            if (keys.any { it.isEmpty() } || keys.toSet().size != keys.size) return false
            return keys.any { it == keyOf(a) }
        }

        /** The bytes of [input], or null when there are more than [limit] (it is not read further). */
        fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > limit) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /** The normalised words of [question] (digits as 0-9). */
        internal fun wordsOf(question: String): List<String> =
            ArabicText.normalize(AnswerParser.asciiDigits(question)).split(' ').filter { it.isNotEmpty() }

        /**
         * Whether two questions that are alike as a whole could differ only by reading noise: their
         * words can be paired up (a word with its neighbour merged into one, or split in two, is fine)
         * so that every pair is the same word or differs by a letter. How many letters a pair may
         * differ by grows with the word: none up to 3 letters (مصر / قطر, أول / آخر), one up to 8
         * letters (أكبر / أصغر, الفجر / الظهر are 2 apart and refused), two beyond that. A stray
         * one-letter word may be left out on either side.
         */
        internal fun wordsCompatible(a: List<String>, b: List<String>): Boolean {
            val n = a.size
            val m = b.size
            val ok = Array(n + 1) { BooleanArray(m + 1) }
            ok[0][0] = true
            for (i in 0..n) {
                for (j in 0..m) {
                    if (i == 0 && j == 0) continue
                    ok[i][j] = (i > 0 && j > 0 && ok[i - 1][j - 1] && wordClose(a[i - 1], b[j - 1])) ||
                        (i > 1 && j > 0 && ok[i - 2][j - 1] && wordClose(a[i - 2] + a[i - 1], b[j - 1])) ||
                        (i > 0 && j > 1 && ok[i - 1][j - 2] && wordClose(a[i - 1], b[j - 2] + b[j - 1])) ||
                        (i > 0 && a[i - 1].length <= 1 && ok[i - 1][j]) ||
                        (j > 0 && b[j - 1].length <= 1 && ok[i][j - 1])
                }
            }
            return ok[n][m]
        }

        private fun wordClose(x: String, y: String): Boolean {
            val shortest = minOf(x.length, y.length)
            val allowed = when {
                shortest <= 3 -> 0
                shortest <= 8 -> 1
                else -> 2
            }
            return ArabicText.levenshtein(x, y) <= allowed
        }

        private const val SEP = '\u0001'

        /** [text] without diacritics, punctuation, spaces and letter variants, with 0-9 digits: the form compared. */
        fun keyOf(text: String): String = ArabicText.key(AnswerParser.asciiDigits(text))

        private val NEGATIONS = setOf("ليس", "ليست", "ليسوا", "لا", "لم", "لن", "غير", "عدا", "الا", "بدون", "دون")
        private val DIGIT_RUN = Regex("\\d+(?:[.,]\\d+)*")

        // An arithmetic sign between two numbers: "12 - 4", "3 × 4", "8 / 2" (x, X and × are one sign).
        private val OPERATOR = Regex("(?<=\\d)\\s*([-+\\u2212\\u2013\\u00D7\\u00F7*/^=xX])\\s*(?=\\d)")

        /**
         * The numbers, the arithmetic signs between them and the negation words of a question: two
         * questions that differ here are different questions ("12 - 4" and "12 / 4" have the same letters
         * once the punctuation is gone).
         */
        internal fun guardOf(question: String): String {
            val ascii = AnswerParser.asciiDigits(question)
            val numbers = DIGIT_RUN.findAll(ascii).joinToString(";") { it.value }
            val signs = OPERATOR.findAll(ascii).joinToString("") {
                when (val c = it.groupValues[1][0]) {
                    'x', 'X', '\u00D7' -> "*"
                    '\u2212', '\u2013' -> "-"
                    else -> c.toString()
                }
            }
            val negations = ArabicText.normalize(ascii).split(' ').filter { it in NEGATIONS }.joinToString(",")
            return "$numbers|$negations|$signs"
        }
    }
}
