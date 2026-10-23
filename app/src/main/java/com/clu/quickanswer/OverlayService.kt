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
import com.clu.quickanswer.ocr.ColorLayout
import com.clu.quickanswer.ocr.LatinOcr
import com.clu.quickanswer.ocr.LayoutReplyParser
import com.clu.quickanswer.ocr.LearningGate
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import com.clu.quickanswer.ocr.ScreenFingerprint
import com.clu.quickanswer.ocr.ScreenLayout
import com.clu.quickanswer.ocr.TemplateLearner
import com.clu.quickanswer.ocr.TemplateReader
import com.clu.quickanswer.ocr.TemplateState
import java.util.UUID
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
        // "The bubble stopped, tap to start again": its own channel, so it is not silent like the running one.
        private const val RESTART_CHANNEL_ID = "clu_restart"
        private const val RESTART_NOTIF_ID = 2
        private const val ACTION_STOP = "com.clu.quickanswer.STOP"
        private const val ACTION_LAST_IMAGE = "com.clu.quickanswer.LAST_IMAGE"
        // How often auto mode looks at the screen; FrameSignature.judge and its tests count in these ticks.
        private const val AUTO_INTERVAL_MS = FrameSignature.TICK_MS
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
        // The box is moved to where it was asked to be only if the system put it this close to it.
        private const val HIGHLIGHT_FIX_MAX_DP = 200
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
    // The answer provider for the settings it was made from (clientKey); see currentClient().
    @Volatile private var client: Answerer? = null
    private var clientKey: String? = null
    private val clientLock = Any()
    // Set when the service stops on purpose (the user's stop, or onDestroy), so only a projection the
    // system stopped (screen locked, status-bar chip) offers the "start again" notification.
    @Volatile private var stoppingByUs = false
    // The virtual display only emits frames when the screen changes, so keep the last one.
    private var lastFrame: Bitmap? = null

    // On-device question reader; loaded in the background when the service starts (null until then,
    // or if it cannot load on this phone). Every use holds ocrLock, so it is never closed mid-read.
    private val ocrLock = Any()
    private var ocr: ArabicOcr? = null
    private val latinOcrLock = Any()
    private val colorLayout = ColorLayout()

    // When a layout request may go out in the background (see learnLayout).
    private val learningGate = LearningGate()
    private var latinOcr: LatinOcr? = null
    @Volatile private var destroyed = false

    // The green box around the right option (its own touch-through window), shown and hidden with the panel.
    private var highlight: View? = null
    // The button it marks (screen pixels) and the screen size that was measured on; null without a box.
    private var highlightBox: HiddenAnswer.Box? = null
    // Where our own windows were painted black in a capture, by capture: the reader must know what is hidden.
    private val captureMasks = java.util.WeakHashMap<Bitmap, List<Rect>>()

    // The last answer that came from memory, for the correction tap (see CORRECTION_WINDOW_MS).
    private var lastHitKey: String? = null
    private var lastHitAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stoppingByUs = true
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
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(RESTART_NOTIF_ID)
        currentClient()
        warmUp()
        AnswerStore.init(this)
        TemplateStore.init(this)
        StatsStore.init(this)
        loadOcr()
        loadLatinOcr()

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
            // Runs on [handler] (the main thread). Android 15 QPR1+ stops the projection when the screen
            // locks; Android asks for consent again for every session, so offer a one-tap way back.
            override fun onStop() {
                if (stoppingByUs) return
                stoppingByUs = true
                showRestartNotification()
                stopSelf()
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

    /**
     * Tells the user the bubble stopped because the system ended the screen capture, with a tap that
     * opens Clu and asks for the capture again (see [MainActivity.EXTRA_RESTART]).
     */
    private fun showRestartNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(RESTART_CHANNEL_ID, "Clu: إعادة التشغيل", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                this, 2,
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_RESTART, true)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, RESTART_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("توقفت فقاعة Clu")
                .setContentText("أوقف أندرويد التقاط الشاشة (مثلاً بعد قفلها). اضغط للتشغيل من جديد")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(RESTART_NOTIF_ID, notification)
        } catch (_: Exception) {
            // No notification permission: the user starts it from the app as before.
        }
    }

    /**
     * The answer provider for the current settings. Rebuilt when the provider, key or model was
     * changed in the settings screen (saved when it is left), so the change applies to the running
     * bubble without a restart. Any thread.
     */
    private fun currentClient(): Answerer {
        val claude = Prefs.provider(this) == "claude"
        val key = if (claude) {
            "claude|${Prefs.claudeKey(this)}|${Prefs.claudeModel(this)}"
        } else {
            "gemini|${Prefs.apiKey(this)}|${Prefs.model(this)}"
        }
        synchronized(clientLock) {
            val existing = client
            if (existing != null && key == clientKey) return existing
            val fresh: Answerer = if (claude) {
                ClaudeClient(Prefs.claudeKey(this), Prefs.claudeModel(this))
            } else {
                GeminiClient(Prefs.apiKey(this), Prefs.model(this))
            }
            client = fresh
            clientKey = key
            return fresh
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // New screen size: the bubble may now be off-screen (landscape -> portrait), so pull it
        // back in first, then re-measure the panel (max width depends on orientation) and re-place
        // it (it follows the bubble in NEXT_TO_BUBBLE mode). The centred preview is sized for the
        // old screen, so just close it.
        // The box marks a button of the old layout (and must not push the re-placed panel around).
        hideHighlight()
        if (resizeCapture()) clampBubble()
        refreshPanel()
        hidePreview()
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
        if (projection == null && client == null) return
        scope.launch(Dispatchers.IO) {
            try {
                currentClient().warmUp()
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
        hideHighlight() // the box of the answer before marks nothing now
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
     * Takes the newest frame the virtual display has produced (if any) as [lastFrame]; true when a
     * new one arrived. The display only produces frames when the screen changes, so on a static
     * screen this costs next to nothing. Main thread.
     */
    private fun grabFrame(): Boolean {
        val fresh = readFrame() ?: return false
        lastFrame?.recycle()
        lastFrame = fresh
        return true
    }

    /**
     * Must be called on the main thread (reads view geometry). Returns the newest frame as a copy
     * with our overlays painted over.
     */
    private fun captureMasked(): Bitmap? {
        grabFrame()
        return maskedCopy()
    }

    /** A full-size copy of [lastFrame] with our overlays painted black (the caller owns and recycles it). */
    private fun maskedCopy(): Bitmap? {
        val bmp = lastFrame?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val masks = overlayRects()
        for (r in masks) canvas.drawRect(r, paint)
        captureMasks[bmp] = masks
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
        // Monotonic, so a clock change cannot give a negative time; includes reading, memory and the network.
        val started = SystemClock.elapsedRealtime()
        val masks = captureMasks.remove(bmp).orEmpty().map { PxRect(it.left, it.top, it.right, it.bottom) }
        scope.launch {
            val solved = withContext(Dispatchers.IO) {
                try {
                    solve(bmp, masks, bypass)
                } catch (e: Exception) {
                    Solved(
                        "خطأ: ${e.javaClass.simpleName}", null, null, null,
                        isAnswer = false, source = AnswerSource.FAILED,
                    )
                } finally {
                    bmp.recycle()
                }
            }
            if (sig != null) lastSentSig = sig
            setBusy(false)
            if (fromAuto && ScreenImage.isNoQuestion(solved.text)) {
                solved.learn?.let { dropLearning(it) }
                return@launch
            }
            // After the not-a-quiz return above: a screen auto mode skipped is not a failed question.
            StatsStore.record(solved.source, SystemClock.elapsedRealtime() - started)
            lastHitKey = solved.memoryKey
            lastHitAt = if (solved.memoryKey != null) SystemClock.uptimeMillis() else 0L
            shownImage = solved.image ?: shownImage
            // The answer only: the owner found the time and model next to it distracting.
            showPanel(solved.text)
            if (solved.isAnswer && Prefs.vibrateOnAnswer(this@OverlayService)) Haptics.tick(this@OverlayService)
            // The box marks a button in the captured frame; skip it if the screen rotated meanwhile.
            val rect = solved.rect
            if (rect != null && capturedW == screenW && capturedH == screenH && Prefs.highlightAnswer(this@OverlayService)) {
                showHighlight(rect)
            }
            // After the answer is on screen, never before it (decision 10).
            solved.learn?.let { startLearning(it) }
        }
    }

    /**
     * What one answer produced: the text for the bar, the option's button to box (screen pixels),
     * the picture "آخر صورة" shows, and the memory entry it came from (null if not from memory).
     * [isAnswer]: the text is an answer (from memory or the model), not an error or "no question";
     * only an answer gets a haptic tick (see [Reply.isAnswer]).
     * [source]: which step produced it, for the statistics; no default, so a new path cannot forget it.
     */
    private class Solved(
        val text: String,
        val rect: PxRect?,
        val image: Bitmap?,
        val memoryKey: String?,
        val isAnswer: Boolean,
        val source: AnswerSource,
        /** A screen to learn the layout of once the answer is shown (see [learnLayout]), or null. */
        val learn: LearnFrame? = null,
    )

    /**
     * A copy of a screen no reader or template could read, kept to ask [client] for its layout in
     * the background. [bitmap] is ours: [startLearning] or [dropLearning] recycles it.
     */
    private class LearnFrame(val bitmap: Bitmap, val masks: List<PxRect>, val fingerprint: String, val client: Answerer)

    /**
     * What the device read: [question] (null when nothing could be read), a [candidate] template's
     * reading (only to be checked against the model's answer, never used to answer), the screen's
     * [fingerprint] when templates were tried (null when [ColorLayout] took the screen), and
     * whether some template [fits] the screen (then there is nothing new to learn).
     */
    private class DeviceRead(
        val question: Question?,
        val candidate: Pair<String, Question>? = null,
        val fingerprint: String? = null,
        val fits: Boolean = false,
    )

    /**
     * Answers the question on [bmp] (blocking; run off the main thread). [masks] are the places where
     * our own windows were painted black over it.
     * 1. Read it on the device. If that works: answer from memory when the question was seen before
     *    (unless it is [bypassKey], the answer being corrected), else ask the model with text only and
     *    remember the answer. A question about a picture skips this: its words do not decide it.
     * 2. Otherwise, or when the text answer cannot be used, send the screenshot as before. If the
     *    question was read, an image answer that names one of its options still gets the box and is
     *    remembered. A correction goes straight here: the text request would get the same reply again.
     */
    private fun solve(bmp: Bitmap, masks: List<PxRect>, bypassKey: String?): Solved {
        val c = currentClient()
        val memory = AnswerStore.memory
        val read = if (Prefs.smartReading(this)) readQuestion(bmp, masks) else DeviceRead(null)
        val q = read.question
        // The cheapest rung of the answer ladder: arithmetic is computed here, before the memory and the model.
        if (q != null) Calculator.answer(q)?.let { r -> return Solved(r.answer, r.rect, shownCopy(bmp), null, isAnswer = true) }
        val wordsDecide = q != null && !QuestionPrompt.refersToPicture(q.questionText)
        var corrected: AnswerMemory.Hit? = null
        if (q != null && wordsDecide) {
            val hit = memory.lookup(q.questionText, q.options, q.numeric)
            if (hit != null && hit.key == bypassKey) {
                corrected = hit
            } else {
                if (hit != null) {
                    AnswerParser.resolveStored(q, hit.answer)?.let { r ->
                        return Solved(
                            "⚡ ${r.answer}", r.rect, shownCopy(bmp), hit.key,
                            isAnswer = true, source = AnswerSource.MEMORY,
                        )
                    }
                }
                val reply = c.answerText(QuestionPrompt.build(q))
                when (reply.kind) {
                    Reply.Kind.ANSWER -> AnswerParser.resolve(q, reply.text)?.let { r ->
                        memory.put(q.questionText, q.options, q.numeric, r.answer)
                        return Solved(r.answer, r.rect, shownCopy(bmp), null, isAnswer = true, source = AnswerSource.TEXT)
                    }
                    // Quota, key, region, no connection: the image request would fail the same way.
                    Reply.Kind.ERROR -> return Solved(
                        reply.text, null, null, null, isAnswer = false, source = AnswerSource.FAILED,
                    )
                    Reply.Kind.EMPTY -> {}
                }
            }
        }
        val imageReply = c.answer(bmp)
        val text = imageReply.text
        // An error message is never matched against the options (or taken for a number).
        if (q != null && imageReply.kind == Reply.Kind.ANSWER) {
            val r = if (q.numeric) {
                // Only a bare number counts (an error message can contain digits too).
                AnswerParser.numberOnly(text)?.let { AnswerParser.Resolved(it, null) }
            } else {
                AnswerParser.resolveImageAnswer(q, text)
            }
            if (r != null) {
                if (wordsDecide) {
                    // The entry that was corrected goes, but only if it was this question's own: a fuzzy hit
                    // belongs to another question, whose answer is still right for it.
                    if (corrected != null && corrected.exact) memory.remove(corrected.key)
                    memory.put(q.questionText, q.options, q.numeric, r.answer)
                }
                return Solved(r.answer, r.rect, c.lastSent, null, isAnswer = true, source = AnswerSource.IMAGE)
            }
        }
        read.candidate?.let { (id, cq) ->
            val check = CandidateCheck.judge(cq, imageReply.isAnswer, text)
            val now = System.currentTimeMillis()
            when (check.verdict) {
                CandidateCheck.Verdict.CONFIRMED -> TemplateStore.templates.recordSuccess(id, now)
                CandidateCheck.Verdict.REFUTED -> TemplateStore.templates.recordFailure(id, now)
                CandidateCheck.Verdict.UNKNOWN -> {}
            }
            // The answer came from the screenshot; the candidate only says where its button is.
            check.resolved?.let { r ->
                return Solved(r.answer, r.rect, c.lastSent, null, isAnswer = true, source = AnswerSource.IMAGE)
            }
        }
        val source = if (imageReply.isAnswer) AnswerSource.IMAGE else AnswerSource.FAILED
        return Solved(
            text, null, c.lastSent, null, imageReply.isAnswer, source,
            learnFrameFor(read, bmp, masks, c, imageReply, text),
        )
    }

    /**
     * A copy of [bmp] to learn its layout from, or null. Only for a screen the templates were tried
     * on (not the original game, which [ColorLayout] reads), that none of them fits, that the model
     * answered (not an error or "no question"), and only when [learningGate] lets a request go.
     */
    private fun learnFrameFor(read: DeviceRead, bmp: Bitmap, masks: List<PxRect>, c: Answerer, reply: Reply, text: String): LearnFrame? {
        val fp = read.fingerprint ?: return null
        if (read.fits || read.question != null || !reply.isAnswer || ScreenImage.isNoQuestion(text)) return null
        if (!learningGate.tryStart(fp, SystemClock.elapsedRealtime())) return null
        val copy = try {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        }
        if (copy == null) {
            learningGate.finish(fp, learned = false, now = SystemClock.elapsedRealtime())
            return null
        }
        return LearnFrame(copy, masks, fp, c)
    }

    private fun startLearning(f: LearnFrame) {
        scope.launch(Dispatchers.IO) {
            var learned = false
            try {
                learned = learnLayout(f)
            } catch (t: Throwable) {
                // Background work: a failure only means nothing was learned this time.
            } finally {
                f.bitmap.recycle()
                learningGate.finish(f.fingerprint, learned, SystemClock.elapsedRealtime())
            }
        }
    }

    private fun dropLearning(f: LearnFrame) {
        f.bitmap.recycle()
        learningGate.finish(f.fingerprint, learned = false, now = SystemClock.elapsedRealtime())
    }

    /**
     * Asks the model where the question and options of [f] are, builds a CANDIDATE template from the
     * reply, and keeps it only when reading the same screen with it gives what the model saw
     * ([TemplateLearner.confirms]). Blocking: run on Dispatchers.IO. True when a template was kept.
     */
    private fun learnLayout(f: LearnFrame): Boolean {
        val answer = f.client.layout(f.bitmap) ?: return false
        val reply = LayoutReplyParser.parse(answer.text) ?: return false
        val w = f.bitmap.width
        val h = f.bitmap.height
        val pixels = IntArray(w * h)
        f.bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val now = System.currentTimeMillis()
        val t = TemplateLearner.build(reply, answer.geometry, pixels, w, h, f.masks, UUID.randomUUID().toString(), now)
            ?: return false
        val layout = TemplateReader.layoutFor(t, pixels, w, h) ?: return false
        val read = readWith(f.bitmap, pixels, w, h, ColorLayout.Result(layout, null), layout, f.masks, t.language)
            ?: return false
        if (!TemplateLearner.confirms(read, reply)) return false
        TemplateStore.templates.add(t)
        return true
    }

    /**
     * Use the existing offline Arabic reader first. If it cannot read this screen,
     * try the separately bundled offline Latin recognizer before sending a full
     * screenshot to the cloud. Each reader must fail closed, never guess text.
     *
     * The screenshot is copied to pixels and analysed ([ColorLayout]) once; both readers share that
     * layout, and a screen that is not the quiz is declined without running either of them. Native
     * failures (missing library, out of memory) are Errors, not Exceptions: they must lead to the
     * screenshot path, never crash the service, hence Throwable.
     */
    private fun readQuestion(bmp: Bitmap, masks: List<PxRect>): DeviceRead {
        // Hardware bitmaps cannot be read pixel by pixel.
        var copy: Bitmap? = null
        try {
            val src = if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { copy = it }
            } else bmp
            if (src == null) return DeviceRead(null)
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val inspected = colorLayout.inspect(pixels, w, h)
            val layout = inspected.layout ?: return readWithTemplates(src, pixels, w, h, masks)
            return DeviceRead(readWith(src, pixels, w, h, inspected, layout, masks, null))
        } catch (t: Throwable) {
            return DeviceRead(null)
        } finally {
            copy?.recycle()
        }
    }

    /**
     * Reads [language] ("ar": the Arabic reader only, "en": the Latin one only, null: Arabic and
     * then Latin) on a layout that [ColorLayout] or a template gave. Each reader runs in its own
     * try/catch under its own lock and a failure counts as "could not read".
     */
    private fun readWith(
        src: Bitmap,
        pixels: IntArray,
        w: Int,
        h: Int,
        inspected: ColorLayout.Result,
        layout: ScreenLayout,
        masks: List<PxRect>,
        language: String?,
    ): Question? {
        // Anything but "ar"/"en" (an old or damaged file) reads with both.
        val lang = language?.takeIf { it == "ar" || it == "en" }
        if (lang == null || lang == "ar") {
            val arabic = try {
                synchronized(ocrLock) { ocr?.read(pixels, w, h, inspected, masks) }
            } catch (t: Throwable) {
                null
            }
            if (arabic != null || lang == "ar") return arabic
        }
        return try {
            synchronized(latinOcrLock) { latinOcr?.read(src, layout, masks) }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Reads a screen that [ColorLayout] declined with a learned layout. Only TRUSTED templates are
     * used to read. A template that does not fit the screen is not a failure (another mode or screen
     * of the game), but a fitting template whose reading is declined is, and counts against it.
     *
     * When no trusted template reads it, the first fitting CANDIDATE that reads it is returned as
     * [DeviceRead.candidate]: it is checked against the model's answer ([CandidateCheck]), which is
     * how candidates earn their way to TRUSTED.
     */
    private fun readWithTemplates(src: Bitmap, pixels: IntArray, w: Int, h: Int, masks: List<PxRect>): DeviceRead {
        val fp = ScreenFingerprint.of(pixels, w, h, masks)
        val templates = TemplateStore.templates
        var fits = false
        for (t in templates.find(fp, null, trustedOnly = false)) {
            if (!TemplateReader.fits(t, pixels, w, h)) continue
            fits = true
            val now = System.currentTimeMillis()
            val tl = TemplateReader.layoutFor(t, pixels, w, h)
            if (tl == null) {
                templates.recordFailure(t.id, now)
                continue
            }
            val q = readWith(src, pixels, w, h, ColorLayout.Result(tl, null), tl, masks, t.language)
            if (q == null) {
                templates.recordFailure(t.id, now)
                continue
            }
            // A candidate's reading is judged later, against the answer; only a trusted one answers.
            if (t.state != TemplateState.TRUSTED) return DeviceRead(null, t.id to q, fp, fits = true)
            templates.recordSuccess(t.id, now)
            return DeviceRead(q, null, fp, fits = true)
        }
        return DeviceRead(null, null, fp, fits)
    }

    /**
     * Load the bundled English OCR alongside Arabic without blocking service startup. It is warmed up
     * on the IO thread before it is published, so the first real question does not pay for model loading.
     */
    private fun loadLatinOcr() {
        scope.launch(Dispatchers.IO) {
            val loaded = try {
                LatinOcr().also { it.warmUp() }
            } catch (t: Throwable) {
                null
            }
            synchronized(latinOcrLock) {
                if (destroyed) loaded?.close() else latinOcr = loaded
            }
        }
    }

    /** A smaller copy of [bmp] for "آخر صورة" (the caller recycles [bmp]). */
    private fun shownCopy(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= SHOWN_IMAGE_MAX_SIDE) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val s = SHOWN_IMAGE_MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
    }

    /**
     * Loads the on-device reader in the background. Without it (it cannot be loaded) the app still
     * works by sending the picture. The APK carries the 64-bit ARM libraries only, so a phone
     * without them cannot install it at all (documented in the README).
     */
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

    /** [r] grown by the room the green box leaves around a button. */
    private fun paddedBox(r: PxRect): PxRect {
        val pad = dp(HIGHLIGHT_PAD_DP)
        return PxRect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
    }

    /** Draws the green box around [r] (screen pixels: captures are the real screen, 1:1). */
    private fun showHighlight(r: PxRect) {
        hideHighlight()
        val box = paddedBox(r)
        val v = View(this).apply {
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
            highlightBox = HiddenAnswer.Box(r, screenW, screenH)
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
        val to = PanelPlacement.moveOffBox(now, box, screenW, screenH, dp(8))
        if (to == null) hideHighlight()
        return to
    }

    private fun hideHighlight() {
        highlightBox = null
        val v = highlight ?: return
        highlight = null
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    /** Puts the green box of an answer that auto mode hid back, if the screen is still the one it was for. */
    private fun restoreHighlight(box: HiddenAnswer.Box?) {
        if (box == null || box.screenW != screenW || box.screenH != screenH) return
        if (Prefs.highlightAnswer(this)) showHighlight(box.rect)
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
     * One auto-mode step (the decision itself is [FrameSignature.judge]); runs every
     * [AUTO_INTERVAL_MS]. A change that shows up while our answer panel is on screen does not send
     * anything: the panel is painted black in the capture and could black out part of the new
     * question. The panel is hidden instead, and a later tick sends the frame once it no longer
     * contains the panel and has settled. If the change was only a blip and the screen goes back to
     * what the answer was for, the answer is shown again for the time it had left.
     *
     * Cheap by design, since it runs twice a second: a tick on a static screen finds no new frame
     * and reuses the previous signature (no bitmap work at all); a tick with a new frame reads it
     * once and scales it down to the signature grid. The full-size masked copy that gets sent is
     * made only for the tick that sends. All on the main thread, one tick at a time: the scaling is
     * a few thousand pixel reads, and it keeps the frame, the overlay rectangles and the panel
     * state of one tick consistent (nothing can swap the frame out from under it).
     */
    private fun autoTick() {
        // While the sent-image preview covers the middle of the screen, the capture is mostly black:
        // not worth sending, and it would overwrite client.lastSent.
        if (busy || preview != null) return
        // Is the panel part of the frame being judged?
        val panelShown = panelAdded
        val gotNewFrame = grabFrame()
        val previous = prevTickSig
        // The virtual display only emits a frame when the screen changes, so "no new frame" means
        // the screen is exactly as at the last tick: count it as one more stable tick (reusing that
        // signature) instead of waiting for a frame that may never come on a static screen.
        // Only the very first tick has nothing to reuse and takes the kept frame.
        val sig = if (gotNewFrame || previous == null) {
            val frame = lastFrame ?: return
            // Taken from the unmasked frame: the cells our windows touch are ignored anyway.
            signature(frame, ignoreRects())
        } else {
            previous
        }
        val verdict = FrameSignature.judge(sig, previous, lastSentSig, panelShown, CHANGE_THRESHOLD, STABLE_THRESHOLD)
        prevTickSig = sig
        when (verdict) {
            FrameSignature.Verdict.HIDE_PANEL -> if (panelAdded) {
                hiddenAnswer.remember(panelText, panelDeadline, highlightBox)
                hidePanel()
            }
            FrameSignature.Verdict.SEND -> {
                val toSend = maskedCopy() ?: return
                hiddenAnswer.clear()
                sendFrame(toSend, sig, fromAuto = true)
            }
            FrameSignature.Verdict.IDLE -> if (!panelAdded) {
                hiddenAnswer.take(SystemClock.uptimeMillis(), HIDDEN_ANSWER_MIN_LEFT_MS)?.let {
                    showPanel(it.text, it.leftMs)
                    restoreHighlight(it.box)
                }
            }
            FrameSignature.Verdict.WAIT -> {}
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        // projection.stop() below calls onStop(): that is our own stop, not one to offer a restart for.
        stoppingByUs = true
        destroyed = true
        volumeTrigger = null
        autoJob?.cancel()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        hidePanel()
        hidePreview()
        hideHighlight()
        // A read in progress holds the lock until it is done (it cannot be interrupted), so taking it
        // here would freeze the main thread for as long as one read takes. Another thread waits for it
        // and closes the reader (native) once nothing uses it; a reader that finishes loading
        // meanwhile finds [destroyed] set and closes itself.
        Thread {
            val reader = synchronized(ocrLock) { ocr.also { ocr = null } }
            val latinReader = synchronized(latinOcrLock) { latinOcr.also { latinOcr = null } }
            reader?.close()
            latinReader?.close()
        }.start()
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
