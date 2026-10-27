package com.clu.quickanswer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.clu.quickanswer.ocr.PxRect
import kotlin.math.abs
import kotlin.math.min

/**
 * The windows [OverlayService] draws over the game: the bubble, the answer panel, the preview of the
 * image last sent and the green box around the right option. Main thread only (views and window
 * manager calls). What a bubble touch should do is passed in as functions, and the bubble's colour
 * follows [isBusy] and [isAutoMode].
 */
internal class OverlayWindows(
    private val context: Context,
    private val handler: Handler,
    private val capture: ScreenCapture,
    private val hiddenAnswer: HiddenAnswer,
    private val isBusy: () -> Boolean,
    private val isAutoMode: () -> Boolean,
    /** The finger went down on the bubble: the user is about to ask. */
    private val onBubbleTouchDown: () -> Unit,
    /** A tap on the bubble (no drag, no long press). */
    private val onBubbleTap: () -> Unit,
    private val onBubbleLongPress: () -> Unit,
) {

    companion object {
        private const val LONG_PRESS_MS = 600L
        private const val PANEL_TOP_MARGIN_DP = 6
        private const val PREVIEW_AUTO_CLOSE_MS = 20_000L
        // Android 12+ drops touches that pass through another app's overlay window whose opacity is
        // above 0.8 (the "maximum obscuring opacity"), even with FLAG_NOT_TOUCHABLE. Keep the panel at
        // or below it, or the game never sees taps that land under the panel. Do not raise this.
        private const val PANEL_WINDOW_ALPHA = 0.8f
        private const val HIGHLIGHT_PAD_DP = 6
        private const val HIGHLIGHT_STROKE_DP = 4
        private const val HIGHLIGHT_COLOR = "#22C55E"
        // The box is moved to where it was asked to be only if the system put it this close to it.
        private const val HIGHLIGHT_FIX_MAX_DP = 200
    }

    private val wm: WindowManager by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    var bubble: TextView? = null
        private set
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: TextView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // Where the panel on screen was placed. Read from Prefs each time a panel is shown, so the
    // setting applies without restarting the service; rotation and bubble drags reuse this value.
    private var panelMode = PanelPlacement.Mode.TOP
    // Measured size of the answer panel, used to place it (see PanelPlacement).
    var panelAdded = false
        private set
    private var panelW = 0
    private var panelH = 0
    private val hidePanelRunnable = Runnable { hidePanel() }
    // What the panel on screen says and when it hides itself (SystemClock.uptimeMillis), so auto
    // mode can bring it back if it hid it for what turned out to be a blip.
    var panelText = ""
        private set
    var panelDeadline = 0L
        private set

    // Touchable preview of the last image sent, opened from the notification.
    var preview: View? = null
        private set
    private val closePreviewRunnable = Runnable { hidePreview() }

    // The green box around the right option (its own touch-through window), shown and hidden with the panel.
    var highlight: View? = null
        private set
    // The button it marks (screen pixels) and the screen size that was measured on; null without a box.
    var highlightBox: HiddenAnswer.Box? = null
        private set

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * With [on], the window's x/y count from the real top-left corner of the display. Without it the
     * window is laid out inside the system bars (and beside a display cutout), so a visible status
     * bar shifts it down by its height, which would put a bubble clamped to getRealMetrics partly
     * off the bottom of the screen after a rotation.
     */
    private fun useScreenCoordinates(p: WindowManager.LayoutParams, on: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        p.flags = if (on) p.flags or flag else p.flags and flag.inv()
        if (Build.VERSION.SDK_INT >= 30) {
            // FLAG_LAYOUT_IN_SCREEN frees the window of the status bar only; a visible navigation bar
            // (at the side in landscape) would still shift it by its width. No insets at all, then.
            p.fitInsetsTypes = if (on) 0 else android.view.WindowInsets.Type.systemBars()
        }
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = when {
                !on -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    fun createBubble() {
        val size = dp(56)
        val tv = TextView(context).apply {
            text = OverlayLook.bubbleText(OverlayLook.BubbleState.IDLE)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bubble = tv
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = capture.screenH / 3
        }
        // x/y are real screen coordinates, so the clamp after a rotation (getRealMetrics) is exact.
        useScreenCoordinates(params, true)
        bubbleParams = params
        updateBubbleStyle()

        val slop = dp(8)
        tv.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            var longFired = false
            val longRunnable = Runnable {
                longFired = true
                onBubbleLongPress()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // The user is about to ask: have a warm connection ready by the time the capture is sent.
                        onBubbleTouchDown()
                        startX = params.x
                        startY = params.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        longFired = false
                        handler.postDelayed(longRunnable, LONG_PRESS_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                            moved = true
                            handler.removeCallbacks(longRunnable)
                        }
                        if (moved) {
                            params.x = startX + dx
                            params.y = startY + dy
                            try {
                                wm.updateViewLayout(v, params)
                            } catch (_: Exception) {
                            }
                            followBubble()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(longRunnable)
                        if (!moved && !longFired) onBubbleTap()
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRunnable)
                }
                return true
            }
        })
        wm.addView(tv, params)
    }

    fun updateBubbleStyle() {
        val tv = bubble ?: return
        OverlayLook.styleBubble(tv, OverlayLook.bubbleState(isBusy(), isAutoMode()),
            context.resources.displayMetrics.density)
    }

    /** After a rotation the screen has new bounds: keep the bubble fully inside them. */
    fun clampBubble() {
        val v = bubble ?: return
        val params = bubbleParams ?: return
        val pos = PanelPlacement.clampInto(params.x, params.y, params.width, params.height, capture.screenW, capture.screenH)
        if (pos.x == params.x && pos.y == params.y) return
        params.x = pos.x
        params.y = pos.y
        try {
            wm.updateViewLayout(v, params)
        } catch (_: Exception) {
        }
    }

    /** Widest the panel may be in the current [panelMode] (see [PanelPlacement.maxWidth]). */
    private fun panelMaxWidth(): Int = PanelPlacement.maxWidth(panelMode, capture.screenW, capture.screenH)

    private fun createPanelView(): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        ellipsize = TextUtils.TruncateAt.END
        // Opaque: the window itself is drawn at PANEL_WINDOW_ALPHA, which makes it ~80% dark.
        background = OverlayLook.panelBackground(context.resources.displayMetrics.density)
    }

    /**
     * Shows the answer at the top of the screen or next to the bubble, as set in [Prefs.panelMode].
     * The panel window is not touchable, so every tap goes through to the app underneath (the
     * answer options of the game); it hides itself after [hideAfterMs] (longer for a list, see
     * [PanelPlacement.autoHideMs]). A list is aligned to the start, in reading order.
     */
    fun showPanel(text: String, hideAfterMs: Long = PanelPlacement.autoHideMs(text)) {
        hiddenAnswer.clear() // this answer replaces whatever auto mode had hidden
        hideHighlight() // the box of the answer before marks nothing now
        panelMode = Prefs.panelMode(context)
        val p = panel ?: createPanelView().also { panel = it }
        p.maxLines = PanelPlacement.maxLines(panelMode, text, landscape = capture.screenW > capture.screenH)
        p.text = text
        val list = ReplyShape.isList(text)
        p.gravity = if (list) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        p.textDirection = if (list) View.TEXT_DIRECTION_ANY_RTL else View.TEXT_DIRECTION_FIRST_STRONG
        measurePanel(p)
        val params = panelParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = PANEL_WINDOW_ALPHA
        }.also { panelParams = it }
        applyPanelSize(params)
        // Next to the bubble the panel must share the bubble's coordinates. At the top it is left
        // inside the system bars on purpose: overlay windows are drawn below the status bar, so a
        // panel at the real top edge would have the clock and icons painted over its text.
        useScreenCoordinates(params, panelMode == PanelPlacement.Mode.NEXT_TO_BUBBLE)
        placePanel(params)
        try {
            if (panelAdded) {
                wm.updateViewLayout(p, params)
            } else {
                wm.addView(p, params)
                panelAdded = true
            }
        } catch (_: Exception) {
        }
        handler.removeCallbacks(hidePanelRunnable)
        handler.postDelayed(hidePanelRunnable, hideAfterMs)
        panelText = text
        panelDeadline = SystemClock.uptimeMillis() + hideAfterMs
    }

    /** Measures the panel for the current text and screen size (width capped by [panelMaxWidth]). */
    private fun measurePanel(p: TextView) {
        p.maxWidth = panelMaxWidth()
        p.measure(
            View.MeasureSpec.makeMeasureSpec(capture.screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(capture.screenH, View.MeasureSpec.AT_MOST)
        )
        panelW = p.measuredWidth
        panelH = p.measuredHeight
    }

    /**
     * Gives the window exactly the measured size. A WRAP_CONTENT window is first measured by the
     * system at a narrower "preferred dialog width" (320dp), which would re-wrap long answers and
     * make them taller than the size [PanelPlacement] was given.
     */
    private fun applyPanelSize(params: WindowManager.LayoutParams) {
        params.width = panelW.coerceAtLeast(1)
        params.height = panelH.coerceAtLeast(1)
    }

    /** Puts the panel where [panelMode] says (see [PanelPlacement]) using the last measured panel size. */
    private fun placePanel(params: WindowManager.LayoutParams) {
        val pos = when (panelMode) {
            PanelPlacement.Mode.TOP ->
                PanelPlacement.top(panelW, panelH, capture.screenW, capture.screenH, dp(PANEL_TOP_MARGIN_DP))
            PanelPlacement.Mode.NEXT_TO_BUBBLE -> {
                val bp = bubbleParams ?: return
                PanelPlacement.place(bp.x, bp.y, bp.width, panelW, panelH, capture.screenW, capture.screenH, dp(8))
            }
        }
        params.x = pos.x
        params.y = pos.y
    }

    /** Keeps a panel that sits next to the bubble with it while the bubble is dragged. */
    private fun followBubble() {
        if (panelMode == PanelPlacement.Mode.NEXT_TO_BUBBLE) updatePanelPosition()
    }

    /** Re-places the visible panel (after the bubble moved or the screen size changed). */
    private fun updatePanelPosition() {
        val p = panel ?: return
        val params = panelParams ?: return
        if (!panelAdded) return
        placePanel(params)
        // Next to the bubble the panel's coordinates are screen coordinates, so it can be kept clear
        // of the green box without measuring (a panel at the top is checked once the box is laid out).
        val box = highlightBox
        if (box != null && panelMode == PanelPlacement.Mode.NEXT_TO_BUBBLE) {
            val now = PxRect(params.x, params.y, params.x + params.width, params.y + params.height)
            clearOfBox(now, paddedBox(box.rect))?.let {
                params.x = it.x
                params.y = it.y
            }
        }
        try {
            wm.updateViewLayout(p, params)
        } catch (_: Exception) {
        }
    }

    /** Re-measures and re-places the visible panel after the screen size changed (either mode). */
    fun refreshPanel() {
        val p = panel ?: return
        if (!panelAdded) return
        measurePanel(p)
        panelParams?.let { applyPanelSize(it) }
        updatePanelPosition()
    }

    fun hidePanel() {
        handler.removeCallbacks(hidePanelRunnable)
        hideHighlight()
        val p = panel ?: return
        if (!panelAdded) return
        panelAdded = false
        try {
            wm.removeView(p)
        } catch (_: Exception) {
        }
    }

    /**
     * Shows the picture behind the answer last shown, in a separate touchable window in the centre
     * of the screen (before any answer: whatever was sent last). Tap closes it; it also closes
     * itself after [PREVIEW_AUTO_CLOSE_MS]. [bmp] is that picture, null when there is none yet.
     */
    fun showLastImage(bmp: Bitmap?) {
        if (bmp == null) {
            Toast.makeText(context, "لا توجد صورة بعد", Toast.LENGTH_SHORT).show()
            return
        }
        hidePreview()
        val maxW = (capture.screenW * 0.8f).toInt()
        val maxH = (capture.screenH * 0.6f).toInt()
        val scale = min(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)

        val image = ImageView(context).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val caption = TextView(context).apply {
            text = "الصورة المرسلة — اضغط للإغلاق"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E6111111"))
            }
            addView(image, LinearLayout.LayoutParams(w, h))
            addView(
                caption,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            setOnClickListener { hidePreview() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            wm.addView(box, params)
        } catch (_: Exception) {
            return
        }
        preview = box
        handler.postDelayed(closePreviewRunnable, PREVIEW_AUTO_CLOSE_MS)
    }

    fun hidePreview() {
        handler.removeCallbacks(closePreviewRunnable)
        val v = preview ?: return
        preview = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    fun viewRect(v: View?): Rect? {
        if (v == null || !v.isAttachedToWindow || v.width == 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Screen rectangles of our own windows that are painted black in captures (bubble, panel, image preview). */
    fun overlayRects(): List<Rect> = listOfNotNull(viewRect(bubble), viewRect(panel), viewRect(preview))

    /**
     * What auto mode's change detection ignores: [overlayRects] plus the green box. The box is never
     * painted into a capture (it sits on an answer button the next question needs); it is hidden with
     * the panel before anything is sent instead.
     */
    fun ignoreRects(): List<Rect> = overlayRects() + listOfNotNull(viewRect(highlight))

    /** [r] grown by the room the green box leaves around a button. */
    private fun paddedBox(r: PxRect): PxRect {
        val pad = dp(HIGHLIGHT_PAD_DP)
        return PxRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
    }

    /** Draws the green box around [r] (screen pixels: captures are the real screen, 1:1). */
    fun showHighlight(r: PxRect) {
        hideHighlight()
        val box = paddedBox(r)
        val v = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(HIGHLIGHT_STROKE_DP), Color.parseColor(HIGHLIGHT_COLOR))
                cornerRadius = dp(12).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            box.width,
            box.height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = box.left
            y = box.top
            // Android 12+ only lets taps through overlays at most 80% opaque.
            alpha = PANEL_WINDOW_ALPHA
        }
        // Real display coordinates, the same as the capture's pixels.
        useScreenCoordinates(params, true)
        try {
            wm.addView(v, params)
            highlight = v
            highlightBox = HiddenAnswer.Box(r, capture.screenW, capture.screenH)
            // Once it is laid out: check where the system really put it, and keep the panel off it.
            v.post { settleHighlight(v, params, box) }
        } catch (_: Exception) {
        }
    }

    /**
     * After the green box is laid out: moves it to where it was asked to be if the system put it
     * somewhere else (an inset it did not drop), then moves the answer panel off it. The panel and
     * the box are each at the 0.8 opacity limit; where they overlap, Android 12+ adds their opacities
     * up (0.96) and drops the player's tap, so they must not overlap.
     */
    private fun settleHighlight(v: View, params: WindowManager.LayoutParams, box: PxRect) {
        if (highlight !== v || !v.isAttachedToWindow || v.width == 0) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val dx = box.left - loc[0]
        val dy = box.top - loc[1]
        val limit = dp(HIGHLIGHT_FIX_MAX_DP)
        if ((dx != 0 || dy != 0) && abs(dx) <= limit && abs(dy) <= limit) {
            params.x += dx
            params.y += dy
            try {
                wm.updateViewLayout(v, params)
            } catch (_: Exception) {
            }
        }
        val p = panel ?: return
        val wParams = panelParams ?: return
        if (!panelAdded) return
        val r = viewRect(p) ?: return
        val now = PxRect(r.left, r.top, r.right, r.bottom)
        val to = clearOfBox(now, box) ?: return
        wParams.x += to.x - now.left
        wParams.y += to.y - now.top
        try {
            wm.updateViewLayout(p, wParams)
        } catch (_: Exception) {
        }
    }

    /**
     * Where the panel, now at [now] (screen pixels), has to go to stop covering the green [box], or
     * null when it already does not. With no room anywhere the box is dropped instead.
     */
    private fun clearOfBox(now: PxRect, box: PxRect): PanelPlacement.Pos? {
        if (!PanelPlacement.overlaps(now, box)) return null
        val to = PanelPlacement.moveOffBox(now, box, capture.screenW, capture.screenH, dp(8))
        if (to == null) hideHighlight()
        return to
    }

    fun hideHighlight() {
        highlightBox = null
        val v = highlight ?: return
        highlight = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    /** Puts the green box of an answer that auto mode hid back, if the screen is still the one it was for. */
    fun restoreHighlight(box: HiddenAnswer.Box?) {
        if (box == null || box.screenW != capture.screenW || box.screenH != capture.screenH) return
        if (Prefs.highlightAnswer(context)) showHighlight(box.rect)
    }

    /** Removes the bubble window (on teardown, after the panel, preview and box are gone). */
    fun removeBubble() {
        bubble?.let {
            if (it.isAttachedToWindow) {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        bubble = null
    }
}
