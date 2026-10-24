package com.clu.quickanswer.interaction

import com.clu.quickanswer.ocr.Question
import com.clu.quickanswer.ocr.ScreenLayout
import com.clu.quickanswer.ocr.WordAnswerMatcher
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.TextLines

/** Independent pixel/OCR fallback when reading the question language failed, but its options did not. */
object PrintedChoiceTouch {
    /** The colour detector supplies button geometry even when its older line cutter misses coloured ink. */
    fun regions(layout: ScreenLayout, pixels: IntArray, width: Int, height: Int): List<PxRect>? {
        if (layout.numeric || layout.buttons.size != 4) return null
        return layout.buttons.map { button ->
            val found = TextLines.findDetailed(pixels, width, height, button, bounded = true)
            if (found.clipped || found.lines.size != 1) return null
            found.lines.single()
        }
    }

    fun request(layout: ScreenLayout, printed: List<WordAnswerMatcher.Candidate>, reply: String): AnswerInteraction.Request? {
        if (layout.numeric || printed.size !in 2..8 || layout.buttons.size != printed.size ||
            printed.any { !it.confidence.isFinite() || it.confidence !in 0.75f..1f }) return null
        if (printed.indices.any { i ->
            val r = printed[i].rect; val b = layout.buttons[i]
            r.width < 8 || r.height < 8 || r.left < b.left || r.top < b.top || r.right > b.right || r.bottom > b.bottom
        }) return null
        val options = printed.map { it.text }
        val literal = options.singleOrNull {
            AnswerInteraction.matchesChoice(reply, it, options, indexRequested = false)
        } ?: return null
        val q = Question("", options, layout.buttons, false, printed.minOf { it.confidence },
            bannerRect = layout.banner, optionTextRects = printed.map { it.rect }, questionTextRects = layout.questionLines)
        return AnswerInteraction.Request(q, literal, reply)
    }
}
