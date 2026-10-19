package com.clu.quickanswer.ocr

/**
 * Reads the quiz question and its answer options off a screenshot, fully on the device: colour
 * layout ([ColorLayout]) -> tight text lines -> Arabic recogniser ([LineRecognizer]). No network.
 *
 * It declines (null) instead of guessing: no question banner, no readable question text, a
 * multiple-choice screen with fewer than two readable options, or a mean per-character confidence
 * under [minConfidence]. The caller should then fall back to a cloud model.
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

    /** [argb] is row-major ARGB ints of size [width] x [height] (android.graphics.Bitmap.getPixels layout). */
    fun read(argb: IntArray, width: Int, height: Int): Question? = analyze(argb, width, height).question

    fun analyze(argb: IntArray, width: Int, height: Int): Reading {
        val t0 = System.nanoTime()
        val found = layout.analyze(argb, width, height)
        val t1 = System.nanoTime()
        val layoutMs = (t1 - t0) / 1e6
        fun fail(why: String, lq: List<RecognizedLine> = emptyList(), lo: List<List<RecognizedLine>> = emptyList()): Reading =
            Reading(null, why, found, lq, lo, layoutMs, (System.nanoTime() - t1) / 1e6)

        if (found == null) return fail("no question banner")
        if (found.questionLines.isEmpty()) return fail("no question text lines")

        val qLines = found.questionLines.map { recognizer.recognize(argb, width, height, it) }
        val questionText = joinLines(qLines)
        if (questionText.isEmpty()) return fail("question unreadable", qLines)

        val oLines = ArrayList<List<RecognizedLine>>()
        val options = ArrayList<String>()
        val rects = ArrayList<PxRect>()
        for ((i, button) in found.buttons.withIndex()) {
            val lines = found.optionLines[i].map { recognizer.recognize(argb, width, height, it) }
            val text = joinLines(lines)
            oLines.add(lines)
            // A button without readable text is left out together with its rect, so options and
            // optionRects stay index-aligned.
            if (text.isNotEmpty()) {
                options.add(text)
                rects.add(button)
            }
        }
        if (!found.numeric && options.size < MIN_OPTIONS) return fail("fewer than $MIN_OPTIONS options read", qLines, oLines)

        var probSum = 0.0
        var chars = 0
        for (line in qLines) { probSum += line.probSum; chars += line.charCount }
        for (lines in oLines) for (line in lines) { probSum += line.probSum; chars += line.charCount }
        val confidence = if (chars > 0) (probSum / chars).toFloat() else 0f
        if (confidence < minConfidence) return fail("low confidence %.3f".format(confidence), qLines, oLines)

        val question = Question(
            questionText = questionText,
            options = options,
            optionRects = rects,
            numeric = found.numeric,
            meanConfidence = confidence,
        )
        return Reading(question, null, found, qLines, oLines, layoutMs, (System.nanoTime() - t1) / 1e6)
    }

    private fun joinLines(lines: List<RecognizedLine>): String =
        lines.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        /**
         * Below this mean per-character confidence the reading is not trusted. The lab suggested
         * about 0.8, but S1 (a blurry photo of the screen, still 98 % accurate) scores 0.79-0.80,
         * while live captures score 0.88-0.95; 0.75 keeps the photo and still rejects garbage.
         */
        const val DEFAULT_MIN_CONFIDENCE = 0.75f

        /** A multiple-choice screen needs at least this many readable options. */
        const val MIN_OPTIONS = 2
    }
}
