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
    private const val MAX_OPTION_REPLY_CHARS = 40

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

    // One number: "1,234.5", "-3", or groups separated by a (non-breaking) space ("8 000 000").
    private const val NUMBER_PATTERN =
        "[-\\u2212]?(?:\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+(?:,\\d{3})*)(?:\\.\\d+)?"
    private val NUMBER = Regex("(?<![\\p{L}\\d])$NUMBER_PATTERN")
    private val WHOLE_NUMBER = Regex(NUMBER_PATTERN)

    // The word before the option number in a reply like "الخيار 2" or "Option 2:".
    private const val LABEL_WORDS =
        "option|answer|choice|number|no|الخيار|خيار|الاختيار|اختيار|الإجابة|الاجابة|إجابة|اجابة|الجواب|جواب|رقم"

    // Nothing but the number, which may carry a label word and brackets: "2", "٢", "(2)", "2)", "الخيار 2.".
    private val BARE_OPTION = Regex("^(?:(?:$LABEL_WORDS)\\s*[:：]?\\s*)*[(\\[]?(\\d{1,3})[)\\].:\\-]?$", RegexOption.IGNORE_CASE)

    // The number that labels the option text after it: "3) القرن 2" gives 3.
    private val LABEL_NUMBER = Regex("^\\s*(\\d{1,3})\\s*[)\\].:\\-\\u2013]\\s*\\S")

    // A word after a number that makes it thousands, millions...: "5 ملايين", "300 ألف", "1.5 billion".
    private val SCALE_WORDS = setOf(
        "الف", "الاف", "ألف", "آلاف", "مليون", "ملايين", "مليار", "مليارات", "بليون", "بلايين", "تريليون",
        "thousand", "thousands", "million", "millions", "billion", "billions", "trillion", "k", "bn", "mn",
    )

    /**
     * The 1-based option number a reply names, or null. Only a reply that is nothing but the number
     * counts ("2", "الخيار 2", "٢", "(2)"): a longer one is the option's text or prose, and a number
     * inside it ("ليس 1 بل 2", "القرن 3") must not be taken for the option number.
     */
    fun optionNumber(reply: String, optionCount: Int): Int? {
        val text = asciiDigits(reply.trim())
        if (text.isEmpty() || text.length > MAX_OPTION_REPLY_CHARS) return null
        val n = BARE_OPTION.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (n in 1..optionCount) n else null
    }

    /**
     * The number a short reply gives ("123", "١٢٣", "حوالي 1,234 شخص", "8 000 000") as plain digits (a
     * "." for a decimal part, a leading "-" when negative), or null when there is none, or when it is
     * not safe to read: a scale word follows ("5 ملايين", "300 ألف": the digits alone would be off by
     * a thousand or a million) or the comma is a decimal comma ("1,5").
     */
    fun number(reply: String): String? {
        val text = asciiDigits(reply.trim())
        if (text.isEmpty() || text.length > MAX_REPLY_CHARS) return null
        val m = NUMBER.find(text) ?: return null
        if (m.range.first > MAX_PREFIX_CHARS) return null
        val rest = text.substring(m.range.last + 1)
        if (rest.isNotEmpty() && rest[0].isDigit()) return null
        if (rest.length > 1 && (rest[0] == ',' || rest[0] == '.') && rest[1].isDigit()) return null
        val following = rest.trimStart(' ', '\u00A0', '\u202F').takeWhile { it.isLetter() }.lowercase()
        if (following in SCALE_WORDS) return null
        return normalizedNumber(m.value)
    }

    /** [reply] when it is exactly one number (see [number]) and nothing else, as plain digits, else null. */
    fun numberOnly(reply: String): String? {
        val text = asciiDigits(reply.trim())
        if (text.isEmpty() || text.length > MAX_REPLY_CHARS || !WHOLE_NUMBER.matches(text)) return null
        return normalizedNumber(text)
    }

    // A lone "1." / "1)" / "1-" in front of the only line of a reply.
    private val LONE_FIRST_LABEL = Regex("^\\s*1\\s*[.)\\-]\\s+")

    /**
     * [reply] without a "1." / "1)" / "1-" label when it is ONE line that starts with it, else
     * [reply] unchanged. The model sometimes numbers a single answer ("1. القلب"); the label is not
     * part of the answer. A reply with several lines is a list and is left alone.
     */
    fun stripLoneFirstLabel(reply: String): String {
        val text = asciiDigits(reply.trim())
        if (text.lines().count { it.isNotBlank() } != 1) return reply
        return if (LONE_FIRST_LABEL.containsMatchIn(text)) LONE_FIRST_LABEL.replaceFirst(text, "") else reply
    }

    private fun normalizedNumber(raw: String): String =
        raw.replace(",", "").replace(" ", "").replace("\u00A0", "").replace("\u202F", "").replace('\u2212', '-')

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
     * be mapped (the caller then asks with the picture). A multiple-choice reply is the option
     * number alone, or the option's text (a "2)" label in front is fine). It is declined when
     * - the label and the text name different options ("3) القرن 2" with option 2 "القرن 2"), or
     * - the number alone is also the text of another option (options 4, 6, 5, 3 and the reply "3":
     *   the third option or the option "3"?).
     */
    fun resolve(q: Question, reply: String): Resolved? {
        if (q.numeric) return number(reply)?.let { Resolved(it, null) }
        val n = q.options.size
        optionNumber(reply, n)?.let { number ->
            val valueOfOther = q.options.withIndex().any { (i, o) -> i != number - 1 && AnswerMemory.keyOf(o) == number.toString() }
            return if (valueOfOther) null else option(q, number - 1)
        }
        return matchLabelled(reply, q.options)?.let { option(q, it) }
    }

    /** Index of the option [reply] is the text of, or null when it is none, or its label says another one. */
    private fun matchLabelled(reply: String, options: List<String>): Int? {
        val index = matchOption(reply, options) ?: return null
        val label = LABEL_NUMBER.find(asciiDigits(reply.trim()))?.groupValues?.get(1)?.toIntOrNull()
        return if (label != null && label in 1..options.size && label - 1 != index) null else index
    }

    private fun option(q: Question, index: Int) = Resolved(q.options[index], q.optionRects.getOrNull(index))

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
        // A screenshot's bare position cannot confirm a candidate layout or authorize a choice.
        val index = matchLabelled(reply, q.options)
            ?: matchLabelled(stripLoneFirstLabel(reply), q.options)
        return index?.let { option(q, it) }
    }
}
