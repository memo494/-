package com.clu.quickanswer

import com.clu.quickanswer.ocr.LayoutTemplate
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.RegionGeometry
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The boxes drawn so far on the manual calibration screen, in pixels of the capture (not of the
 * view, so they survive a rotation or a resize). Pure Kotlin, no android.*: the view only draws
 * them and feeds them to [com.clu.quickanswer.ocr.ManualTemplate].
 */
class CalibrationBoxes {

    /** What the next box drawn is. */
    enum class Mode {
        QUESTION, OPTION, FIELD,
        /** The next number key, 0 first then 1..9 ([nextKey]); see [com.clu.quickanswer.ocr.CalibratedKeypad]. */
        KEY,
        /** The game's confirm button for a typed number; a new one replaces the old one. */
        SUBMIT,
    }

    /** One step to undo: the [mode] drawn and what it replaced ([previous], for the question and the field). */
    private class Step(val mode: Mode, val previous: PxRect?, val previousKeys: List<PxRect>? = null)

    var question: PxRect? = null
        private set
    var numericField: PxRect? = null
        private set
    var submit: PxRect? = null
        private set
    private val drawn = ArrayList<PxRect>()
    private val drawnKeys = ArrayList<PxRect>()

    /** The number keys drawn so far, index = digit. */
    val keys: List<PxRect> get() = drawnKeys

    /** The digit the next [Mode.KEY] box is for, or null when all ten are drawn. */
    val nextKey: Char? get() = if (drawnKeys.size < 10) '0' + drawnKeys.size else null
    private val history = ArrayList<Step>()

    /** The options in the order they were drawn. */
    val options: List<PxRect> get() = drawn

    val isEmpty: Boolean get() = question == null && numericField == null && drawn.isEmpty() &&
        drawnKeys.isEmpty() && submit == null

    /**
     * Adds [rect] as a [mode] box: a question or a number field replaces the one already there,
     * an option is added. False (nothing changed) for an option beyond [LayoutTemplate.MAX_OPTIONS].
     */
    fun add(mode: Mode, rect: PxRect): Boolean {
        when (mode) {
            Mode.QUESTION -> {
                history.add(Step(mode, question))
                question = rect
            }
            Mode.FIELD -> {
                history.add(Step(mode, numericField))
                numericField = rect
            }
            Mode.OPTION -> {
                if (drawn.size >= LayoutTemplate.MAX_OPTIONS) return false
                history.add(Step(mode, null))
                drawn.add(rect)
            }
            Mode.KEY -> {
                if (nextKey == null) return false
                history.add(Step(mode, null))
                drawnKeys.add(rect)
            }
            Mode.SUBMIT -> {
                history.add(Step(mode, submit))
                submit = rect
            }
        }
        return true
    }

    /**
     * Replaces the number keys with [found] (ten boxes, index = digit), e.g. from automatic detection.
     * One [undo] takes them all back. False, nothing changed, unless there are exactly ten.
     */
    fun setKeys(found: List<PxRect>): Boolean {
        if (found.size != 10) return false
        history.add(Step(Mode.KEY, null, drawnKeys.toList()))
        drawnKeys.clear()
        drawnKeys.addAll(found)
        return true
    }

    /**
     * The box for a key or button the user TAPPED at ([x], [y]) on a [width] x [height] capture: a
     * square the size of the keys already there (their median side), or [defaultShare] of the
     * shorter side for the first one, centred on the tap and kept inside the capture. Tapping is
     * easier than dragging ten small boxes, and the taps only need the key's middle.
     */
    fun boxAround(x: Int, y: Int, width: Int, height: Int, defaultShare: Float = DEFAULT_KEY_SHARE): PxRect {
        val sides = drawnKeys.map { minOf(it.width, it.height) }.sorted()
        val side = (if (sides.isNotEmpty()) sides[sides.size / 2] else (minOf(width, height) * defaultShare).toInt())
            .coerceIn(MIN_TAP_SIDE, maxOf(MIN_TAP_SIDE, minOf(width, height)))
        val left = (x - side / 2).coerceIn(0, maxOf(0, width - side))
        val top = (y - side / 2).coerceIn(0, maxOf(0, height - side))
        return PxRect(left, top, minOf(width, left + side), minOf(height, top + side))
    }

    /** Takes back the box drawn last (a replaced question or field comes back). False when there is none. */
    fun undo(): Boolean {
        val step = history.removeLastOrNull() ?: return false
        when (step.mode) {
            Mode.QUESTION -> question = step.previous
            Mode.FIELD -> numericField = step.previous
            Mode.OPTION -> drawn.removeAt(drawn.size - 1)
            Mode.KEY -> if (step.previousKeys != null) {
                drawnKeys.clear()
                drawnKeys.addAll(step.previousKeys)
            } else drawnKeys.removeAt(drawnKeys.size - 1)
            Mode.SUBMIT -> submit = step.previous
        }
        return true
    }

    fun clear() {
        question = null
        numericField = null
        drawn.clear()
        drawnKeys.clear()
        submit = null
        history.clear()
    }

    /**
     * The options in the order the template will list them (and so the order of the readers'
     * options): rows top to bottom, right to left inside a row. What the numbers on the boxes show.
     */
    fun readingOrder(): List<PxRect> = RegionGeometry.readingRows(drawn, leftToRight = false) { it }.flatten()

    companion object {
        /** Share of the capture's shorter side for the first tapped key: a game key is usually 8-12%. */
        const val DEFAULT_KEY_SHARE = 0.07f

        /** Smallest tapped box (px): what a touch can still hit and a signature can still be measured on. */
        const val MIN_TAP_SIDE = 24
    }
}

/**
 * A picture of [imageW] x [imageH] pixels drawn as large as fits into a [viewW] x [viewH] view,
 * centred, aspect kept (so there are bars on two sides). Maps between view coordinates and picture
 * pixels. Both sizes must be positive.
 */
class ImageFit(val viewW: Float, val viewH: Float, val imageW: Int, val imageH: Int) {
    init {
        require(viewW > 0f && viewH > 0f && imageW > 0 && imageH > 0) { "sizes must be positive" }
    }

    val scale: Float = min(viewW / imageW, viewH / imageH)
    val offsetX: Float = (viewW - imageW * scale) / 2f
    val offsetY: Float = (viewH - imageH * scale) / 2f

    fun viewX(imageX: Int): Float = offsetX + imageX * scale
    fun viewY(imageY: Int): Float = offsetY + imageY * scale

    /** The picture column under view coordinate [x], clamped to 0..[imageW] (a point in a bar is the picture's edge). */
    fun imageX(x: Float): Int = ((x - offsetX) / scale).roundToInt().coerceIn(0, imageW)
    fun imageY(y: Float): Int = ((y - offsetY) / scale).roundToInt().coerceIn(0, imageH)

    /** The picture rectangle spanned by a drag from ([x0], [y0]) to ([x1], [y1]) in view coordinates, in any direction. */
    fun rectBetween(x0: Float, y0: Float, x1: Float, y1: Float): PxRect {
        val ax = imageX(x0)
        val bx = imageX(x1)
        val ay = imageY(y0)
        val by = imageY(y1)
        return PxRect(min(ax, bx), min(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }
}
