package com.clu.quickanswer.ocr

/** Output of a CTC recogniser: [steps] time steps by [classes] class scores, row-major in [values]. */
class CtcLogits(val steps: Int, val classes: Int, val values: FloatArray) {
    init {
        require(values.size >= steps * classes) { "logits too short: ${values.size} < $steps x $classes" }
    }
}

/** What greedy decoding produced: the text in visual order, and the summed max-probability of its characters. */
class CtcDecoded(val text: String, val probSum: Double, val charCount: Int)

/** CTC decoding and PaddleOCR's Arabic fix-up, as pure functions. */
object Ctc {

    /** Index of the CTC blank class. */
    const val BLANK = 0

    /**
     * Greedy CTC: per step take the best class, drop blanks and repeats. A repeat only collapses
     * when nothing, not even a blank, lies between the two steps. [labels] maps class index to
     * its text (index 0 is the blank and is never emitted).
     */
    fun greedyDecode(logits: CtcLogits, labels: Array<String>): CtcDecoded {
        require(logits.classes == labels.size) {
            "model has ${logits.classes} classes but the dictionary gives ${labels.size}"
        }
        val sb = StringBuilder()
        var probSum = 0.0
        var count = 0
        var last = BLANK
        val v = logits.values
        val c = logits.classes
        for (t in 0 until logits.steps) {
            val base = t * c
            var best = 0
            var bestV = v[base]
            var sum = bestV.toDouble()
            var min = bestV
            for (k in 1 until c) {
                val x = v[base + k]
                sum += x
                if (x > bestV) { bestV = x; best = k }
                if (x < min) min = x
            }
            if (best != BLANK && best != last) {
                sb.append(labels[best])
                probSum += maxProbability(v, base, c, bestV, sum, min)
                count++
            }
            last = best
        }
        return CtcDecoded(sb.toString(), probSum, count)
    }

    /**
     * The best class's probability. The PaddleOCR export already ends in a softmax, so a row that
     * sums to 1 is used as it is; any other row is treated as raw logits and soft-maxed.
     */
    private fun maxProbability(v: FloatArray, base: Int, c: Int, best: Float, sum: Double, min: Float): Double {
        if (min >= 0f && best <= 1.0001f && Math.abs(sum - 1.0) < 1e-2) return best.toDouble()
        var denom = 0.0
        for (k in 0 until c) denom += Math.exp((v[base + k] - best).toDouble())
        return 1.0 / denom
    }

    /**
     * PaddleOCR's `pred_reverse` for Arabic models. The network emits characters in visual
     * left-to-right order; reading order is the reverse, but runs of Latin letters, digits and
     * `:*./%+-` and spaces keep their own order. Arabic-Indic digits (U+0660-0669, U+06F0-06F9)
     * are also kept in runs: left to right is how a number is read in Arabic text too, so
     * reversing them digit by digit would scramble every number.
     */
    fun predReverse(pred: String): String {
        val pieces = ArrayList<String>()
        val run = StringBuilder()
        var i = 0
        while (i < pred.length) {
            val cp = pred.codePointAt(i)
            val n = Character.charCount(cp)
            if (isRunChar(cp)) {
                run.appendCodePoint(cp)
            } else {
                if (run.isNotEmpty()) {
                    pieces.add(run.toString())
                    run.setLength(0)
                }
                pieces.add(pred.substring(i, i + n))
            }
            i += n
        }
        if (run.isNotEmpty()) pieces.add(run.toString())
        return pieces.asReversed().joinToString("")
    }

    private fun isRunChar(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code ||
            cp == ' '.code || cp == ':'.code || cp == '*'.code || cp == '.'.code || cp == '/'.code ||
            cp == '%'.code || cp == '+'.code || cp == '-'.code ||
            cp in 0x0660..0x0669 || cp in 0x06F0..0x06F9
}
