package com.clu.quickanswer.ocr

/**
 * Reads the quiz question and its answer options off a screenshot, fully on the device: colour
 * layout ([ColorLayout]) -> tight text lines -> Arabic recogniser ([LineRecognizer]). No network.
 *
 * It declines (null) instead of guessing, so the caller falls back to a cloud model on the picture:
 * - the screen is not the quiz (no banner, or the banner and buttons are not shaped like the game's);
 * - the question is unreadable, too short, not Arabic, or one of its lines is unreliable;
 * - any answer button has no readable text (a missing option could be the right one), two options
 *   read the same, or an option is not Arabic text or digits;
 * - one of our own overlay windows ([masks], painted black in the capture) covers the question or
 *   the answer buttons;
 * - any question or option line that has characters is under [MIN_LINE_CONFIDENCE] on its own;
 * - the mean per-character confidence is under [minConfidence], over everything and over the
 *   question alone (clean options must not hide a garbled question).
 *
 * Stateless apart from the models it was given, so it is safe to share between threads.
 */
class ScreenReader(
    private val recognizer: LineRecognizer,
    private val layout: ColorLayout = ColorLayout(),
    private val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
) {

    /** Everything one [analyze] call found, for logging and tests. [question] is null when [failure] is set. */
    class Reading(
        val question: Question?,
        val failure: String?,
        val layout: ScreenLayout?,
        val questionLines: List<RecognizedLine>,
        val optionLines: List<List<RecognizedLine>>,
        val layoutMs: Double,
        val recognizeMs: Double,
    )

    /**
     * [argb] is row-major ARGB ints of size [width] x [height] (android.graphics.Bitmap.getPixels
     * layout). [masks] are the rectangles that were painted over the capture (our own windows).
     * [precomputed] is a [ColorLayout.inspect] result of the same pixels, when the caller already ran
     * it (it is then not run again); null inspects here.
     */
    fun read(
        argb: IntArray, width: Int, height: Int, masks: List<PxRect> = emptyList(),
        precomputed: ColorLayout.Result? = null,
    ): Question? = analyze(argb, width, height, masks, precomputed).question

    /** Like [read], with everything found along the way. [Reading.layoutMs] is 0 for a [precomputed] layout. */
    fun analyze(
        argb: IntArray, width: Int, height: Int, masks: List<PxRect> = emptyList(),
        precomputed: ColorLayout.Result? = null,
    ): Reading {
        val t0 = System.nanoTime()
        val inspected = precomputed ?: layout.inspect(argb, width, height)
        val found = inspected.layout
        val t1 = System.nanoTime()
        val layoutMs = if (precomputed != null) 0.0 else (t1 - t0) / 1e6
        fun fail(why: String, lq: List<RecognizedLine> = emptyList(), lo: List<List<RecognizedLine>> = emptyList()): Reading =
            Reading(null, why, found, lq, lo, layoutMs, (System.nanoTime() - t1) / 1e6)

        if (found == null) return fail(inspected.rejection ?: "no question banner")
        if (found.questionLines.isEmpty()) return fail("no question text lines")
        if (coveredByOverlay(found, masks)) return fail("an overlay window covers the question")

        val qLines = dropStrayTrailingLines(found.questionLines.map { recognizer.recognize(argb, width, height, it) })
        val questionText = joinLines(qLines)
        if (questionText.isEmpty()) return fail("question unreadable", qLines)
        if (ArabicText.key(questionText).length < MIN_QUESTION_CHARS || wordCount(questionText) < MIN_QUESTION_WORDS) {
            return fail("question too short", qLines)
        }
        if (ArabicText.arabicLetterRatio(questionText) < MIN_ARABIC_RATIO) return fail("question is not Arabic", qLines)
        if (qLines.any { it.charCount > 0 && it.confidence < MIN_LINE_CONFIDENCE }) {
            return fail("a question line is unreliable", qLines)
        }

        val oLines = ArrayList<List<RecognizedLine>>()
        val options = ArrayList<String>()
        for ((i, _) in found.buttons.withIndex()) {
            val lines = found.optionLines[i].map { recognizer.recognize(argb, width, height, it) }
            oLines.add(lines)
            val text = joinLines(lines)
            // A button without readable text is not skipped: the option that cannot be read could be
            // the right one, and the model would then only be able to pick a wrong answer.
            if (text.isEmpty()) return fail("a button has no readable text", qLines, oLines)
            // Like a question line, an option line must be reliable on its own: a clean option
            // must not hide a garbled one in the mean, and a garbled option could be the right answer.
            if (lines.any { it.charCount > 0 && it.confidence < MIN_LINE_CONFIDENCE }) {
                return fail("an option line is unreliable", qLines, oLines)
            }
            options.add(text)
        }
        if (!found.numeric) {
            if (options.size < MIN_OPTIONS) return fail("fewer than $MIN_OPTIONS options read", qLines, oLines)
            if (options.map { ArabicText.key(it) }.toSet().size != options.size) return fail("two options read the same", qLines, oLines)
            if (options.any { ArabicText.arabicOrDigitRatio(it) < MIN_OPTION_ARABIC_RATIO }) {
                return fail("an option is not Arabic text", qLines, oLines)
            }
        }

        val confidence = meanConfidence(qLines + oLines.flatten())
        if (confidence < minConfidence) return fail("low confidence %.3f".format(confidence), qLines, oLines)
        val questionConfidence = meanConfidence(qLines)
        if (questionConfidence < minConfidence) {
            return fail("low question confidence %.3f".format(questionConfidence), qLines, oLines)
        }

        val question = Question(
            questionText = questionText,
            options = options,
            optionRects = found.buttons,
            numeric = found.numeric,
            meanConfidence = confidence,
            numericField = found.numericField,
            digitKeys = found.digitKeys,
            bannerRect = found.banner,
            questionTextRects = found.questionLines.take(qLines.size),
            numericSubmit = found.numericSubmit,
            optionTextRects = found.optionLines.mapIndexed { index, regions ->
                val button = found.buttons[index]
                if (regions.isEmpty()) button else PxRect(
                    regions.minOf { it.left }, regions.minOf { it.top },
                    regions.maxOf { it.right }, regions.maxOf { it.bottom },
                )
            },
        )
        return Reading(question, null, found, qLines, oLines, layoutMs, (System.nanoTime() - t1) / 1e6)
    }

    private fun meanConfidence(lines: List<RecognizedLine>): Float {
        var probSum = 0.0
        var chars = 0
        for (line in lines) { probSum += line.probSum; chars += line.charCount }
        return if (chars > 0) (probSum / chars).toFloat() else 0f
    }

    private fun joinLines(lines: List<RecognizedLine>): String =
        lines.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun wordCount(text: String): Int = ArabicText.normalize(text).split(' ').count { it.isNotEmpty() }

    /**
     * A last question line that is a stray mark at the banner's lower edge is not part of the
     * question: a few characters ([STRAY_MAX_CHARS] at most) with no letter or digit of the language,
     * or read with low confidence. A longer line is the question's own and stays.
     */
    private fun dropStrayTrailingLines(lines: List<RecognizedLine>): List<RecognizedLine> {
        var end = lines.size
        while (end > 1) {
            val last = lines[end - 1]
            val text = last.text.trim()
            val short = ArabicText.key(text).length <= STRAY_MAX_CHARS
            val noWord = text.none { ArabicText.isArabicLetter(it) || it.isDigit() }
            if (!(short && (noWord || last.confidence < MIN_LINE_CONFIDENCE))) break
            end--
        }
        return lines.subList(0, end)
    }

    /** See [OverlayCheck.covers]; the English reader applies the same rule. */
    private fun coveredByOverlay(found: ScreenLayout, masks: List<PxRect>): Boolean = OverlayCheck.covers(found, masks)

    companion object {
        /**
         * Below this mean per-character confidence the reading is not trusted. The lab suggested
         * about 0.8, but S1 (a blurry photo of the screen, still 98 % accurate) scores 0.79-0.80,
         * while live captures score 0.88-0.95; 0.75 keeps the photo and still rejects garbage.
         */
        const val DEFAULT_MIN_CONFIDENCE = 0.75f

        /** A multiple-choice screen needs at least this many readable options. */
        const val MIN_OPTIONS = 2

        /**
         * Every question or option line that has characters must reach this on its own. The worst
         * real question line is 0.78 (S1); a band merged by an overlay or a cut-off banner reads
         * 0.30-0.56.
         */
        const val MIN_LINE_CONFIDENCE = 0.6f

        // The game's questions are 56-89 characters of Arabic; a headline of a few letters is a dialog or a mistake.
        private const val MIN_QUESTION_CHARS = 10
        private const val MIN_QUESTION_WORDS = 2
        private const val MIN_ARABIC_RATIO = 0.8
        private const val MIN_OPTION_ARABIC_RATIO = 0.5

        /** A trailing question line this short (key characters) that is unsure or not text is noise. */
        private const val STRAY_MAX_CHARS = 3
    }
}
