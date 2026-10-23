package com.clu.quickanswer.ocr

/**
 * The geometry rules every way of making a template shares, so a template learned from a model's
 * boxes ([TemplateLearner]) and one drawn by hand ([ManualTemplate]) order and check their regions
 * the same way. Pure Kotlin, no android.*.
 */
internal object RegionGeometry {

    /**
     * [items] grouped into rows, top to bottom, each row in reading order: right to left, or left to
     * right when [leftToRight]. Two items are in one row when their vertical ranges overlap by at
     * least half of the shorter one's height. [box] gives an item's rectangle in any one unit.
     */
    fun <T> readingRows(items: List<T>, leftToRight: Boolean, box: (T) -> PxRect): List<List<T>> {
        val rows = ArrayList<MutableList<T>>()
        for (item in items.sortedBy { box(it).top }) {
            val b = box(item)
            val row = rows.firstOrNull { r -> r.any { sameRow(box(it), b) } }
            if (row != null) row.add(item) else rows.add(mutableListOf(item))
        }
        return rows.map { row ->
            if (leftToRight) row.sortedBy { box(it).left } else row.sortedByDescending { box(it).right }
        }
    }

    private fun sameRow(a: PxRect, b: PxRect): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap >= 0.5 * minOf(a.bottom - a.top, b.bottom - b.top)
    }

    /** True when [a] and [b] share more than [maxShare] of the smaller one's area. */
    fun overlapsTooMuch(a: RelRect, b: RelRect, maxShare: Float): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return false
        val smaller = minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
        return w * h > maxShare * smaller
    }
}
