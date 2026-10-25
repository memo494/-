package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect

/**
 * Decides where the answer panel goes relative to the floating bubble. Pure Kotlin (no Android
 * imports) so it can be unit tested on the JVM. All values are pixels in screen coordinates.
 */
object PanelPlacement {

    /** Where the answer panel is shown. [key] is what [Prefs] stores. */
    enum class Mode(val key: String) {
        /** Centred at the top of the screen, over the question banner (taps pass through anyway). */
        TOP("top"),

        /** Next to the floating bubble and following it while it is dragged. */
        NEXT_TO_BUBBLE("bubble");

        companion object {
            /** Anything that is not the bubble key (missing, unknown, old) means [TOP]. */
            fun fromKey(key: String?): Mode = if (key == NEXT_TO_BUBBLE.key) NEXT_TO_BUBBLE else TOP
        }
    }

    /**
     * Widest the panel may be next to the bubble. In landscape that is only a quarter of the
     * screen: the answer options of the games sit just right of the bubble's default spot (about a
     * third across), so a wider panel would hide them. Portrait has the room, so it gets 65%.
     */
    fun maxWidth(screenW: Int, screenH: Int): Int =
        (screenW * if (screenW > screenH) LANDSCAPE_WIDTH_FRACTION else PORTRAIT_WIDTH_FRACTION).toInt()

    const val LANDSCAPE_WIDTH_FRACTION = 0.25f
    const val PORTRAIT_WIDTH_FRACTION = 0.65f

    /** Widest the panel may be at the top of the screen: 60% in landscape, 90% in portrait. */
    fun topMaxWidth(screenW: Int, screenH: Int): Int =
        screenW * (if (screenW > screenH) TOP_LANDSCAPE_WIDTH_PERCENT else TOP_PORTRAIT_WIDTH_PERCENT) / 100

    const val TOP_LANDSCAPE_WIDTH_PERCENT = 60

    /**
     * How many lines the panel shows for [text] in [mode]: 2 at the top and 4 next to the bubble
     * keep a single answer small, but a reply with one answer per line (a screen with several
     * questions) gets a line for each, up to [MAX_LIST_LINES], instead of losing the last ones.
     */
    fun maxLines(mode: Mode, text: String): Int {
        val base = if (mode == Mode.TOP) TOP_MAX_LINES else BUBBLE_MAX_LINES
        val lines = text.trim().lines().count { it.isNotBlank() }
        return maxOf(base, minOf(lines, MAX_LIST_LINES))
    }

    const val TOP_MAX_LINES = 2
    const val BUBBLE_MAX_LINES = 4
    const val MAX_LIST_LINES = 8
    const val TOP_PORTRAIT_WIDTH_PERCENT = 90

    /** Widest the panel may be in the given [mode]. */
    fun maxWidth(mode: Mode, screenW: Int, screenH: Int): Int = when (mode) {
        Mode.TOP -> topMaxWidth(screenW, screenH)
        Mode.NEXT_TO_BUBBLE -> maxWidth(screenW, screenH)
    }

    /** Top-left corner of the panel. */
    data class Pos(val x: Int, val y: Int)

    /**
     * The panel goes beside the bubble, on the side with more horizontal room (right when the
     * bubble's centre is in the left half of the screen, left otherwise), vertically centred on
     * the bubble. If it does not fit beside the bubble it goes just below it, or just above it
     * when there is no room below. The result is always clamped so the panel stays on screen
     * (as far as its size allows).
     */
    fun place(
        bubbleX: Int,
        bubbleY: Int,
        bubbleSize: Int,
        panelW: Int,
        panelH: Int,
        screenW: Int,
        screenH: Int,
        gap: Int
    ): Pos {
        val bubbleCenterX = bubbleX + bubbleSize / 2
        val bubbleCenterY = bubbleY + bubbleSize / 2

        val onRight = bubbleCenterX * 2 < screenW
        val sideX = if (onRight) bubbleX + bubbleSize + gap else bubbleX - gap - panelW
        if (sideX >= 0 && sideX + panelW <= screenW) {
            return Pos(sideX, clampY(bubbleCenterY - panelH / 2, panelH, screenH))
        }

        // No room beside the bubble: centre under (or over) it.
        val x = clampX(bubbleCenterX - panelW / 2, panelW, screenW)
        val below = bubbleY + bubbleSize + gap
        if (below >= 0 && below + panelH <= screenH) return Pos(x, below)
        val above = bubbleY - gap - panelH
        if (above >= 0 && above + panelH <= screenH) return Pos(x, above)
        return Pos(x, clampY(below, panelH, screenH))
    }

    /**
     * The panel goes horizontally centred, [margin] below the top edge of the screen. It may cover
     * the status bar or the game's question banner: the panel does not take touches. Clamped so it
     * stays on screen (as far as its size allows).
     */
    fun top(panelW: Int, panelH: Int, screenW: Int, screenH: Int, margin: Int): Pos =
        Pos(clampX((screenW - panelW) / 2, panelW, screenW), clampY(margin, panelH, screenH))

    /**
     * Moves a window of [w]x[h] at ([x], [y]) fully onto the screen (as far as its size allows).
     * Used to pull the bubble back in after a rotation shrank the screen in one direction.
     */
    fun clampInto(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Pos =
        Pos(clampX(x, w, screenW), clampY(y, h, screenH))

    /** True when [a] and [b] share some area (touching edges do not count). */
    fun overlaps(a: PxRect, b: PxRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * Where the [panel] can go so that it no longer covers the [box] (the green frame around the
     * answer): just above, below, left or right of it, [gap] away, whichever of those is entirely on
     * the screen and needs the shortest move. Android 12+ drops a tap that lands where two of an
     * app's overlay windows lie on top of each other and are together more than 80% opaque, so the
     * two must not overlap. Null when there is no such place (the caller then drops the box).
     */
    fun moveOffBox(panel: PxRect, box: PxRect, screenW: Int, screenH: Int, gap: Int): Pos? {
        val w = panel.width
        val h = panel.height
        val x = panel.left.coerceIn(0, maxOf(0, screenW - w))
        val y = panel.top.coerceIn(0, maxOf(0, screenH - h))
        val candidates = listOf(
            Pos(x, box.top - gap - h), // above
            Pos(x, box.bottom + gap), // below
            Pos(box.left - gap - w, y), // left
            Pos(box.right + gap, y), // right
        )
        return candidates
            .filter { it.x >= 0 && it.y >= 0 && it.x + w <= screenW && it.y + h <= screenH }
            .filter { !overlaps(PxRect(it.x, it.y, it.x + w, it.y + h), box) }
            .minByOrNull { Math.abs(it.x - panel.left) + Math.abs(it.y - panel.top) }
    }

    private fun clampX(x: Int, w: Int, screenW: Int) = x.coerceIn(0, maxOf(0, screenW - w))

    private fun clampY(y: Int, h: Int, screenH: Int) = y.coerceIn(0, maxOf(0, screenH - h))
}
