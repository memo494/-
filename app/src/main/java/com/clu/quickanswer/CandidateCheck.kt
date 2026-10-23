package com.clu.quickanswer

import com.clu.quickanswer.ocr.Question

/**
 * How a CANDIDATE layout template is confirmed by real answers: the screen is read with it on the
 * device (never used to answer: a candidate is not trusted yet), the screenshot is answered as
 * usual, and the model's answer is compared with what was read. An answer that is one of the read
 * options (or a number, for a number field) means the template found the right places; an answer
 * that matches nothing means it did not. Errors and "no question" say nothing about the template.
 * Pure Kotlin.
 */
object CandidateCheck {

    enum class Verdict { CONFIRMED, REFUTED, UNKNOWN }

    /** The [verdict], and for CONFIRMED the answer mapped onto [Question] (the option's box, if any). */
    class Result(val verdict: Verdict, val resolved: AnswerParser.Resolved?)

    /** Judges the image answer ([replyIsAnswer] = [Reply.isAnswer], [replyText]) against [read]. */
    fun judge(read: Question, replyIsAnswer: Boolean, replyText: String): Result {
        if (!replyIsAnswer || replyText.isBlank() || ScreenImage.isNoQuestion(replyText)) {
            return Result(Verdict.UNKNOWN, null)
        }
        val resolved = if (read.numeric) {
            AnswerParser.numberOnly(replyText)?.let { AnswerParser.Resolved(it, null) }
        } else {
            AnswerParser.resolveImageAnswer(read, replyText)
        }
        return if (resolved != null) Result(Verdict.CONFIRMED, resolved) else Result(Verdict.REFUTED, null)
    }
}
