package com.clu.quickanswer

import com.clu.quickanswer.ocr.ArabicText
import com.clu.quickanswer.ocr.Question
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * Answers an arithmetic question ("كم يساوي 12 × 4 ؟", "٧ - ١٠", "5 زائد 6") on the device: the
 * cheapest rung of the answer ladder, instant and free, and exact where a model can slip on
 * arithmetic. Pure Kotlin.
 *
 * It fails closed. The text must be ONE expression, optionally wrapped in framing words; any other
 * word, symbol or shape ("كم عدد أيام الأسبوع؟", "في أي سنة 1975", "3 + 4 = 7") gives null and the
 * question goes on to the memory and the model as before. A wrong answer shown instantly is worse
 * than a slow right one, so every doubt declines.
 */
object Calculator {

    /** Longer text is a sentence, not a sum (a quiz question about arithmetic is short). */
    private const val MAX_CHARS = 80

    /** More numbers than this is not a quiz sum, and more likely an OCR run-on. */
    private const val MAX_NUMBERS = 12

    /**
     * Largest value (also for every partial result) that is answered: 2^53, the last point up to
     * which a Double holds every integer exactly, so the digits shown are always right. Bigger
     * results go to the model rather than show digits that may be off.
     */
    private const val MAX_MAGNITUDE = 9.007199254740992E15

    /** Decimals shown ("10 / 3" gives 3.3333): what a quiz option can reasonably spell out. */
    private const val DECIMALS = 4

    /** A result this close to a whole number is that number ("0.1 + 0.2 - 0.3" leaves 5.5e-17). */
    private const val WHOLE_TOLERANCE = 1e-9

    /** An option is the answer when its number is this close to it (the answer has at most 4 decimals). */
    private const val OPTION_TOLERANCE = 1e-9

    /** Dots (a trailing "..."), the question mark, "=" and ":" and spaces at the end of the text carry no meaning. */
    private val TRAILING_FILLER = Regex("[.\\u2026\\u061F?=:\\s]+$")

    // ---- words ----

    private fun words(phrase: String): List<String> = ArabicText.normalize(phrase).split(' ')

    /**
     * Framing around the expression, matched as whole words only at the start or the end of the
     * text (never inside the expression), longest first. Written as people write them: [words]
     * folds hamza and teh marbuta, so "أوجد" and "اوجد", "نتيجة" and "نتيجه" are the same.
     * "ما نتيجة" and the two are additions to the plain "ما هي نتيجة" for the same reason.
     */
    private val FRAMING: List<List<String>> = listOf(
        "كم يساوي", "كم ناتج", "ما ناتج", "ما هو ناتج", "ما هي نتيجة", "ما نتيجة", "ما حاصل", "ما هو حاصل",
        "احسب", "أوجد", "ناتج", "يساوي",
    ).map(::words).sortedByDescending { it.size }

    private class OperatorWord(val words: List<String>, val symbol: Char)

    private fun operatorWords(symbol: Char, vararg phrases: String) = phrases.map { OperatorWord(words(it), symbol) }

    /**
     * Operators spelled as words, matched anywhere between numbers, longest first. "x" is the Latin
     * letter used as a times sign ("12 x 4"). "في" is not here: it means times only between two
     * numbers, see [resolveWords].
     */
    private val OPERATOR_WORDS: List<OperatorWord> = (
        operatorWords('+', "زائد") +
            operatorWords('-', "ناقص") +
            operatorWords('*', "ضرب", "مضروب في", "ضرب في", "x") +
            operatorWords('/', "على", "مقسوم على", "تقسيم", "تقسيم على")
        ).sortedByDescending { it.words.size }

    /** "في" ("in"), which is also "times" ("6 في 7"), but only between two numbers: "في أي سنة 1975" is not a sum. */
    private val TIMES_IN = words("في").single()

    // ---- tokens ----

