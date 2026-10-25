package com.clu.quickanswer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.clu.quickanswer.CalibrationBoxes.Mode
import com.clu.quickanswer.ocr.PxRect
import kotlin.math.abs

/**
 * Shows [bitmap] scaled to fit and lets the user drag rectangles on it; the rectangles live in
 * [boxes] in pixels of the bitmap. A drag draws a box of the current [mode]; [onBoxDrawn] says
 * whether [boxes] took it (an option beyond the limit is refused).
 */
class CalibrationView(
    context: Context,
    private val bitmap: Bitmap,
    private val boxes: CalibrationBoxes,
    private val onBoxDrawn: (added: Boolean) -> Unit,
) : View(context) {

    var mode: Mode = Mode.QUESTION

    private val density = resources.displayMetrics.density
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val dst = RectF()
    private var fit: ImageFit? = null

    // The drag in progress, in view coordinates.
    private var dragging = false
    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fit = if (w > 0 && h > 0 && bitmap.width > 0 && bitmap.height > 0) ImageFit(w.toFloat(), h.toFloat(), bitmap.width, bitmap.height) else null
    }

    override fun onDraw(canvas: Canvas) {
        val f = fit ?: return
        dst.set(f.viewX(0), f.viewY(0), f.viewX(bitmap.width), f.viewY(bitmap.height))
        canvas.drawBitmap(bitmap, null, dst, imagePaint)

        boxes.question?.let { drawBox(canvas, f, it, QUESTION_COLOR, "السؤال") }
        boxes.numericField?.let { drawBox(canvas, f, it, FIELD_COLOR, "الرقم") }
        // The number on an option is its place in the order the template will use.
        for ((i, r) in boxes.readingOrder().withIndex()) drawBox(canvas, f, r, optionColor(i), (i + 1).toString())
        // A number key shows its digit, so a key drawn for the wrong digit is seen at once.
        for ((i, r) in boxes.keys.withIndex()) drawBox(canvas, f, r, KEY_COLOR, i.toString())
        boxes.submit?.let { drawBox(canvas, f, it, SUBMIT_COLOR, "تأكيد") }

        if (dragging) {
            val l = minOf(x0, x1)
            val t = minOf(y0, y1)
            val rr = maxOf(x0, x1)
            val b = maxOf(y0, y1)
            strokePaint.color = colorOf(mode)
            canvas.drawRect(l, t, rr, b, strokePaint)
        }
    }

    private fun drawBox(canvas: Canvas, f: ImageFit, r: PxRect, color: Int, label: String) {
        val l = f.viewX(r.left)
        val t = f.viewY(r.top)
        val rr = f.viewX(r.right)
        val b = f.viewY(r.bottom)
        fillPaint.color = (color and 0x00FFFFFF) or (0x33 shl 24)
        canvas.drawRect(l, t, rr, b, fillPaint)
        strokePaint.color = color
        canvas.drawRect(l, t, rr, b, strokePaint)
        // A pill with the label in the box's corner, so it reads on any picture.
        val pad = 4 * density
        val textW = labelPaint.measureText(label)
        val textH = labelPaint.textSize
        fillPaint.color = color
        canvas.drawRect(l, t, l + textW + 2 * pad, t + textH + 2 * pad, fillPaint)
        canvas.drawText(label, l + pad, t + pad + textH * 0.85f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = fit ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                x0 = event.x; y0 = event.y
                x1 = x0; y1 = y0
            }
            MotionEvent.ACTION_MOVE -> {
                x1 = event.x; y1 = event.y
            }
            MotionEvent.ACTION_UP -> {
                x1 = event.x; y1 = event.y
                dragging = false
                if (abs(x1 - x0) >= MIN_DRAG_DP * density && abs(y1 - y0) >= MIN_DRAG_DP * density) {
                    onBoxDrawn(boxes.add(mode, f.rectBetween(x0, y0, x1, y1)))
                } else if (mode == Mode.KEY || mode == Mode.SUBMIT) {
                    // A key or the confirm button is placed with one tap on its middle.
                    onBoxDrawn(boxes.add(mode, boxes.boxAround(f.imageX(x1), f.imageY(y1), bitmap.width, bitmap.height)))
                }
                // Otherwise a tap, or a drag too short to mean a box, draws nothing.
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
            else -> return true
        }
        invalidate()
        return true
    }

    companion object {
        /** A drag shorter than this (dp) on either side is an accidental touch, not a box. */
        private const val MIN_DRAG_DP = 12

        const val QUESTION_COLOR = 0xFF1E88E5.toInt()
        const val FIELD_COLOR = 0xFFFFB300.toInt()
        const val KEY_COLOR = 0xFF00897B.toInt()
        const val SUBMIT_COLOR = 0xFF5E35B1.toInt()

        /** Options cycle through these, so neighbours are told apart; none is the question's blue or the field's amber. */
        private val OPTION_COLORS = intArrayOf(
            0xFF43A047.toInt(), 0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(),
            0xFFD81B60.toInt(), 0xFFFB8C00.toInt(), 0xFF6D4C41.toInt(), 0xFF7CB342.toInt(),
        )

        fun optionColor(index: Int): Int = OPTION_COLORS[index % OPTION_COLORS.size]

        fun colorOf(mode: Mode): Int = when (mode) {
            Mode.QUESTION -> QUESTION_COLOR
            Mode.FIELD -> FIELD_COLOR
            Mode.OPTION -> OPTION_COLORS[0]
            Mode.KEY -> KEY_COLOR
            Mode.SUBMIT -> SUBMIT_COLOR
        }
    }
}
