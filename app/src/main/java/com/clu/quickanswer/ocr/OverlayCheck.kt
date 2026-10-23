package com.clu.quickanswer.ocr

/** Shared test for overlay windows (painted over the capture) that hide part of what is read. */
object OverlayCheck {
    /**
     * True when one of [masks] (painted black over the capture) hides part of what is read: a
     * question line, the number field, an answer button, or where one more button of the stack
     * would be (below the last).
     */
    fun covers(layout: ScreenLayout, masks: List<PxRect>): Boolean {
        if (masks.isEmpty()) return false
        val needed = ArrayList<PxRect>(layout.questionLines)
        layout.numericField?.let { needed.add(it) }
        needed.addAll(layout.buttons)
        val last = layout.buttons.lastOrNull()
        if (last != null) {
            val before = layout.buttons.getOrNull(layout.buttons.size - 2)
            val pitch = if (before != null) last.top - before.top else (last.height * 1.4).toInt()
            needed.add(PxRect(last.left, last.bottom, last.right, last.bottom + pitch))
        }
        return masks.any { m -> needed.any { overlaps(m, it) } }
    }

    fun overlaps(a: PxRect, b: PxRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
