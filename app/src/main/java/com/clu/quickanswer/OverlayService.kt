package com.clu.quickanswer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.clu.quickanswer.ocr.ArabicOcr
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        /**
         * Set while the bubble runs: asks for an answer, as a bubble tap does. [VolumeKeyService]
         * calls it on the main thread when volume down is pressed; null means the key is left alone.
         */
        @Volatile var volumeTrigger: (() -> Unit)? = null
        private const val CHANNEL_ID = "clu_overlay"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.clu.quickanswer.STOP"
        private const val ACTION_LAST_IMAGE = "com.clu.quickanswer.LAST_IMAGE"
        private const val AUTO_INTERVAL_MS = 1500L
        private const val SIG_SIZE = 32
        private const val CHANGE_THRESHOLD = 6.0
        private const val STABLE_THRESHOLD = 2.0
        private const val LONG_PRESS_MS = 600L
        private const val PANEL_AUTO_HIDE_MS = 15_000L
        private const val PANEL_TOP_MARGIN_DP = 6
        private const val PANEL_TOP_MAX_LINES = 2
        private const val PANEL_BUBBLE_MAX_LINES = 4
        private const val PREVIEW_AUTO_CLOSE_MS = 20_000L
        // An answer that auto mode hid is brought back only if it still has this long to live.
        private const val HIDDEN_ANSWER_MIN_LEFT_MS = 1_000L
        // Android 12+ drops touches that pass through another app's overlay window whose opacity is
        // above 0.8 (the "maximum obscuring opacity"), even with FLAG_NOT_TOUCHABLE. Keep the panel at
        // or below it, or the game never sees taps that land under the panel. Do not raise this.
        private const val PANEL_WINDOW_ALPHA = 0.8f

        // A second tap within this time after an answer from memory asks the model again and
        // overwrites the remembered answer (the way to correct a wrong one).
        private const val CORRECTION_WINDOW_MS = 20_000L
        private const val HIGHLIGHT_PAD_DP = 6
        private const val HIGHLIGHT_STROKE_DP = 4
        private const val HIGHLIGHT_COLOR = "#22C55E"
        private const val SHOWN_IMAGE_MAX_SIDE = 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var density = 0

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: TextView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // Where the panel on screen was placed. Read from Prefs each time a panel is shown, so the
    // setting applies without restarting the service; rotation and bubble drags reuse this value.
    private var panelMode = PanelPlacement.Mode.TOP
    // Measured size of the answer panel, used to place it (see PanelPlacement).
    private var panelAdded = false
    private var panelW = 0
    private var panelH = 0
    private val hidePanelRunnable = Runnable { hidePanel() }
    // What the panel on screen says and when it hides itself (SystemClock.uptimeMillis), so auto
    // mode can bring it back if it hid it for what turned out to be a blip.
    private var panelText = ""
    private var panelDeadline = 0L
    private val hiddenAnswer = HiddenAnswer()
    // The picture that goes with the answer last shown: what "آخر صورة" previews. Not client.lastSent,
    // which also moves on to auto-mode sends whose "no question" reply is never shown.
    private var shownImage: Bitmap? = null

    // Touchable preview of the last image sent, opened from the notification.
    private var preview: View? = null
    private val closePreviewRunnable = Runnable { hidePreview() }

    private var autoMode = false
    private var autoJob: Job? = null
    @Volatile private var busy = false
    private var lastSentSig: IntArray? = null
    private var prevTickSig: IntArray? = null
    private var client: Answerer? = null
    // The virtual display only emits frames when the screen changes, so keep the last one.
    private var lastFrame: Bitmap? = null

    // On-device question reader; loaded in the background when the service starts (null until then,
    // or if it cannot load on this phone). Every use holds ocrLock, so it is never closed mid-read.
    private val ocrLock = Any()
    private var ocr: ArabicOcr? = null
    private var destroyed = false

    // The green box around the right option (its own touch-through window), shown and hidden with the panel.
    private var highlight: View? = null

    // The last answer that came from memory, for the correction tap (see CORRECTION_WINDOW_MS).
    private var lastHitKey: String? = null
    private var lastHitAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LAST_IMAGE) {
            // Only meaningful while the bubble is running; a stale tap must not start a half-initialised service.
            if (projection == null) stopSelf() else showLastImage()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+: foreground service must be started before getMediaProjection().
        startAsForeground()

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        GeminiRuntime.init(this)
        client = if (Prefs.provider(this) == "claude") ClaudeClient(Prefs.claudeKey(this), Prefs.claudeModel(this))
        else GeminiClient(Prefs.apiKey(this), Prefs.model(this))
        warmUp()
        AnswerStore.init(this)
        loadOcr()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            Toast.makeText(this, "تعذر بدء التقاط الشاشة", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        setupCapture(proj)
        createBubble()
        volumeTrigger = { answerNow() }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Clu", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val lastImageIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_LAST_IMAGE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Clu")
            .setContentText("الفقاعة تعمل")
            .setOngoing(true)
            .addAction(0, "إيقاف", stopIntent)
            .addAction(0, "آخر صورة", lastImageIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // New screen size: the bubble may now be off-screen (landscape -> portrait), so pull it
        // back in first, then re-measure the panel (max width depends on orientation) and re-place
        // it (it follows the bubble in NEXT_TO_BUBBLE mode). The centred preview is sized for the
        // old screen, so just close it.
        if (resizeCapture()) clampBubble()
        refreshPanel()
        hidePreview()
        // The box marks a button of the old layout.
        hideHighlight()
    }

    /**
     * The virtual display keeps the size it was created with, so after rotating to landscape the
     * screen would be squeezed into a portrait frame. Match it to the current orientation.
     * Returns true when the screen size changed.
     */
    private fun resizeCapture(): Boolean {
        val vd = virtualDisplay ?: return false
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return false
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        density = dm.densityDpi
        val old = imageReader
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        vd.resize(screenW, screenH, density)
        vd.surface = reader.surface
        old?.close()
        lastFrame?.recycle()
        lastFrame = null
        // The auto mode's previous signature describes the old orientation.
        prevTickSig = null
        return true
    }

    /** After a rotation the screen has new bounds: keep the bubble fully inside them. */
    private fun clampBubble() {
        val v = bubble ?: return
        val params = bubbleParams ?: return
        val pos = PanelPlacement.clampInto(params.x, params.y, params.width, params.height, screenW, screenH)
        if (pos.x == params.x && pos.y == params.y) return
        params.x = pos.x
        params.y = pos.y
        try {
            wm.updateViewLayout(v, params)
        } catch (_: Exception) {
        }
    }

    private fun setupCapture(proj: MediaProjection) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        density = dm.densityDpi
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = proj.createVirtualDisplay(
            "CluCapture", screenW, screenH, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    // ---------------------------------------------------------------- overlays

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = when {
                !on -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun createBubble() {
        val size = dp(56)
        val tv = TextView(this).apply {
            text = "؟"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
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
            y = screenH / 3
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
                toggleAuto()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // The user is about to ask: have a warm connection ready by the time the capture is sent.
                        warmUp()
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
                        if (!moved && !longFired) answerNow()
                    }
                    MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longRunnable)
                }
                return true
            }
        })
        wm.addView(tv, params)
    }

    /** Lets the answer provider open its connection early. Never touches the network on the main thread. */
    private fun warmUp() {
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            try {
                c.warmUp()
            } catch (_: Exception) {
            }
        }
    }

    private fun updateBubbleStyle() {
        val tv = bubble ?: return
        val color = when {
            busy -> Color.parseColor("#F59E0B")
            autoMode -> Color.parseColor("#16A34A")
            else -> Color.parseColor("#2563EB")
        }
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
        tv.text = when {
            busy -> "…"
            autoMode -> "A"
            else -> "؟"
        }
    }

    /** Widest the panel may be in the current [panelMode] (see [PanelPlacement.maxWidth]). */
    private fun panelMaxWidth(): Int = PanelPlacement.maxWidth(panelMode, screenW, screenH)

    private fun createPanelView(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        ellipsize = TextUtils.TruncateAt.END
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            // Opaque: the window itself is drawn at PANEL_WINDOW_ALPHA, which makes it ~80% dark.
            setColor(Color.parseColor("#FF111111"))
        }
    }

    /**
     * Shows the answer at the top of the screen or next to the bubble, as set in [Prefs.panelMode].
     * The panel window is not touchable, so every tap goes through to the app underneath (the
     * answer options of the game); it hides itself after [hideAfterMs].
     */
    private fun showPanel(text: String, hideAfterMs: Long = PANEL_AUTO_HIDE_MS) {
        hiddenAnswer.clear() // this answer replaces whatever auto mode had hidden
        panelMode = Prefs.panelMode(this)
        val p = panel ?: createPanelView().also { panel = it }
        p.maxLines = if (panelMode == PanelPlacement.Mode.TOP) PANEL_TOP_MAX_LINES else PANEL_BUBBLE_MAX_LINES
        p.text = text
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
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST)
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
                PanelPlacement.top(panelW, panelH, screenW, screenH, dp(PANEL_TOP_MARGIN_DP))
            PanelPlacement.Mode.NEXT_TO_BUBBLE -> {
                val bp = bubbleParams ?: return
                PanelPlacement.place(bp.x, bp.y, bp.width, panelW, panelH, screenW, screenH, dp(8))
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
        try {
            wm.updateViewLayout(p, params)
        } catch (_: Exception) {
        }
    }

    /** Re-measures and re-places the visible panel after the screen size changed (either mode). */
    private fun refreshPanel() {
        val p = panel ?: return
        if (!panelAdded) return
        measurePanel(p)
        panelParams?.let { applyPanelSize(it) }
        updatePanelPosition()
    }

    private fun hidePanel() {
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
     * itself after [PREVIEW_AUTO_CLOSE_MS].
     */
    private fun showLastImage() {
        val bmp = shownImage ?: client?.lastSent
        if (bmp == null) {
            Toast.makeText(this, "لا توجد صورة بعد", Toast.LENGTH_SHORT).show()
            return
        }
        hidePreview()
        val maxW = (screenW * 0.8f).toInt()
        val maxH = (screenH * 0.6f).toInt()
        val scale = min(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)

        val image = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val caption = TextView(this).apply {
            text = "الصورة المرسلة — اضغط للإغلاق"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val box = LinearLayout(this).apply {
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

    private fun hidePreview() {
        handler.removeCallbacks(closePreviewRunnable)
        val v = preview ?: return
        preview = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun viewRect(v: View?): Rect? {
        if (v == null || !v.isAttachedToWindow || v.width == 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    // ---------------------------------------------------------------- capture

    /**
     * Must be called on the main thread (reads view geometry). Returns a bitmap with our overlays
     * painted over. With [newOnly], returns null when no new frame arrived since the last capture.
     */
    private fun captureMasked(newOnly: Boolean = false): Bitmap? {
        val fresh = readFrame()
        if (fresh != null) {
            lastFrame?.recycle()
            lastFrame = fresh
        } else if (newOnly) {
            return null
        }
        val bmp = lastFrame?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (r in overlayRects()) canvas.drawRect(r, paint)
        return bmp
    }

    /** Screen rectangles of our own windows that are painted black in captures (bubble, panel, image preview). */
    private fun overlayRects(): List<Rect> = listOfNotNull(viewRect(bubble), viewRect(panel), viewRect(preview))

    /**
     * What auto mode's change detection ignores: [overlayRects] plus the green box. The box is never
     * painted into a capture (it sits on an answer button the next question needs); it is hidden with
     * the panel before anything is sent instead.
     */
    private fun ignoreRects(): List<Rect> = overlayRects() + listOfNotNull(viewRect(highlight))

    private fun readFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        val bmp: Bitmap
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val wide = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            wide.copyPixelsFromBuffer(plane.buffer)
            bmp = if (wide.width != image.width) {
                Bitmap.createBitmap(wide, 0, 0, image.width, image.height).also { wide.recycle() }
            } else wide
        } finally {
            image.close()
        }
        return bmp
    }

    /**
     * Luminance grid of the frame. Cells under one of [overlays] (our own windows) are marked
     * ignored: the panel appearing, moving or auto-hiding must not look like the game changing.
     */
    private fun signature(bmp: Bitmap, overlays: List<Rect>): IntArray {
        val w = bmp.width
        val h = bmp.height
        val small = Bitmap.createScaledBitmap(bmp, SIG_SIZE, SIG_SIZE, true)
        val px = IntArray(SIG_SIZE * SIG_SIZE)
        small.getPixels(px, 0, SIG_SIZE, 0, 0, SIG_SIZE, SIG_SIZE)
        if (small !== bmp) small.recycle()
        val sig = IntArray(px.size) {
            val c = px[it]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        for (r in overlays) FrameSignature.ignoreRect(sig, SIG_SIZE, w, h, r.left, r.top, r.right, r.bottom)
        return sig
    }

    // ---------------------------------------------------------------- actions

    private fun setBusy(b: Boolean) {
        busy = b
        updateBubbleStyle()
    }

    private fun answerNow() {
        if (busy) return
        hiddenAnswer.clear()
        if (panelAdded || preview != null) {
            // Remove the old answer (and the sent-image preview) first so they don't hide part of the question.
            hidePanel()
            hidePreview()
            setBusy(true)
            handler.postDelayed({
                setBusy(false)
                answerNow()
            }, 250)
            return
        }
        val bmp = captureMasked()
        if (bmp == null) {
            showPanel("لم يتم الحصول على إجابة")
            return
        }
        // In auto mode count this as the last frame sent, or the next tick would see the question as
        // "new", hide this answer and ask again.
        sendFrame(bmp, if (autoMode) signature(bmp, ignoreRects()) else null, fromAuto = false)
    }

    /**
     * Sends [bmp] and shows the answer. A send that auto mode started on its own ([fromAuto]) shows
     * nothing when the model says there is no question: that is a screen that is not the quiz
     * (the notification shade, another app, Clu's settings), and it must neither cover the screen
     * nor replace the picture "آخر صورة" shows. The screen still counts as sent, so it is not asked again.
     */
    private fun sendFrame(bmp: Bitmap, sig: IntArray?, fromAuto: Boolean) {
        setBusy(true)
        val bypass = if (!fromAuto && SystemClock.uptimeMillis() - lastHitAt < CORRECTION_WINDOW_MS) lastHitKey else null
        val capturedW = bmp.width
        val capturedH = bmp.height
        scope.launch {
            val solved = withContext(Dispatchers.IO) {
                try {
                    solve(bmp, bypass)
                } catch (e: Exception) {
                    Solved("خطأ: ${e.javaClass.simpleName}", null, null, null)
                } finally {
                    bmp.recycle()
                }
            }
            if (sig != null) lastSentSig = sig
            setBusy(false)
            if (fromAuto && ScreenImage.isNoQuestion(solved.text)) return@launch
            lastHitKey = solved.memoryKey
            lastHitAt = if (solved.memoryKey != null) SystemClock.uptimeMillis() else 0L
            shownImage = solved.image ?: shownImage
            showPanel(solved.text)
            // The box marks a button in the captured frame; skip it if the screen rotated meanwhile.
            val rect = solved.rect
            if (rect != null && capturedW == screenW && capturedH == screenH && Prefs.highlightAnswer(this@OverlayService)) {
                showHighlight(rect)
            }
        }
    }

    /**
     * What one answer produced: the text for the bar, the option's button to box (screen pixels),
     * the picture "آخر صورة" shows, and the memory entry it came from (null if not from memory).
     */
    private class Solved(val text: String, val rect: PxRect?, val image: Bitmap?, val memoryKey: String?)

    /**
     * Answers the question on [bmp] (blocking; run off the main thread):
     * 1. Read it on the device. If that works: answer from memory when the question was seen before
     *    (unless it is [bypassKey], the answer being corrected), else ask the model with text only and
     *    remember the answer.
     * 2. Otherwise, or when the text answer cannot be used, send the screenshot as before. If the
     *    question was read, an image answer that names one of its options still gets the box and is
     *    remembered.
     */
    private fun solve(bmp: Bitmap, bypassKey: String?): Solved {
        val c = client ?: return Solved("خطأ", null, null, null)
        val memory = AnswerStore.memory
        val q = if (Prefs.smartReading(this)) readQuestion(bmp) else null
        if (q != null) {
            val hit = memory.lookup(q.questionText, q.options, q.numeric)
            if (hit != null && hit.key != bypassKey) {
                AnswerParser.resolveStored(q, hit.answer)?.let { r ->
                    return Solved("⚡ ${r.answer}", r.rect, shownCopy(bmp), hit.key)
                }
            }
            val reply = c.answerText(QuestionPrompt.build(q))
            when (reply.kind) {
                Reply.Kind.ANSWER -> AnswerParser.resolve(q, reply.text)?.let { r ->
                    // A corrected answer replaces the old entry (a fuzzy hit may have another key).
                    if (hit != null && hit.key == bypassKey) memory.remove(hit.key)
                    memory.put(q.questionText, q.options, q.numeric, r.answer)
                    return Solved(r.answer, r.rect, shownCopy(bmp), null)
                }
                // Quota, key, region, no connection: the image request would fail the same way.
                Reply.Kind.ERROR -> return Solved(reply.text, null, null, null)
                Reply.Kind.EMPTY -> {}
            }
        }
        val text = c.answer(bmp)
        if (q != null) {
            val r = if (q.numeric) {
                // Only a bare number counts (an error message can contain digits too).
                text.trim().takeIf { t -> t.isNotEmpty() && t.all { it.isDigit() || it in ".,-٫٬ " } }
                    ?.let { AnswerParser.number(it) }?.let { AnswerParser.Resolved(it, null) }
            } else {
                AnswerParser.resolveImageAnswer(q, text)
            }
            if (r != null) {
                memory.put(q.questionText, q.options, q.numeric, r.answer)
                return Solved(r.answer, r.rect, c.lastSent, null)
            }
        }
        return Solved(text, null, c.lastSent, null)
    }

    /** The question on [bmp], or null when it cannot be read (or the reader is not loaded). */
    private fun readQuestion(bmp: Bitmap): Question? = try {
        synchronized(ocrLock) { ocr?.read(bmp) }
    } catch (t: Throwable) {
        null
    }

    /** A smaller copy of [bmp] for "آخر صورة" (the caller recycles [bmp]). */
    private fun shownCopy(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= SHOWN_IMAGE_MAX_SIDE) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val s = SHOWN_IMAGE_MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
    }

    /** Loads the on-device reader in the background; the app works without it (image path). */
    private fun loadOcr() {
        scope.launch(Dispatchers.IO) {
            val loaded = try {
                ArabicOcr.create(this@OverlayService)
            } catch (t: Throwable) {
                null
            }
            synchronized(ocrLock) {
                if (destroyed) loaded?.close() else ocr = loaded
            }
        }
    }

    /** Draws the green box around [r] (screen pixels: captures are the real screen, 1:1). */
    private fun showHighlight(r: PxRect) {
        hideHighlight()
        val pad = dp(HIGHLIGHT_PAD_DP)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(HIGHLIGHT_STROKE_DP), Color.parseColor(HIGHLIGHT_COLOR))
                cornerRadius = dp(12).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            r.width + 2 * pad,
            r.height + 2 * pad,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left - pad
            y = r.top - pad
            // Android 12+ only lets taps through overlays at most 80% opaque.
            alpha = PANEL_WINDOW_ALPHA
        }
        // Real display coordinates, the same as the capture's pixels.
        useScreenCoordinates(params, true)
        try {
            wm.addView(v, params)
            highlight = v
        } catch (_: Exception) {
        }
    }

    private fun hideHighlight() {
        val v = highlight ?: return
        highlight = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun toggleAuto() {
        autoMode = !autoMode
        updateBubbleStyle()
        Toast.makeText(this, if (autoMode) "الوضع التلقائي: مفعّل" else "الوضع التلقائي: متوقف", Toast.LENGTH_SHORT).show()
        autoJob?.cancel()
        autoJob = null
        hiddenAnswer.clear()
        if (autoMode) {
            lastSentSig = null
            prevTickSig = null
            autoJob = scope.launch {
                while (isActive) {
                    delay(AUTO_INTERVAL_MS)
                    autoTick()
                }
            }
        }
    }

    /**
     * One auto-mode step (the decision itself is [FrameSignature.judge]). A change that shows up
     * while our answer panel is on screen does not send anything: the panel is painted black in
     * the capture and could black out part of the new question. The panel is hidden instead, and a
     * later tick sends the frame once it no longer contains the panel and has settled. If the
     * change was only a blip and the screen goes back to what the answer was for, the answer is
     * shown again for the time it had left.
     */
    private suspend fun autoTick() {
        // While the sent-image preview covers the middle of the screen, the capture is mostly black:
        // not worth sending, and it would overwrite client.lastSent.
        if (busy || preview != null) return
        // Fixed before anything suspends: is the panel part of the frame being judged?
        val panelShown = panelAdded
        val fresh = captureMasked(newOnly = true)
        val overlays = ignoreRects()
        val previous = prevTickSig
        // The virtual display only emits a frame when the screen changes, so "no new frame" means
        // the screen is exactly as at the last tick: count it as one more stable tick (reusing that
        // signature) instead of waiting for a frame that may never come on a static screen.
        // Only the very first tick has nothing to reuse and takes the kept frame.
        val bmp: Bitmap? = fresh ?: if (previous == null) (captureMasked() ?: return) else null
        val sig = if (bmp != null) {
            withContext(Dispatchers.Default) { signature(bmp, overlays) }
        } else {
            previous ?: return
        }
        val verdict = FrameSignature.judge(sig, previous, lastSentSig, panelShown, CHANGE_THRESHOLD, STABLE_THRESHOLD)
        prevTickSig = sig
        var toSend: Bitmap? = null
        if (!busy && preview == null) {
            when (verdict) {
                FrameSignature.Verdict.HIDE_PANEL -> if (panelAdded) {
                    hiddenAnswer.remember(panelText, panelDeadline)
                    hidePanel()
                }
                FrameSignature.Verdict.SEND -> toSend = bmp ?: captureMasked()
                FrameSignature.Verdict.IDLE -> if (!panelAdded) {
                    hiddenAnswer.take(SystemClock.uptimeMillis(), HIDDEN_ANSWER_MIN_LEFT_MS)
                        ?.let { showPanel(it.text, it.leftMs) }
                }
                FrameSignature.Verdict.WAIT -> {}
            }
        }
        if (toSend != null) {
            hiddenAnswer.clear()
            sendFrame(toSend, sig, fromAuto = true)
        }
        if (bmp != null && bmp !== toSend) bmp.recycle()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        volumeTrigger = null
        autoJob?.cancel()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        hidePanel()
        hidePreview()
        hideHighlight()
        // Taken under the lock, so no read is running; closing (native) happens off the main thread.
        val reader = synchronized(ocrLock) {
            destroyed = true
            ocr.also { ocr = null }
        }
        if (reader != null) Thread { reader.close() }.start()
        bubble?.let {
            if (it.isAttachedToWindow) {
                try {
                    wm.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        bubble = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        lastFrame?.recycle()
        lastFrame = null
        shownImage = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
