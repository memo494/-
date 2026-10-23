package com.clu.quickanswer

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * Where an answer came from. [FAILED] is every question that got no answer: an error, "no question
 * on screen", or a reply that could not be used. The names are written to the stats file, so a
 * value must never be renamed (a new one may be added).
 */
enum class AnswerSource { MEMORY, CALCULATOR, TEXT, PICTURE, IMAGE, FAILED }

/**
 * How many questions each [AnswerSource] solved and how long they took, for today and for ever, so
 * the owner can see how much the cheap steps (memory, calculator, on-device reading) are doing.
 * Pure Kotlin apart from org.json; the file handling is in [StatsStore].
 *
 * - Per day (the caller's "yyyy-MM-dd" string, so tests need no clock): a count and the total time
 *   per source. Only the last [MAX_DAYS] days are kept, which bounds the file.
 * - All-time totals are kept apart from the days, so dropping an old day does not lose its count.
 * - A record whose day is not "yyyy-MM-dd" is ignored altogether: day order is string order, and a
 *   day that does not sort would break the cap.
 *
 * Thread-safe.
 */
class AnswerStats {

    /**
     * The numbers for one period. [count] has an entry for every [AnswerSource] (0 when none), so a
     * reader never meets null; [total] adds them up, failures included. [averageMillis] has an entry
     * only for a source with at least one answer: no answers, no average.
     */
    class Summary(val count: Map<AnswerSource, Int>, val averageMillis: Map<AnswerSource, Long>) {
        val total: Int get() = count.values.sum()
    }

    /** Count and total time per source (indexed by [AnswerSource.ordinal]). */
    private class Tally {
        val count = IntArray(SOURCES.size)
        val millis = LongArray(SOURCES.size)

        fun hasData() = count.any { it > 0 }

        fun add(i: Int, ms: Long) {
            // Saturate rather than overflow: a corrupt file must not turn the totals negative.
            if (count[i] >= MAX_COUNT) return
            count[i]++
            millis[i] += ms
        }

        fun summary(): Summary {
            val counts = HashMap<AnswerSource, Int>()
            val averages = HashMap<AnswerSource, Long>()
            for (s in SOURCES) {
                val n = count[s.ordinal]
                counts[s] = n
                // Rounded to the nearest millisecond (half up).
                if (n > 0) averages[s] = (millis[s.ordinal] + n / 2) / n
            }
            return Summary(counts, averages)
        }
    }

    // Day strings sort like dates, so the first key is the oldest day.
    private val days = TreeMap<String, Tally>()
    private var all = Tally()

    /** Called (on the calling thread, outside the lock) after every change. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Counts one question solved by [source] in [millis] (negative counts as 0) on [day]. */
    fun record(source: AnswerSource, millis: Long, day: String) {
        if (!DAY.matches(day)) return
        val ms = millis.coerceAtLeast(0)
        synchronized(this) {
            all.add(source.ordinal, ms)
            days.getOrPut(day) { Tally() }.add(source.ordinal, ms)
            while (days.size > MAX_DAYS) days.pollFirstEntry()
        }
        onChange?.invoke()
    }

    /** The numbers of [day]; empty when nothing was recorded then (or it is older than [MAX_DAYS] days). */
    @Synchronized
    fun today(day: String): Summary = (days[day] ?: Tally()).summary()

    /** Everything ever recorded, including days no longer kept. */
    @Synchronized
    fun allTime(): Summary = all.summary()

    fun clear() {
        val had = synchronized(this) { (all.hasData() || days.isNotEmpty()).also { days.clear(); all = Tally() } }
        if (had) onChange?.invoke()
    }

    /** JSON of the totals and the kept days, for [restore]. */
    @Synchronized
    fun encode(): String {
        val d = JSONObject()
        for ((day, tally) in days) d.put(day, tallyJson(tally))
        return JSONObject().put("v", 1).put("all", tallyJson(all)).put("days", d).toString()
    }

    private fun tallyJson(t: Tally): JSONObject {
        val o = JSONObject()
        for (s in SOURCES) {
            if (t.count[s.ordinal] > 0) o.put(s.name, JSONArray().put(t.count[s.ordinal]).put(t.millis[s.ordinal]))
        }
        return o
    }

