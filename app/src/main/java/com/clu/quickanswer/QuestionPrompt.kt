package com.clu.quickanswer

import com.clu.quickanswer.ocr.Question

/**
 * The prompt for a question that was read on the device. One place for every provider, so Gemini and
 * Claude are asked the same thing. The reply is parsed by [AnswerParser].
 */
object QuestionPrompt {

    fun build(q: Question): String {
        val sb = StringBuilder()
        if (q.numeric) {
            sb.append(
                "This is a quiz question whose answer is a number. Reply with ONLY digits (0-9): your " +
                    "best single number estimate (the typical or most recent real value), even if the " +
                    "exact number varies. No words, no units, no explanation.\n"
            )
        } else {
            sb.append(
                "This is a multiple-choice quiz question. Reply with ONLY the number of the correct " +
                    "option (for example: 2). No option text, no explanation.\n"
            )
        }
        sb.append("The text was read from a screenshot by OCR and may contain small spelling mistakes.\n\n")
        sb.append("Question: ").append(q.questionText).append('\n')
        if (!q.numeric) {
            sb.append("Options:\n")
            for ((i, option) in q.options.withIndex()) sb.append(i + 1).append(") ").append(option).append('\n')
        }
        return sb.toString().trimEnd()
    }
}
