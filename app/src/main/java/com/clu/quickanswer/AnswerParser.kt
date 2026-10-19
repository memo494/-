package com.clu.quickanswer

import com.clu.quickanswer.ocr.ArabicText
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import kotlin.math.abs

/**
 * Turns a model's reply into an answer for a [Question]: the text reply to [QuestionPrompt] (an
 * option number, or digits), and the answer to an image request when it is matched against the
 * options that were read. Pure Kotlin.
 */
object AnswerParser {

    /** An answer mapped onto the question. */
    class Resolved(
        /** What the bar shows and what the memory stores: the option's text, or the number. */
        val answer: String,
        /** The whole button of the chosen option (screen pixels), null for a number. */
        val rect: PxRect?,
    )

    /** An image answer counts as one of the options when its text is at least this similar. */
    const val OPTION_SIMILARITY = 0.8

    // A reply longer than this, or whose number comes late, is prose ("I can't tell, but 3 of ...").
    private const val MAX_REPLY_CHARS = 80
    private const val MAX_PREFIX_CHARS = 20

    /**
     * [s] with Arabic-Indic and Persian digits as 0-9, the Arabic thousands separator as "," and the
     * Arabic decimal separator as ".".
     */
    fun asciiDigits(s: String): String {
        if (s.none { it in '٠'..'٩' || it in '۰'..'۹' || it == '٫' || it == '٬' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '٠'..'٩' -> '0' + (c - '٠')
                    in '۰'..'۹' -> '0' + (c - '۰')
                    '٬' -> ','
                    '٫' -> '.'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private val INTEGER = Regex("\\d+")
    private val NUMBER = Regex("(?<![\\p{L}\\d])[-\\u2212]?\\d+(?:,\\d{3})*(?:\\.\\d+)?")

    /**
     * The 1-based option number a short reply names ("2", "الخيار 2", "2) سورة طه", "٢"), or null
     * when there is none, it is not within 1..[optionCount], or the reply is prose.
     */
    fun optionNumber(reply: String, optionCount: Int): Int? {
        val text = asciiDigits(reply.trim())
        if (text.isEmpty() || text.length > MAX_REPLY_CHARS) return null
        val m = INTEGER.find(text) ?: return null
        if (m.range.first > MAX_PREFIX_CHARS) return null
        val n = m.value.toIntOrNull() ?: return null
        return if (n in 1..optionCount) n else null
    }

    /**
     * The number a short reply gives ("123", "١٢٣", "حوالي 1,234 شخص") as plain digits (a "." for a
     * decimal part, a leading "-" when negative), or null when there is none.
     */
    fun number(reply: String): String? {
        val text = asciiDigits(reply.trim())
        if (text.isEmpty() || text.length > MAX_REPLY_CHARS) return null
        val m = NUMBER.find(text) ?: return null
        if (m.range.first > MAX_PREFIX_CHARS) return null
        return m.value.replace(",", "").replace('\u2212', '-')
    }

    // "2) ", "2- ", "٢.", "ب) ": a leading option label.
    private val LABEL = Regex("^\\s*(?:\\d+|\\p{L})\\s*[)\\].:\\-\\u2013]\\s*")

    /**
     * Index of the option [text] is (after dropping a leading label such as "2)"), or null if no
     * option is at least [minSimilarity] similar or two options tie for it.
     */
    fun matchOption(text: String, options: List<String>, minSimilarity: Double = OPTION_SIMILARITY): Int? {
        if (options.isEmpty()) return null
        val candidates = listOf(asciiDigits(text.trim()), asciiDigits(text.trim()).replace(LABEL, ""))
        var best = -1
        var bestSim = 0.0
        var tied = false
        for ((i, option) in options.withIndex()) {
            val sim = candidates.maxOf { ArabicText.similarity(it, asciiDigits(option)) }
            if (sim > bestSim + 1e-9) {
                best = i; bestSim = sim; tied = false
            } else if (abs(sim - bestSim) <= 1e-9 && sim > 0) {
                tied = true
            }
        }
        return if (best >= 0 && bestSim >= minSimilarity && !tied) best else null
    }

    /**
     * The text reply [reply] to [QuestionPrompt.build] of [q], mapped onto it, or null if it cannot
     * be mapped. A multiple-choice reply that is the option's text instead of its number also maps.
     */
    fun resolve(q: Question, reply: String): Resolved? {
        if (q.numeric) return number(reply)?.let { Resolved(it, null) }
        val index = (optionNumber(reply, q.options.size)?.minus(1)) ?: matchOption(reply, q.options) ?: return null
        return Resolved(q.options[index], q.optionRects.getOrNull(index))
    }

    /**
     * An answer taken from the memory, mapped onto [q]: the option [stored] names (the options may
     * have been shuffled since), or the number. Null if no option is that text.
     */
    fun resolveStored(q: Question, stored: String): Resolved? {
        if (q.numeric) return Resolved(stored, null)
        val index = indexOfOption(q.options, stored) ?: return null
        return Resolved(q.options[index], q.optionRects.getOrNull(index))
    }

    /** The option whose text is [text]: the same normalised text first, else the closest at [OPTION_SIMILARITY]. */
    fun indexOfOption(options: List<String>, text: String): Int? {
        val key = AnswerMemory.keyOf(text)
        val exact = options.indexOfFirst { AnswerMemory.keyOf(it) == key }
        if (exact >= 0) return exact
        return matchOption(text, options)
    }

    /**
     * An image answer that is one of the options that were read: the option and its button, so the
     * box can still be drawn after the image path. Null for a number, a different text or no options.
     */
    fun resolveImageAnswer(q: Question, reply: String): Resolved? {
        if (q.numeric) return null
        val index = matchOption(reply, q.options) ?: return null
        return Resolved(q.options[index], q.optionRects.getOrNull(index))
    }
}