    /**
     * Replaces the contents with what [encode] produced. Returns false (and leaves the stats empty)
     * when [json] is not readable. A single bad part (unknown source, negative or absurd number, bad
     * day) is skipped; only the last [MAX_DAYS] days are kept. Does not call [onChange].
     */
    @Synchronized
    fun restore(json: String): Boolean {
        days.clear()
        all = Tally()
        try {
            val root = JSONObject(json)
            val allJson = root.getJSONObject("all")
            val daysJson = root.getJSONObject("days")
            val newAll = readTally(allJson)
            val newDays = TreeMap<String, Tally>()
            for (day in daysJson.keys()) {
                if (!DAY.matches(day)) continue
                val tally = readTally(daysJson.optJSONObject(day) ?: continue)
                if (tally.hasData()) newDays[day] = tally
            }
            while (newDays.size > MAX_DAYS) newDays.pollFirstEntry()
            all = newAll
            days.putAll(newDays)
            return true
        } catch (_: Throwable) {
            // Not JSON, or not shaped like ours, or so deeply nested that the parser gave up.
            days.clear()
            all = Tally()
            return false
        }
    }

    /** The valid "SOURCE": [count, millis] pairs of [o]; everything else is skipped. */
    private fun readTally(o: JSONObject): Tally {
        val t = Tally()
        for (name in o.keys()) {
            val source = SOURCES.firstOrNull { it.name == name } ?: continue
            val pair = o.optJSONArray(name) ?: continue
            if (pair.length() != 2) continue
            val n = (pair.opt(0) as? Number)?.toLong() ?: continue
            val ms = (pair.opt(1) as? Number)?.toLong() ?: continue
            if (n !in 1..MAX_COUNT.toLong() || ms !in 0..MAX_TOTAL_MILLIS) continue
            t.count[source.ordinal] = n.toInt()
            t.millis[source.ordinal] = ms
        }
        return t
    }

    companion object {
        /** Days kept (about a month): enough to see a trend, and the file stays a few kilobytes. */
        const val MAX_DAYS = 30

        /**
         * Largest count of one source that is kept or believed. Six sources of this size still fit an
         * Int, so [Summary.total] cannot overflow; nobody answers a hundred million questions.
         */
        private const val MAX_COUNT = 100_000_000

        /** Largest total time of one source in a file we believe (about 31,700 years of answering). */
        private const val MAX_TOTAL_MILLIS = 1_000_000_000_000_000L

        private val SOURCES = AnswerSource.values()
        private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

        /** Sources in the order shown, with their names for the user. */
        private val LABELS = mapOf(
            AnswerSource.MEMORY to "ذاكرة",
            AnswerSource.CALCULATOR to "حاسبة",
            AnswerSource.TEXT to "نص",
            AnswerSource.PICTURE to "صورة مقصوصة",
            AnswerSource.IMAGE to "صورة كاملة",
            AnswerSource.FAILED to "فشل",
        )

        /**
         * The text of the statistics section: today's count per source (zeros too, so the line has
         * the same shape every day), today's average time per source, and the all-time total.
         * No average for [AnswerSource.FAILED]: how long a failure took (a timeout, no network) says
         * nothing about how fast answers are. Nothing recorded at all gets one short line.
         */
        fun describe(today: Summary, all: Summary): String {
            if (all.total == 0 && today.total == 0) return "لا توجد إحصائيات بعد"
            val lines = ArrayList<String>()
            if (today.total == 0) {
                lines.add("اليوم: لا أسئلة بعد")
            } else {
                val counts = SOURCES.joinToString("، ") { s ->
                    val label = if (s == AnswerSource.MEMORY) "${LABELS[s]} ⚡" else "${LABELS[s]}"
                    "$label ${today.count[s] ?: 0}"
                }
                lines.add("اليوم: ${today.total} سؤال — $counts")
                val times = SOURCES.filter { it != AnswerSource.FAILED && (today.count[it] ?: 0) > 0 }
                    .mapNotNull { s -> today.averageMillis[s]?.let { "${LABELS[s]} ${seconds(it)} ث" } }
                if (times.isNotEmpty()) lines.add("متوسط الوقت: ${times.joinToString("، ")}")
            }
            lines.add("الكل: ${all.total} سؤال")
            return lines.joinToString("\n")
        }

        /** [millis] in seconds with one decimal ("1.2"), rounded half up, without locale surprises. */
        private fun seconds(millis: Long): String {
            val tenths = (millis + 50) / 100
            return "${tenths / 10}.${tenths % 10}"
        }
    }
}
