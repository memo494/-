package com.clu.quickanswer.interaction

import com.clu.quickanswer.AnswerMemory
import com.clu.quickanswer.AnswerParser
import com.clu.quickanswer.TouchPlanner
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question

/** Plans an interaction from reader evidence, independent of a game's name, skin or coordinates. */
object AnswerInteraction {
    enum class Kind { CHOICE, NUMBER }

    data class Request(
        val question: Question,
        val answer: String,
        val reply: String,
        val indexRequested: Boolean = false,
        val literalAnswer: Boolean = false,
    )

    /** Geometry must come from a verified detector/template, never from a model's guessed tap. */
    data class Controls(
        val width: Int,
        val height: Int,
        val digits: Map<Char, PxRect> = emptyMap(),
        val clear: PxRect? = null,
        val backspace: PxRect? = null,
        val submit: PxRect? = null,
        /** Verified from pixels; bypasses legacy reset planning without appending to unknown input. */
        val fieldIsEmpty: Boolean = false,
    )

    data class Plan(val kind: Kind, val taps: List<PxRect>, val number: String? = null)

    /** New question patterns can provide another planner without changing the answer providers. */
    fun interface Pattern {
        fun plan(request: Request, controls: Controls): Plan?
    }

    private val defaults = listOf(ChoicePattern, NumberPattern)

    fun plan(request: Request, controls: Controls, patterns: List<Pattern> = defaults): Plan? {
        if (!request.question.meanConfidence.isFinite() ||
            request.question.meanConfidence !in 0.75f..1f) return null
        return patterns.firstNotNullOfOrNull { pattern ->
            try { pattern.plan(request, controls) } catch (_: Throwable) { null }
        }
    }

    private val label = Regex("^\\s*(\\d+|[a-hA-H]|[أ-ي])(?:\\s*[)\\]:\\-–]\\s*|\\.\\s+)(.+)$")

    /** An exact visible answer, optionally labelled; a bare index is valid only when requested. */
    fun matchesChoice(reply: String, answer: String, options: List<String>, indexRequested: Boolean,
                      literalAnswer: Boolean = false): Boolean {
        if (options.size !in 2..8) return false
        val keys = options.map(AnswerMemory::keyOf)
        if (keys.any { it.isBlank() } || keys.toSet().size != keys.size) return false
        val target = AnswerMemory.keyOf(answer)
        val at = keys.indexOf(target)
        if (at < 0) return false
        val raw = AnswerParser.asciiDigits(reply.trim())
        val exact = AnswerMemory.keyOf(raw) == target
        // A stored/local option is its literal value, even after a shuffle. A screenshot's bare
        // number can instead mean an option index and needs the ambiguity check below.
        if (exact && (literalAnswer || !indexRequested && AnswerParser.numberOnly(raw) == null)) return true
        val possibleIndex = AnswerParser.optionNumber(raw, options.size)
        if (possibleIndex != null && indexRequested) {
            if (keys.withIndex().any { (i, key) -> i != possibleIndex - 1 && key == possibleIndex.toString() }) return false
            return possibleIndex - 1 == at
        }
        if (possibleIndex != null && keys[possibleIndex - 1] != target) return false
        if (AnswerMemory.keyOf(raw) == target) return true
        val labelled = label.matchEntire(raw)
        if (labelled != null) {
            val number = labelled.groupValues[1].toIntOrNull()
            if (number != null && number != at + 1) return false
            return AnswerMemory.keyOf(labelled.groupValues[2]) == target
        }
        if (!indexRequested) return false
        val index = possibleIndex ?: return false
        // Numeric option text can otherwise be confused with an option's index.
        if (keys.withIndex().any { (i, key) -> i != index - 1 && key == index.toString() }) return false
        return index - 1 == at
    }

    private object ChoicePattern : Pattern {
        override fun plan(request: Request, controls: Controls): Plan? {
            val q = request.question
            if (q.numeric || q.optionRects.size != q.options.size ||
                !matchesChoice(request.reply, request.answer, q.options, request.indexRequested, request.literalAnswer)) return null
            val all = q.optionRects
            if (all.any { !inside(it, controls) } || all.withIndex().any { (i, a) ->
                    all.drop(i + 1).any { b -> overlaps(a, b) }
                }) return null
            val index = q.options.indexOfFirst { AnswerMemory.keyOf(it) == AnswerMemory.keyOf(request.answer) }
            val button = all[index]
            val word = q.optionTextRects.getOrNull(index)?.takeIf {
                it.left >= button.left && it.top >= button.top && it.right <= button.right && it.bottom <= button.bottom
            }
            val taps = TouchPlanner.plan(request.answer, word ?: button, emptyMap(),
                screenWidth = controls.width, screenHeight = controls.height) ?: return null
            return Plan(Kind.CHOICE, taps)
        }
    }

    private object NumberPattern : Pattern {
        override fun plan(request: Request, controls: Controls): Plan? {
            if (!request.question.numeric) return null
            val number = AnswerParser.numberOnly(request.answer) ?: return null
            if (AnswerParser.numberOnly(request.reply) != number) return null
            val taps = TouchPlanner.plan(number, null, controls.digits, controls.submit,
                controls.width, controls.height,
                clear = controls.clear.takeUnless { controls.fieldIsEmpty },
                backspace = controls.backspace.takeUnless { controls.fieldIsEmpty },
                requireReset = !controls.fieldIsEmpty) ?: return null
            return Plan(Kind.NUMBER, taps, number)
        }
    }

    private fun inside(r: PxRect, controls: Controls): Boolean = r.left >= 0 && r.top >= 0 &&
        r.right <= controls.width && r.bottom <= controls.height && r.width >= 8 && r.height >= 8

    private fun overlaps(a: PxRect, b: PxRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