    private sealed interface Token
    private class Num(val value: Double) : Token
    private class Word(val text: String) : Token
    private class Op(val symbol: Char) : Token // + - * / ^
    private object Open : Token
    private object Close : Token

    private fun isWordChar(c: Char) = c.isLetter() || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    /** The tokens of [text], or null when it has a character that is not part of an expression or its framing. */
    private fun lex(text: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c in '0'..'9' -> {
                    var j = i
                    while (j < text.length && text[j] in '0'..'9') j++
                    if (j + 1 < text.length && text[j] == '.' && text[j + 1] in '0'..'9') {
                        j++
                        while (j < text.length && text[j] in '0'..'9') j++
                    }
                    out += Num(text.substring(i, j).toDouble())
                    i = j
                    continue
                }
                isWordChar(c) -> {
                    var j = i
                    while (j < text.length && isWordChar(text[j])) j++
                    val word = ArabicText.normalize(text.substring(i, j))
                    if (word.isNotEmpty()) out += Word(word)
                    i = j
                    continue
                }
                // Spaces and invisible marks (the right-to-left mark Arabic text often carries).
                c.isWhitespace() || Character.getType(c) == Character.FORMAT.toInt() -> {}
                else -> when (c) {
                    '+' -> out += Op('+')
                    '-', '−', '–' -> out += Op('-') // hyphen, minus sign, en dash (OCR reads a minus as either)
                    '*', '×' -> out += Op('*')
                    '/', '÷' -> out += Op('/')
                    '^' -> out += Op('^')
                    '(' -> out += Open
                    ')' -> out += Close
                    '؟', '?', '=', ':' -> {} // question mark, "=", ":" frame the question
                    else -> return null
                }
            }
            i++
        }
        return out
    }

    private fun matchesAt(tokens: List<Token>, at: Int, phrase: List<String>): Boolean =
        at >= 0 && at + phrase.size <= tokens.size && phrase.indices.all { (tokens[at + it] as? Word)?.text == phrase[it] }

    /** [tokens] without the [FRAMING] phrases at its start and its end. */
    private fun stripFraming(tokens: List<Token>): List<Token> {
        var t = tokens
        while (true) {
            val p = FRAMING.firstOrNull { matchesAt(t, 0, it) } ?: break
            t = t.subList(p.size, t.size)
        }
        while (true) {
            val p = FRAMING.firstOrNull { matchesAt(t, t.size - it.size, it) } ?: break
            t = t.subList(0, t.size - p.size)
        }
        return t
    }

    /** [tokens] with the operator words turned into operators, or null if another word is left. */
    private fun resolveWords(tokens: List<Token>): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t !is Word) {
                out += t
                i++
                continue
            }
            val phrase = OPERATOR_WORDS.firstOrNull { matchesAt(tokens, i, it.words) }
            if (phrase != null) {
                out += Op(phrase.symbol)
                i += phrase.words.size
                continue
            }
            val afterNumber = out.lastOrNull().let { it is Num || it === Close }
            val beforeNumber = tokens.getOrNull(i + 1).let { it is Num || it === Open }
            if (t.text == TIMES_IN && afterNumber && beforeNumber) {
                out += Op('*')
                i++
                continue
            }
            return null
        }
        return out
    }

    // ---- evaluation ----

    /** [v] unless it is not a number or too big to be exact (see [MAX_MAGNITUDE]). */
    private fun checked(v: Double): Double? = v.takeIf { it.isFinite() && abs(it) <= MAX_MAGNITUDE }

    /**
     * Recursive descent over the tokens: sum (+ -) of products (* /) of signed powers, where ^ binds
     * tighter than a sign on its left ("-2 ^ 2" is -4) and associates to the right ("2 ^ 3 ^ 2" is
     * 512). Every function returns null when the tokens are not an expression or a step has no
     * usable value (division by zero, too big).
     */
    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        /** How many binary operators were applied: none means one bare number, which is no sum. */
        private var binary = 0

        /** The value of all [tokens], or null when they are not exactly one expression with an operator. */
        fun parse(): Double? {
            val v = expr() ?: return null
            return if (pos == tokens.size && binary > 0) v else null
        }

        private fun peekOp(vararg symbols: Char): Char? =
            (tokens.getOrNull(pos) as? Op)?.symbol?.takeIf { it in symbols }

        private fun expr(): Double? {
            var v = term() ?: return null
            while (true) {
                val op = peekOp('+', '-') ?: return v
                pos++
                binary++
                val r = term() ?: return null
                v = checked(if (op == '+') v + r else v - r) ?: return null
            }
        }

        private fun term(): Double? {
            var v = unary() ?: return null
            while (true) {
                val op = peekOp('*', '/') ?: return v
                pos++
                binary++
                val r = unary() ?: return null
                if (op == '/' && r == 0.0) return null
                v = checked(if (op == '*') v * r else v / r) ?: return null
            }
        }

        private fun unary(): Double? {
            if (peekOp('-') == null) return power()
            pos++
            return unary()?.let { -it }
        }

        private fun power(): Double? {
            val base = atom() ?: return null
            if (peekOp('^') == null) return base
            pos++
            binary++
            val exponent = unary() ?: return null
            return checked(base.pow(exponent))
        }

        private fun atom(): Double? {
            when (val t = tokens.getOrNull(pos)) {
                is Num -> {
                    pos++
                    return checked(t.value)
                }
                Open -> {
                    pos++
                    val v = expr() ?: return null
                    if (tokens.getOrNull(pos) !== Close) return null
                    pos++
                    return v
                }
                else -> return null
            }
        }
    }

    /**
     * [v] as the answer text: an integer when it is whole, else rounded to [DECIMALS] decimals
     * without trailing zeros, with "." as the separator. Null when a value that is not zero would
     * show as "0" ("1 ÷ 100000"): that is a wrong answer, not a rounded one.
     */
    private fun format(v: Double): String? {
        val whole = Math.rint(v)
        if (abs(v - whole) < WHOLE_TOLERANCE) return whole.toLong().toString()
        val rounded = BigDecimal.valueOf(v).setScale(DECIMALS, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) return null
        return rounded.stripTrailingZeros().toPlainString()
    }

    /**
     * The value of the arithmetic question [questionText] ("48", "-3", "3.75"), or null when it is
     * not one: see the class comment. Digits may be Arabic-Indic or Persian, the decimal separator
     * "." or "٫"; the operators are + - − – × x X * ÷ / ^ and parentheses, or the words زائد، ناقص،
     * ضرب، مضروب في، في (between two numbers)، على، مقسوم على، تقسيم.
     */
    fun evaluate(questionText: String): String? {
        val raw = questionText.trim()
        if (raw.isEmpty() || raw.length > MAX_CHARS) return null
        val text = AnswerParser.asciiDigits(raw).replace(TRAILING_FILLER, "")
        val tokens = resolveWords(stripFraming(lex(text) ?: return null)) ?: return null
        if (tokens.count { it is Num } !in 2..MAX_NUMBERS) return null
        return Parser(tokens).parse()?.let(::format)
    }

    /**
     * The answer to [q] when it is an arithmetic question, or null (the memory and the model then
     * answer). A numeric question gets the number. A multiple-choice question gets the option whose
     * number is the value: with no such option, or with several (two spellings of one value), the
     * question is declined, because choosing among them would be a guess.
     */
    fun answer(q: Question): AnswerParser.Resolved? {
        val value = evaluate(q.questionText) ?: return null
        if (q.numeric) return AnswerParser.Resolved(value, null)
        val target = value.toDouble()
        val matching = q.options.indices.filter { i ->
            val n = AnswerParser.numberOnly(q.options[i])?.toDoubleOrNull()
            n != null && abs(n - target) <= OPTION_TOLERANCE
        }
        val index = matching.singleOrNull() ?: return null
        return AnswerParser.Resolved(q.options[index], q.optionRects.getOrNull(index))
    }
}
