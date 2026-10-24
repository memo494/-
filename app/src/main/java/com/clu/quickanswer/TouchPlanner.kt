package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect

/** Pure, fail-closed planning. The gesture executor never has to guess a screen position. */
object TouchPlanner {
    const val MAX_DIGITS = 4
    /** Select an option, or type an integer of one to four digits on the verified quiz keypad. */
    fun plan(
        answer: String,
        option: PxRect?,
        digitKeys: Map<Char, PxRect>,
        numericSubmit: PxRect? = null,
        screenWidth: Int,
        screenHeight: Int,
        clear: PxRect? = null,
        backspace: PxRect? = null,
        requireReset: Boolean = false,
    ): List<PxRect>? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        fun valid(rect: PxRect): Boolean =
            rect.left >= 0 && rect.top >= 0 && rect.right <= screenWidth && rect.bottom <= screenHeight &&
                rect.width >= 8 && rect.height >= 8 &&
                rect.width <= screenWidth * 0.85 && rect.height <= screenHeight * 0.4
        fun overlaps(a: PxRect, b: PxRect): Boolean =
            a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        val boxes = if (option != null) listOf(option) else {
            if (answer.length !in 1..MAX_DIGITS || answer.any { it !in '0'..'9' } ||
                digitKeys.keys != ('0'..'9').toSet() || clear != null && backspace != null ||
                requireReset && clear == null && backspace == null
            ) return null
            // Reject ambiguous maps, including two digits sharing the same target.
            val controls = digitKeys.values.toList() + listOfNotNull(numericSubmit, clear, backspace)
            if (controls.any { !valid(it) } || controls.withIndex().any { (i, a) ->
                controls.drop(i + 1).any { b -> overlaps(a, b) }
            }) return null
            // The games accept at most four digits. Reset first so a partly entered answer
            // (including a previous cancelled attempt) cannot be appended to this one.
            val reset = when {
                clear != null -> listOf(clear)
                backspace != null -> List(MAX_DIGITS) { backspace }
                else -> emptyList()
            }
            reset + answer.map { digitKeys.getValue(it) } + listOfNotNull(numericSubmit)
        }
        if (boxes.any { !valid(it) }) return null
        return boxes
    }
}
