package com.clu.quickanswer

/**
 * What shape a model's image reply has. The general screenshot prompt lets the model answer several
 * questions, several correct options or matching pairs, but only a reply that is ONE answer can be
 * compared with the options read on the device, or taught to a layout template. Pure Kotlin.
 */
object ReplyShape {

    // "1-ب", "2 – أ", "٣-ج": a number, a dash and a letter, as in a matching answer "1-ب، 2-أ".
    private val PAIRS = Regex("[0-9٠-٩]+\\s*[-–]\\s*\\p{L}")

    /** The non-blank lines of [reply], trimmed. */
    fun lines(reply: String): List<String> = reply.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** True when the reply has one answer per line (several questions). */
    fun isList(reply: String): Boolean = lines(reply).size > 1

    /** True when one line joins several answers: options with " + " or two or more matching pairs. */
    fun isCompound(reply: String): Boolean =
        reply.contains(" + ") || PAIRS.findAll(reply).count() >= 2

    /** True when the model said it cannot read the question ([ScreenImage.UNSURE]). */
    fun isUnsure(reply: String): Boolean = reply.trim().trimEnd('.', '!', '،', ' ') == ScreenImage.UNSURE

    /** True when [reply] is exactly one answer: not blank, a list, compound, "unsure" or "no question". */
    fun isSingle(reply: String): Boolean =
        reply.isNotBlank() && !isList(reply) && !isCompound(reply) && !isUnsure(reply) && !ScreenImage.isNoQuestion(reply)
}
