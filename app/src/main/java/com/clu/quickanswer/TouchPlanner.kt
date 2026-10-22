package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect

/** Pure, fail-closed planning. The gesture executor never has to guess a screen position. */
object TouchPlanner {
    /** Select an option, or type an integer of one to four digits on the verified quiz keypad. */
    fun plan(
        answer: String,
        option: PxRect?,
        digitKeys: Map<Char, PxRect>,
        numericSubmit: PxRect? = null,
        screenWidth: Int,
        screenHeight: Int,
    ): List<PxRect>? {
        val boxes = if (option != null) listOf(option) else {
            if (answer.length !in 1..4 || answer.any { it !in '0'..'9' }) return null
            answer.map { digitKeys[it] ?: return null } + listOfNotNull(numericSubmit)
        }
        if (screenWidth <= 0 || screenHeight <= 0) return null
        if (boxes.any {
            it.width < 8 || it.height < 8 || it.left < 0 || it.top < 0 ||
                it.right > screenWidth || it.bottom > screenHeight ||
                it.width > screenWidth * 0.85 || it.height > screenHeight * 0.4
        }) return null
        return boxes
    }
}
