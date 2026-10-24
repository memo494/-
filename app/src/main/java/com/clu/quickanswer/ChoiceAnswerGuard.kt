package com.clu.quickanswer

import com.clu.quickanswer.ocr.ArabicText

/** Display matching can be fuzzy; an automatic tap needs exact visible text or a requested index. */
internal object ChoiceAnswerGuard {
    /** Preserve the original reply's evidence when caching; fuzzy display text must not become trusted later. */
    fun rememberChoice(
        memory: AnswerMemory, question: String, reply: String, resolved: String,
        options: List<String>, allowOptionIndex: Boolean,
    ): Boolean {
        if (!matches(reply, resolved, options, allowOptionIndex)) return false
        memory.put(question, options, false, resolved)
        return true
    }

    fun matches(reply: String, resolved: String, options: List<String>, allowOptionIndex: Boolean): Boolean {
        if (options.size != 4) return false
        val keys = options.map(ArabicText::key)
        if (keys.any { it.isBlank() } || keys.toSet().size != 4) return false
        val target = ArabicText.key(resolved)
        if (target !in keys) return false
        if (ArabicText.key(reply) == target) return true
        val index = if (allowOptionIndex) AnswerParser.optionNumber(reply, options.size) else null
        return index != null && keys[index - 1] == target
    }
}
