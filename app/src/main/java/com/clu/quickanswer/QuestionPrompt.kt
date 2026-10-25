package com.clu.quickanswer

import com.clu.quickanswer.ocr.ArabicText
import com.clu.quickanswer.ocr.Question

/**
 * The prompt for a question that was read on the device. One place for every provider, so Gemini and
 * Claude are asked the same thing. The reply is parsed by [AnswerParser].
 */
object QuestionPrompt {

    /**
     * What the model replies when the text is not a quiz question at all (a dialog, a menu, chat,
     * settings...): the screen was mistaken for the quiz. No parser accepts it as an answer, so the
     * screenshot is sent instead and gets the usual "no question" reply. Not "0": that is a number.
     */
    const val NONE = "NONE"

    private const val NOT_A_QUESTION =
        "If this text is not a real quiz question (it comes from a dialog, menu, chat, settings or " +
            "other screen), reply exactly: $NONE\n"

    /** What tells the model the attached image is part of the question, see [buildPicture]. */
    private const val PICTURE_NOTE =
        "A picture is attached: it is the picture this question shows, and the answer depends on it.\n"

    /** The prompt for a question read as text only: no image goes with it. */
    fun build(q: Question): String = compose(q, withPicture = false)

    /**
     * The prompt for a question that shows a picture, sent together with that picture (see
     * [Answerer.answerPicture]): [build] plus one line saying the attached image is the picture the
     * question shows and that the answer depends on it. Without it the model may take the image for
     * decoration, and "ما هو اسم الشخصية الخيالية الاتية ؟" has no word that points at a picture. The
     * answer format and the [NONE] escape are the same, so the same parser reads the reply.
     */
    fun buildPicture(q: Question): String = compose(q, withPicture = true)

    private fun compose(q: Question, withPicture: Boolean): String {
        val sb = StringBuilder()
        if (q.numeric) {
            sb.append(
                "This is a quiz question whose answer is a number. Reply with ONLY digits (0-9): your " +
                    "best single number estimate (the typical or most recent real value), even if the " +
                    "exact number varies. The game's field takes a whole number of at most 4 digits " +
                    "(0-9999), so give the value in the unit that fits it (litre to millilitres: 1000). " +
                    "No words, no units, no explanation.\n"
            )
        } else {
            sb.append(
                "This is a multiple-choice quiz question. Reply with ONLY the number of the correct " +
                    "option (for example: 2). No option text, no explanation.\n"
            )
        }
        if (withPicture) sb.append(PICTURE_NOTE)
        sb.append(NOT_A_QUESTION)
        sb.append("The text was read from a screenshot by OCR and may contain small spelling mistakes.\n\n")
        sb.append("Question: ").append(q.questionText).append('\n')
        if (!q.numeric) {
            sb.append("Options:\n")
            for ((i, option) in q.options.withIndex()) sb.append(i + 1).append(") ").append(option).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // The words (normalised) with which a question points at a picture on the screen.
    private val PICTURE_WORDS = setOf(
        "الصوره", "صوره", "بالصوره", "الصور", "الشكل", "الشعار",
        "picture", "pictured", "photo", "photograph", "image", "diagram", "figure", "logo"
    )

    /**
     * True when the question refers to a picture ("ما اسم صاحب الصورة؟"): its words alone do not
     * decide the answer, so it must be asked with the screenshot, and an answer remembered for the
     * same words (an earlier picture) must not be reused.
     */
    fun refersToPicture(questionText: String): Boolean =
        ArabicText.normalize(AnswerParser.asciiDigits(questionText)).split(' ').any { it in PICTURE_WORDS }
}
