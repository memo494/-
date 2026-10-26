package com.clu.quickanswer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    /**
     * A tap (not a drag) at picture pixel (x, y): the screen decides what it means in the current
     * mode (a key read under the tap, a field or button grown from it). Returns whether it placed a
     * box; false lets the view do nothing.
     */
    private val onTap: (x: Int, y: Int) -> Boolean = { _, _ -> false },
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
    private val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = labelSize
        isFakeBoldText = true
    }
    private val dst = RectF()
    private var fit: ImageFit? = null

    /**
     * Zoom on top of the fitted picture: two fingers pinch and move it, the zoom button steps it.
     * Boxes are drawn and touches read through it, so drawing works the same when zoomed in.
     */
    private val zoom = Matrix()
    private val inverse = Matrix()
    private var zoomLevel = 1f
    private var twoFingers = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private val point = FloatArray(2)
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (zoomLevel * d.scaleFactor).coerceIn(1f, MAX_ZOOM)
            zoom.postScale(next / zoomLevel, next / zoomLevel, d.focusX, d.focusY)
            zoomLevel = next
            keepInside()
            invalidate()
            return true
        }
    })

    /** Zooms in a step around ([x], [y]) in view coordinates, or back to the whole picture after the last step. */
    fun zoomStep(x: Float = width / 2f, y: Float = height / 2f) {
        if (zoomLevel >= MAX_ZOOM - 0.01f) {
            zoom.reset()
            zoomLevel = 1f
        } else {
            val next = minOf(MAX_ZOOM, zoomLevel * ZOOM_STEP)
            zoom.postScale(next / zoomLevel, next / zoomLevel, x, y)
            zoomLevel = next
            keepInside()
        }
        invalidate()
    }

    /** Where the user last drew or tapped, in view coordinates: the zoom button zooms there. */
    var lastTouchX = Float.NaN
        private set
    var lastTouchY = Float.NaN
        private set

    /** The picture never leaves gaps at the edges when zoomed (it always covers the view as it did unzoomed). */
    private fun keepInside() {
        val v = FloatArray(9)
        zoom.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        val minX = width - width * scale
        val minY = height - height * scale
        val tx = v[Matrix.MTRANS_X].coerceIn(minOf(0f, minX), 0f)
        val ty = v[Matrix.MTRANS_Y].coerceIn(minOf(0f, minY), 0f)
        zoom.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
    }

    /** A view point through the zoom back to the unzoomed view, where [fit] maps it to the picture. */
    private fun unzoom(x: Float, y: Float): FloatArray {
        zoom.invert(inverse)
        point[0] = x; point[1] = y
        inverse.mapPoints(point)
        return point
    }

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
        canvas.save()
        canvas.concat(zoom)
        // Lines and labels keep their size on screen at any zoom.
        strokePaint.strokeWidth = 3 * density / zoomLevel
        labelPaint.textSize = labelSize / zoomLevel
        dst.set(f.viewX(0), f.viewY(0), f.viewX(bitmap.width), f.viewY(bitmap.height))
        canvas.drawBitmap(bitmap, null, dst, imagePaint)

        boxes.question?.let { drawBox(canvas, f, it, QUESTION_COLOR, "السؤال") }
        boxes.numericField?.let { drawBox(canvas, f, it, FIELD_COLOR, "الرقم") }
        // The number on an option is its place in the order the template will use.
        for ((i, r) in boxes.readingOrder().withIndex()) drawBox(canvas, f, r, optionColor(i), (i + 1).toString())
        // A number key shows its digit, so a key drawn for the wrong digit is seen at once.
        for ((i, r) in boxes.keyAt.withIndex()) if (r != null) drawBox(canvas, f, r, KEY_COLOR, i.toString())
        boxes.submit?.let { drawBox(canvas, f, it, SUBMIT_COLOR, "تأكيد") }
        canvas.restore()
        strokePaint.strokeWidth = 3 * density
        labelPaint.textSize = labelSize

        if (dragging && !twoFingers) {
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
        val pad = 4 * density / zoomLevel
        val textW = labelPaint.measureText(label)
        val textH = labelPaint.textSize
        fillPaint.color = color
        canvas.drawRect(l, t, l + textW + 2 * pad, t + textH + 2 * pad, fillPaint)
        canvas.drawText(label, l + pad, t + pad + textH * 0.85f, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = fit ?: return false
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                twoFingers = false
                x0 = event.x; y0 = event.y
                x1 = x0; y1 = y0
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger zooms and moves the picture; the box being drawn is dropped.
                twoFingers = true
                dragging = false
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingers && event.pointerCount >= 2) {
                    val fx = focusX(event); val fy = focusY(event)
                    zoom.postTranslate(fx - lastFocusX, fy - lastFocusY)
                    keepInside()
                    lastFocusX = fx; lastFocusY = fy
                } else {
                    x1 = event.x; y1 = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                lastFocusX = focusX(event); lastFocusY = focusY(event)
            }
            MotionEvent.ACTION_UP -> {
                x1 = event.x; y1 = event.y
                val wasDrawing = dragging && !twoFingers
                dragging = false
                if (wasDrawing) {
                    lastTouchX = x1; lastTouchY = y1
                    val a = unzoom(x0, y0).copyOf()
                    val b = unzoom(x1, y1).copyOf()
                    if (abs(x1 - x0) >= MIN_DRAG_DP * density && abs(y1 - y0) >= MIN_DRAG_DP * density) {
                        onBoxDrawn(boxes.add(mode, f.rectBetween(a[0], a[1], b[0], b[1])))
                    } else {
                        // A tap: the screen decides what is under it (a key, a field, a button).
                        val ix = f.imageX(b[0]); val iy = f.imageY(b[1])
                        if (onTap(ix, iy)) onBoxDrawn(true)
                        else if (mode == Mode.KEY || mode == Mode.SUBMIT) {
                            onBoxDrawn(boxes.add(mode, boxes.boxAround(ix, iy, bitmap.width, bitmap.height)))
                        }
                    }
                }
                twoFingers = false
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; twoFingers = false }
            else -> return true
        }
        invalidate()
        return true
    }

    /** The centre of the fingers still down (one lifting is left out). */
    private fun focusX(e: MotionEvent): Float {
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
        return if (n > 0) sum / n else e.x
    }

    private fun focusY(e: MotionEvent): Float {
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var sum = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
        return if (n > 0) sum / n else e.y
    }

    companion object {
        /** A drag shorter than this (dp) on either side is an accidental touch, not a box. */
        private const val MIN_DRAG_DP = 12

        /** Deepest zoom: a key of a phone game then fills a good part of the screen. */
        private const val MAX_ZOOM = 4f

        /** Each press of the zoom button multiplies the zoom by this, until [MAX_ZOOM]. */
        private const val ZOOM_STEP = 2f

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
