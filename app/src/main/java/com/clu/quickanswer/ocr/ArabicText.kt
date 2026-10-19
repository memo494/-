package com.clu.quickanswer.ocr

import kotlin.math.max
import kotlin.math.min

/** Arabic text normalisation and string similarity for comparing OCR output and building cache keys. */
object ArabicText {

    /**
     * Comparison form of [s]: diacritics (harakat, Quranic marks, dagger alef) and tatweel are
     * dropped; alef variants become ا, ى becomes ي, ة becomes ه (and Persian ی/ک become ي/ك);
     * punctuation turns into a space; runs of spaces collapse to one and the ends are trimmed.
     */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (ch in s) {
            if (isDiacritic(ch)) continue
            if (ch.isWhitespace() || isPunctuation(ch)) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
            pendingSpace = false
            sb.append(
                when (ch) {
                    '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627' // alef with hamza/madda, alef wasla -> alef
                    '\u0649', '\u06CC' -> '\u064A' // alef maqsura, Persian yeh -> yeh
                    '\u0629' -> '\u0647' // teh marbuta -> heh
                    '\u06A9' -> '\u0643' // Persian keheh -> kaf
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** [normalize] with every space removed: the key to compare or cache a question by. */
    fun key(s: String): String = normalize(s).replace(" ", "")

    /** Edit distance between [a] and [b] (insert, delete, substitute all cost 1). */
    fun levenshtein(a: CharSequence, b: CharSequence): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(sub, min(prev[j] + 1, cur[j - 1] + 1))
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Normalised Levenshtein ratio of the [key]s of [a] and [b]: 1.0 when they are the same text,
     * 0.0 when nothing matches. Two empty texts are identical (1.0).
     */
    fun similarity(a: String, b: String): Double {
        val ka = key(a)
        val kb = key(b)
        val longest = max(ka.length, kb.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(ka, kb).toDouble() / longest
    }

    /**
     * The lab's accuracy metric: 1 - edit distance / reference length on the [normalize]d texts
     * (single spaces kept), floored at 0. Use it to score OCR output against a ground truth.
     */
    fun charAccuracy(hypothesis: String, reference: String): Double {
        val h = normalize(hypothesis)
        val r = normalize(reference)
        return max(0.0, 1.0 - levenshtein(h, r).toDouble() / max(1, r.length))
    }

    private fun isDiacritic(c: Char): Boolean =
        c in '\u0610'..'\u061A' || c in '\u064B'..'\u065F' || c == '\u0670' ||
            c in '\u06D6'..'\u06ED' || c == '\u0640' // tatweel

    private fun isPunctuation(c: Char): Boolean = when (c) {
        '\u061F', '\u060C', '\u00AB', '\u00BB', '?', '!', '.', ',', ':', ';', '"', '\'', '(', ')', '[', ']', '{', '}',
        '-', '_', '|', '/', '\\', '*', '~', '`', '^', '<', '>' -> true
        else -> false
    }
}
