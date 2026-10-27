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
import com.clu.quickanswer.ocr.CalibratedKeypad
import com.clu.quickanswer.ocr.FieldStamp
import com.clu.quickanswer.ocr.ChoiceTouchGuard
import com.clu.quickanswer.ocr.GameVisualLayout
import com.clu.quickanswer.ocr.OptionWordDetector
import com.clu.quickanswer.ocr.NumericFieldReader
import com.clu.quickanswer.ocr.NumericFieldInk
import com.clu.quickanswer.interaction.PrintedChoiceTouch
import com.clu.quickanswer.ocr.TextLines
import com.clu.quickanswer.ocr.WordAnswerMatcher
import com.clu.quickanswer.interaction.AnswerInteraction
import com.clu.quickanswer.interaction.NumericEntryCheck
import com.clu.quickanswer.ocr.ColorLayout
import com.clu.quickanswer.ocr.LatinOcr
import com.clu.quickanswer.ocr.LayoutReplyParser
import com.clu.quickanswer.ocr.LayoutTemplate
import com.clu.quickanswer.ocr.LearningGate
import com.clu.quickanswer.ocr.PictureRegion
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import com.clu.quickanswer.ocr.ResultConfirmer
import com.clu.quickanswer.ocr.ResultReader
import com.clu.quickanswer.ocr.ScreenFingerprint
import com.clu.quickanswer.ocr.ScreenLayout
import com.clu.quickanswer.ocr.TemplateLearner
import com.clu.quickanswer.ocr.TemplateReader
import com.clu.quickanswer.ocr.TemplateState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        /** Capture after hiding our panel: poll step, least wait (the former fixed one), most wait (ms). */
        private const val CLEAN_POLL_MS = 40L
        private const val CLEAN_MIN_MS = 250L
        private const val CLEAN_MAX_MS = 600L

        /** Before touching: at most this many [CLEAN_POLL_MS] waits for a frame without the panel. */
        private const val CLEAN_TOUCH_POLLS = 15

        /**
         * How long past [AnswerDeadline.BUDGET_MS] sendFrame waits for the answer before it gives up on it.
         * The clients stop at the budget themselves and say so, and that message should normally be the
         * one shown, so this guard (for a hang the clients' timeouts do not catch) waits a little longer.
         */
        private const val ANSWER_GUARD_GRACE_MS = 1_000L

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

        // The result watch (see watchResult). None of these is measured on real captures yet: there
        // are no screenshots of these games' result moment.
        // How often it looks, and how long it keeps looking after the answer is shown.
        private const val WATCH_INTERVAL_MS = 500L
        private const val WATCH_MAX_MS = 20_000L
        // It stops when the screen, leaving the option buttons out, differs from the question's by more
        // than this (FrameSignature.diff). Half of CHANGE_THRESHOLD: the buttons are what differs most
        // between two questions and are not compared, so the rest has to be held to a stricter limit.
        private const val WATCH_SAME_SCREEN_DIFF = CHANGE_THRESHOLD / 2
        private const val LONG_PRESS_MS = 600L
        // A reply for learning a layout is a quiz option or a number, which is short; a sentence means
        // a document or an app, not a quiz screen worth a layout request.
        private const val MAX_LEARN_REPLY_CHARS = 40
        private const val PANEL_TOP_MARGIN_DP = 6
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
    // The watch for the game's result screen after the last answer (see watchResult); one at a time.
    private var watchJob: Job? = null
    @Volatile private var busy = false
    private var touchPending = false
    private var touchJob: Job? = null
    private var touchEpoch = 0L
    private val touchAttempts = TouchAttemptGuard()
    private val autoRetry = AutoAnswerRetry()
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

    /** When the display produced [lastFrame] ([android.media.Image.getTimestamp], ns), or 0 if unknown. */
    private var lastFrameAtNs = 0L

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
                cancelTouches()
                showRestartNotification()
                stopSelf()
            }
        }, handler)

        setupCapture(proj)
        createBubble()
        volumeTrigger = { answerNow(AnswerTrigger.VOLUME_DOWN) }
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
            // The installed app's own name ("Clu" or "Clu beta"), so two bubbles are told apart.
            .setContentTitle(applicationInfo.loadLabel(packageManager))
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
        cancelTouches()
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
     * answer options of the game); it hides itself after [hideAfterMs] (longer for a list, see
     * [PanelPlacement.autoHideMs]). A list is aligned to the start, in reading order.
     */
    private fun showPanel(text: String, hideAfterMs: Long = PanelPlacement.autoHideMs(text)) {
        hiddenAnswer.clear() // this answer replaces whatever auto mode had hidden
        hideHighlight() // the box of the answer before marks nothing now
        panelMode = Prefs.panelMode(this)
        val p = panel ?: createPanelView().also { panel = it }
        p.maxLines = PanelPlacement.maxLines(panelMode, text, landscape = screenW > screenH)
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
        lastFrameAtNs = readFrameAtNs
        return true
    }

    /** Timestamp of the image [readFrame] read last (set by it). */
    private var readFrameAtNs = 0L

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
            readFrameAtNs = image.timestamp
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

    private fun answerNow(trigger: AnswerTrigger = AnswerTrigger.BUBBLE) {
        if (busy || touchPending || destroyed) return
        stopResultWatch()
        hiddenAnswer.clear()
        if (panelAdded || preview != null) {
            // Remove the old answer (and the sent-image preview) first so they don't hide part of the question.
            hidePanel()
            hidePreview()
            setBusy(true)
            awaitFrameWithoutOverlays(trigger, SystemClock.uptimeMillis(), System.nanoTime())
            return
        }
        val bmp = captureMasked()
        if (bmp == null) {
            showPanel("لم يتم الحصول على إجابة")
            return
        }
        // In auto mode count this as the last frame sent, or the next tick would see the question as
        // "new", hide this answer and ask again.
        touchAttempts.reset()
        autoRetry.reset()
        sendFrame(bmp, if (autoMode) signature(bmp, ignoreRects()) else null, fromAuto = false, trigger = trigger)
    }

    /**
     * Answers once the screen has been drawn without our old answer panel. A capture that still
     * showed the panel over the question (26 Oct 2026) could not be read on the device, and the
     * model was then sent our own old answer to read. Waits at least [CLEAN_MIN_MS] (the wait that
     * always worked) and, when the display's frame times can be trusted, until the newest frame was
     * produced after the removal; never longer than [CLEAN_MAX_MS]. Removing a window always makes
     * the display draw a frame without it, so after the minimum wait the newest frame is that one
     * (or a later one of an animating game).
     */
    private fun awaitFrameWithoutOverlays(trigger: AnswerTrigger, startMs: Long, removedAtNs: Long) {
        handler.postDelayed({
            if (destroyed) { setBusy(false); return@postDelayed }
            try { grabFrame() } catch (_: Throwable) { }
            val waited = SystemClock.uptimeMillis() - startMs
            val frameAt = lastFrameAtNs
            // The image clock is the monotonic one (as System.nanoTime) on Android; a time that is
            // not within a minute of now is from another clock, and only the minimum wait is used.
            val trusted = frameAt > 0 && kotlin.math.abs(frameAt - System.nanoTime()) < 60_000_000_000L
            val clean = waited >= CLEAN_MIN_MS && (!trusted || frameAt > removedAtNs)
            if (!clean && waited < CLEAN_MAX_MS) {
                awaitFrameWithoutOverlays(trigger, startMs, removedAtNs)
                return@postDelayed
            }
            DiagLog.add("انتظار اختفاء اللوحة ${waited}ms: " + when {
                !trusted -> "وقت الإطار غير معروف"
                clean -> "إطار بعد الإخفاء بـ${(frameAt - removedAtNs) / 1_000_000}ms"
                else -> "انتهى الحد بدون إطار جديد"
            })
            setBusy(false)
            answerNow(trigger)
        }, CLEAN_POLL_MS)
    }

    private fun permitsTouch(trigger: AnswerTrigger): Boolean = trigger.permitsTouch(
        autoMode, Prefs.autoTouch(this), Prefs.volumeKey(this))

    private fun cancelTouches() {
        touchEpoch++
        touchJob?.cancel()
        touchJob = null
        VolumeKeyService.cancelAnswer()
        if (touchPending) setBusy(false)
        touchPending = false
    }

    /**
     * Sends [bmp] and shows the answer. A send that auto mode started on its own ([fromAuto]) shows
     * nothing when the model says there is no question: that is a screen that is not the quiz
     * (the notification shade, another app, Clu's settings), and it must neither cover the screen
     * nor replace the picture "آخر صورة" shows. Failed readings get at most two delayed retries.
     */
    private fun sendFrame(bmp: Bitmap, sig: IntArray?, fromAuto: Boolean,
                          trigger: AnswerTrigger = AnswerTrigger.AUTOMATIC) {
        // A new question: what the last one's result screen shows must not be taken for this one's.
        stopResultWatch()
        val bypass = if (!fromAuto && SystemClock.uptimeMillis() - lastHitAt < CORRECTION_WINDOW_MS) lastHitKey else null
        val capturedW = bmp.width
        val capturedH = bmp.height
        // Monotonic, so a clock change cannot give a negative time; includes reading, memory and the network.
        val started = SystemClock.elapsedRealtime()
        // One budget for the whole answer: reading, the text request and the image request share it.
        val deadline = newAnswerDeadline()
        val maskRects = captureMasks.remove(bmp).orEmpty()
        val masks = maskRects.map { PxRect(it.left, it.top, it.right, it.bottom) }
        val capturedEpoch = touchEpoch
        val capturedWindow = VolumeKeyService.contextVersion()
        val touchRequested = permitsTouch(trigger)
        DiagLog.add("طلب ${trigger.name}${if (fromAuto) " تلقائي" else ""} ${capturedW}x$capturedH، لمس=${if (touchRequested) "نعم" else "لا"}")
        val retryAttempt = sig?.let { autoRetry.begin(it) }
        fun requestIsCurrent() = !destroyed && capturedEpoch == touchEpoch &&
            capturedWindow == VolumeKeyService.contextVersion() &&
            capturedW == screenW && capturedH == screenH
        // The question screen as the watch will compare it (see startResultWatch); [bmp] is gone after solve.
        val watchBase = if (Prefs.learnFromResults(this)) {
            try {
                    signature(bmp, maskRects)
                } catch (t: Throwable) {
                    null
                }
            } else null
            setBusy(true)
            // Set when this question is given up on (see below): its late result may then only clean up.
            val abandoned = AtomicBoolean(false)
            scope.launch {
            try {
                // The work is its own coroutine, so that the wait for it can be bounded. It is not
                // cancelled when the wait ends: its thread could not be interrupted anyway, and it
                // recycles [bmp] itself when it returns.
                val work = scope.async(Dispatchers.IO) {
                    try {
                        solve(bmp, masks, bypass, deadline).let {
                            if (touchRequested && it.isAnswer) prepareTouches(it, bmp, masks) else it
                        }.also {
                            // For "معايرة يدوية": not a screen auto mode found to be something else (settings, shade).
                            // Not a late result either: it would replace the capture of a newer question.
                            if (!abandoned.get() && !(fromAuto && ScreenImage.isNoQuestion(it.text))) LastCapture.set(bmp, masks)
                        }
                    } catch (e: Throwable) {
                        Solved(
                            "خطأ: ${e.javaClass.simpleName}", null, null, null,
                            isAnswer = false, source = AnswerSource.FAILED,
                        )
                    } finally {
                        bmp.recycle()
                    }
                }
                // The clients stop at the deadline themselves and say so; this wait ends a little after
                // it, for whatever they do not catch (a stuck reader, a socket that ignores its timeout).
                val solved = withTimeoutOrNull(AnswerDeadline.BUDGET_MS + ANSWER_GUARD_GRACE_MS) { work.await() }
                if (solved == null) {
                    // Out of time. From here on this question is over: the bubble is free (the finally
                    // below releases it), the panel says so, and auto mode counts it as a failed
                    // question. The late result touches nothing but its learning frame, which is ours to
                    // recycle: no panel, no memory key, no result watch, no touches, so it can never act
                    // on a newer question.
                    abandoned.set(true)
                    scope.launch(Dispatchers.IO) {
                        runCatching { work.await() }.getOrNull()?.learn?.let { dropLearning(it) }
                    }
                    DiagLog.add("انتهى وقت الجواب بعد ${SystemClock.elapsedRealtime() - started}ms، والنتيجة المتأخرة تُهمل")
                    if (destroyed) return@launch
                    if (sig != null) {
                        lastSentSig = sig
                        // A failed question like any other; a newer request, a cancel or a changed screen
                        // means the retry would be for a screen that is gone, so none is booked.
                        if (requestIsCurrent()) retryAttempt?.finish(retryable = true, SystemClock.uptimeMillis())
                        else retryAttempt?.cancel()
                    }
                    StatsStore.record(AnswerSource.FAILED, SystemClock.elapsedRealtime() - started)
                    lastHitKey = null
                    lastHitAt = 0L
                    showPanel(AnswerDeadline.TIMEOUT_TEXT)
                    return@launch
                }
                if (destroyed) {
                    solved.learn?.let { dropLearning(it) }
                    return@launch
                }
                if (sig != null) {
                    lastSentSig = sig
                    if (requestIsCurrent()) retryAttempt?.resolved(solved.isAnswer, touchRequested,
                        solved.touches != null, SystemClock.uptimeMillis())
                    else retryAttempt?.cancel()
                }
                if (fromAuto && ScreenImage.isNoQuestion(solved.text)) {
                    solved.learn?.let { dropLearning(it) }
                    return@launch
                }
                // After the not-a-quiz return above: a screen auto mode skipped is not a failed question.
                StatsStore.record(solved.source, SystemClock.elapsedRealtime() - started)
                lastHitKey = solved.memoryKey
                lastHitAt = if (solved.memoryKey != null) SystemClock.uptimeMillis() else 0L
                shownImage = solved.image ?: shownImage
                val model = if (solved.source == AnswerSource.TEXT || solved.source == AnswerSource.IMAGE)
                    runCatching { currentClient().lastModel }.getOrNull() ?: "-" else "-"
                DiagLog.add("الجواب «${solved.text.take(60)}» من ${solved.source.name}" +
                    " ($model) خلال ${SystemClock.elapsedRealtime() - started}ms، " +
                    when {
                        !touchRequested || !solved.isAnswer -> "بدون لمس"
                        solved.touches != null -> "خطة لمس: ${solved.touches.size} ضغطة"
                        else -> "لا لمس: ${solved.touchFailure ?: "بدون سبب"}"
                    })
                // The answer only: the owner found the time and model next to it distracting.
                showPanel(if (touchRequested && solved.isAnswer && solved.touches == null)
                    "${solved.text}\n${solved.touchFailure ?: "لم أجد طريقة إدخال موثوقة لهذا الشكل"}" else solved.text)
                if (solved.isAnswer && Prefs.vibrateOnAnswer(this@OverlayService)) Haptics.tick(this@OverlayService)
                // The box marks a button in the captured frame; skip it if the screen rotated meanwhile.
                val rect = solved.rect
                if (rect != null && capturedW == screenW && capturedH == screenH && Prefs.highlightAnswer(this@OverlayService)) {
                    showHighlight(rect)
                }
                // After the answer is on screen, never before it (decision 10).
                solved.learn?.let { startLearning(it) }
                // The game's result screen may show another option as the right one; skip it if the screen rotated.
                val watch = solved.watch
                if (watch != null && watchBase != null && capturedW == screenW && capturedH == screenH &&
                    Prefs.learnFromResults(this@OverlayService)
                ) {
                    startResultWatch(watch, watchBase)
                }
                if (touchRequested && permitsTouch(trigger) && capturedEpoch == touchEpoch &&
                    capturedWindow == VolumeKeyService.contextVersion() &&
                    capturedW == screenW && capturedH == screenH && solved.touches != null) {
                    performAnswerTouches(solved, capturedW, capturedH, trigger, retryAttempt)
                } else if (touchRequested && solved.touches != null) {
                    DiagLog.add("اللمس أُلغي قبل البدء: " + when {
                        !permitsTouch(trigger) -> "اللمس انطفى بالإعدادات"
                        capturedEpoch != touchEpoch -> "محاولة أحدث أو إلغاء"
                        capturedWindow != VolumeKeyService.contextVersion() -> "تغيّر التطبيق الظاهر"
                        else -> "تغيّر مقاس الشاشة"
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (sig != null) {
                    lastSentSig = sig
                    // Once handed off, touch execution alone decides whether a stroke could have begun.
                    if (!touchPending && requestIsCurrent())
                        retryAttempt?.finish(retryable = true, SystemClock.uptimeMillis())
                }
                if (!destroyed) try { showPanel("تعذّرت المحاولة؛ يمكنك طلب الإجابة من جديد") } catch (_: Throwable) { }
            } finally {
                // Touch execution owns busy until its own finally block. All other exits release it.
                if (!touchPending) {
                    retryAttempt?.cancel()
                    setBusy(false)
                }
            }
        }
    }

    /** Bind every answer source to the same interaction engine. All pixel/OCR work here is on IO. */
    private fun prepareTouches(solved: Solved, bmp: Bitmap, masks: List<PxRect>): Solved {
        // Refusals without a message of their own: the panel stays as it was, the step goes to the log.
        fun quiet(code: String): Solved { DiagLog.add("رفض اللمس: $code"); return solved }
        return try {
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val request = solved.request
            val q = request?.question
            val picture = solved.pictureRect
            fun overlaps(a: PxRect, b: PxRect) = a.left < b.right && b.left < a.right &&
                a.top < b.bottom && b.top < a.bottom
            if (picture != null && TouchFrameMask.obscuresPicture(picture, masks)) {
                return solved.copy(touchFailure = "الفقاعة تغطّي الصورة؛ أبعدها ثم اطلب الجواب")
            }
            if (request != null && q != null && !q.numeric) {
                val banner = q.bannerRect ?: return quiet("no banner")
                val words = q.optionTextRects.takeIf { it.size == q.options.size } ?: q.optionRects
                if (!ChoiceTouchGuard.isReadyFromLayout(pixels, w, h, banner, q.optionRects, words)) {
                    return solved.copy(touchFailure = "الأزرار غير جاهزة أو بعض الكتابة محجوب")
                }
                val plan = AnswerInteraction.plan(request, AnswerInteraction.Controls(w, h))
                    ?: return solved.copy(touchFailure = "الإجابة لا تطابق خيارًا واضحًا؛ لم يتم الضغط")
                val questionRegion = questionRegion(q, pixels, w, h) ?: return solved.copy(
                    touchFailure = "لم أجد منطقة السؤال كاملة للتحقق قبل اللمس")
                return solved.copy(touches = plan.taps, guardRect = questionRegion,
                    guardStamp = fingerprint(bmp, questionRegion, masks), ignoredRects = masks,
                    choiceButtons = q.optionRects, choiceWords = words, choiceFromLayout = true,
                    choiceStamps = words.mapNotNull { fingerprint(bmp, it, masks) },
                    pictureStamp = picture?.let { fingerprint(bmp, it, masks) })
            }
            // Verified skin detectors are optional geometry adapters. A learned number field alone
            // cannot authorize a guessed keypad; future adapters can supply other verified controls.
            val visual = GameVisualLayout.detect(pixels, w, h)
                ?.takeIf { it.kind != GameVisualLayout.Kind.CHOICE_GRID }
                ?.let { if (it.submit != null && !GameVisualLayout.hasSubmitControl(pixels, w, h, it))
                    it.copy(submit = null) else it }
            // A number question on a pad no adapter knows: the pad the user drew, if any.
            if (q?.numeric == true && visual == null) return calibratedTouches(solved, request, q, pixels, w, h, bmp, masks)
            if (q?.numeric == true || request == null && visual != null) {
                if (visual == null) return solved.copy(touchFailure = "لوحة الأرقام غير معروفة بعد؛ لم أكتب شيئًا")
                // The keypad adapter measures this skin's field; a learned box can include its bevel or wood.
                // The dark skin's reference box can reach into the field's gold rim when the card is
                // drawn off the reference (owner's phone): check and read only the measured inside.
                val measured = visual.field?.let {
                    if (visual.kind == GameVisualLayout.Kind.NUMERIC_DARK)
                        GameVisualLayout.fieldInterior(pixels, w, h, it) ?: it else it
                }
                val field = measured ?: q?.numericField ?: return quiet("no field")
                val ink = if (visual.kind == GameVisualLayout.Kind.NUMERIC_DARK)
                    NumericFieldReader.Ink.GOLD else NumericFieldReader.Ink.LIGHT
                // Our own window over the field hides what is in it: never call that empty.
                if (masks.any { overlaps(it, field) }) return solved.copy(touchFailure =
                    "الفقاعة فوق خانة الرقم؛ أبعدها ثم اطلب الجواب")
                val sample = NumericFieldInk.inspect(pixels, w, h, field, ink)
                DiagLog.add("خانة الرقم ${field.width}x${field.height}: ${sample.state} ${sample.why}")
                when (sample.state) {
                    NumericFieldInk.State.FILLED -> return solved.copy(touchFailure =
                        "الخانة فيها إدخال سابق؛ لم أمسحه ولم أضف عليه")
                    NumericFieldInk.State.UNKNOWN -> return solved.copy(touchFailure =
                        "لم أتحقق أن الخانة فارغة؛ لم أكتب أو أمسح شيئًا")
                    NumericFieldInk.State.EMPTY -> Unit
                }
                val numericRequest = request ?: AnswerInteraction.Request(
                    Question("", emptyList(), emptyList(), true, 1f, numericField = field),
                    solved.rawReply, solved.rawReply)
                val plan = AnswerInteraction.plan(numericRequest, AnswerInteraction.Controls(
                    w, h, visual.digits, submit = visual.submit, fieldIsEmpty = true))
                    ?: return solved.copy(touchFailure = "تعذّر تحديد مفاتيح إدخال رقم من 1–4 خانات")
                val banner = q?.let { questionRegion(it, pixels, w, h) }?.takeUnless { overlaps(it, field) }
                    ?: visual.questionArea
                return solved.copy(touches = plan.taps, guardRect = banner,
                    guardStamp = fingerprint(bmp, banner, masks), ignoredRects = masks,
                    numericLayout = visual.copy(clear = null, backspace = null), submitLast = visual.submit != null,
                    expectedNumber = plan.number, fieldRect = field, fieldStamp = fingerprint(bmp, field, masks),
                    pictureStamp = picture?.let { fingerprint(bmp, it, masks) })
            }
            if (request != null) return quiet("read question, no match") // never replace a rejected trusted reading with guessed positions
            // A mixed Arabic/Latin question may fail both question readers while every option is clear.
            // Geometry is detected again from the visible banner/buttons; candidate/model boxes are not used.
            // This fallback initially supports the four-row example. Two remaining white rows on
            // a red/green result screen must not be mistaken for a fresh two-option question.
            val first = ColorLayout().inspect(pixels, w, h)
            // The question text could not be cut (something over it), but the buttons may still be
            // verified and their words read: then the banner itself guards the touch.
            val stackInspection = if (first.layout == null && first.rejection == "question text band merged")
                ColorLayout().inspect(pixels, w, h, keepMergedQuestion = true) else first
            val stack = stackInspection.layout?.takeIf { !it.numeric && it.buttons.size == 4 }
            val stackWhy = stackInspection.rejection ?: stackInspection.layout?.let {
                if (it.numeric) "numeric" else "${it.buttons.size} buttons" } ?: "-"
            val stackWords = stack?.let { PrintedChoiceTouch.regions(it, pixels, w, h) }
            if (stack != null && (stack.questionLines.isNotEmpty() || first.layout == null) && stackWords != null) {
                val words = stackWords
                if (ChoiceTouchGuard.isReadyFromLayout(pixels, w, h, stack.banner, stack.buttons, words)) {
                    val printed = synchronized(ocrLock) { ocr?.readWords(bmp, words) }.orEmpty()
                    val independent = PrintedChoiceTouch.request(stack, printed, solved.rawReply)
                    val plan = independent?.let { AnswerInteraction.plan(it, AnswerInteraction.Controls(w, h)) }
                    val region = independent?.question?.let { questionRegion(it, pixels, w, h) }
                        ?: stack.banner.takeIf { independent != null && stack.questionLines.isEmpty() }
                    if (plan != null && region != null) return solved.copy(touches = plan.taps,
                        guardRect = region, guardStamp = fingerprint(bmp, region, masks), ignoredRects = masks,
                        choiceButtons = stack.buttons, choiceWords = words, choiceFromLayout = true,
                        choiceStamps = words.mapNotNull { fingerprint(bmp, it, masks) })
                }
            }
            // Legacy fallback: independently read all four printed words; candidate/model boxes alone
            // never authorize a touch. The original reply is retained even when display matching was fuzzy.
            val choices = OptionWordDetector.detectChoices(pixels, w, h) ?: return quiet("no choices found ($stackWhy)")
            if (!ChoiceTouchGuard.isReady(pixels, w, h, choices.buttons, choices.words)) return quiet("choices not ready")
            val printed = synchronized(ocrLock) { ocr?.readWords(bmp, choices.words) } ?: return quiet("words not read")
            val printedOptions = printed.map { it.text }
            val literal = printedOptions.singleOrNull {
                AnswerInteraction.matchesChoice(solved.rawReply, it, printedOptions, indexRequested = false)
            } ?: return quiet("answer not among the words read: " + printedOptions.joinToString("|").take(80))
            val target = WordAnswerMatcher.match(literal, printed) ?: return quiet("word not placed")
            val actions = TouchPlanner.plan(solved.rawReply, target, emptyMap(), null, w, h) ?: return quiet("no plan")
            val content = ChoiceTouchGuard.stableContent(pixels, w, h, choices.buttons) ?: return solved.copy(
                touchFailure = "لم أجد نص السؤال كاملًا للتحقق قبل اللمس")
            if (content.picture != null && TouchFrameMask.obscuresPicture(content.picture, masks)) return solved.copy(
                touchFailure = "الفقاعة تغطّي الصورة؛ أبعدها ثم اطلب الجواب")
            solved.copy(touches = actions, guardRect = content.question, guardStamp = fingerprint(bmp, content.question, masks),
                choiceButtons = choices.buttons, choiceWords = choices.words,
                choiceStamps = choices.words.mapNotNull { fingerprint(bmp, it, masks) }, ignoredRects = masks,
                pictureRect = content.picture, pictureStamp = content.picture?.let { fingerprint(bmp, it, masks) })
        } catch (e: Throwable) { quiet("error ${e.javaClass.simpleName}") }
    }

    /** Use measured question ink, not a banner that can also contain an animated badge. */
    private fun questionRegion(q: Question, pixels: IntArray, w: Int, h: Int): PxRect? {
        val lines = q.questionTextRects.ifEmpty {
            val banner = q.bannerRect ?: return null
            val found = TextLines.findDetailed(pixels, w, h, banner, bounded = true)
            if (found.clipped) return null
            found.lines
        }
        if (lines.isEmpty()) return null
        return PxRect(lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom })
    }

    /** Fresh field readback finishes asynchronously; no model lock or native inference on the UI thread. */
    private fun verifyEntry(solved: Solved, epoch: Long, complete: (Boolean) -> Unit) {
        val expected = solved.expectedNumber
        val field = solved.fieldRect
        val initial = solved.fieldStamp
        if (expected == null || field == null || initial == null || destroyed || epoch != touchEpoch) {
            complete(false)
            return
        }
        if (overlayRects().any { it.left < field.right && field.left < it.right &&
                it.top < field.bottom && field.top < it.bottom }) { complete(false); return }
        scope.launch {
            // Accessibility's UP may precede the game's next rendered frame. Read again, never retype.
            repeat(3) { attempt ->
                if (destroyed || epoch != touchEpoch) { complete(false); return@launch }
                if (attempt > 0) delay(120)
                grabFrame()
                val frame = lastFrame
                val current = frame?.let { fingerprint(it, field, solved.ignoredRects) }
                if (frame != null && current != null && NumericEntryCheck.fieldChanged(initial, current)) {
                    if (field.left < 0 || field.top < 0 || field.right > frame.width ||
                        field.bottom > frame.height || field.width <= 0 || field.height <= 0) {
                        complete(false)
                        return@launch
                    }
                    // Rounded gold fields need neighboring parchment to distinguish their corners
                    // from clipped digits. Keep two pixels of context without widening the OCR field.
                    val context = PxRect(maxOf(0, field.left - 2), maxOf(0, field.top - 2),
                        minOf(frame.width, field.right + 2), minOf(frame.height, field.bottom + 2))
                    val localField = PxRect(field.left - context.left, field.top - context.top,
                        field.right - context.left, field.bottom - context.top)
                    val snapshot = try {
                        val cropped = Bitmap.createBitmap(frame, context.left, context.top, context.width, context.height)
                        // A whole-frame crop can alias lastFrame, which the next capture recycles.
                        if (cropped === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else cropped
                    } catch (_: Throwable) { null }
                    if (snapshot != null) {
                        val matches = withContext(Dispatchers.IO) {
                            try {
                                // A calibrated pad's ink colour is not known: the generic line reader.
                                val ink = when {
                                    solved.calibratedKeys != null -> NumericFieldReader.Ink.AUTO
                                    solved.numericLayout?.kind == GameVisualLayout.Kind.NUMERIC_DARK -> NumericFieldReader.Ink.GOLD
                                    else -> NumericFieldReader.Ink.LIGHT
                                }
                                val read = synchronized(ocrLock) { ocr?.readField(snapshot, localField, ink) }
                                read != null && NumericEntryCheck.matches(expected, read.text, read.confidence, changed = true)
                            } catch (_: Throwable) { false }
                            finally { snapshot.recycle() }
                        }
                        if (matches) { complete(!destroyed && epoch == touchEpoch); return@launch }
                    }
                }
            }
            complete(false)
        }
    }

    /**
     * Number entry on a pad the user drew ([CalibratedKeypad]): no built-in adapter knows this game.
     * The same rules as the built-in pads: the field must be proven empty (here against its empty
     * look when calibrated), digits then the confirm button only, and the confirm is sent only after
     * the field reads back the expected number ([verifyNumericEntry] with the generic reader).
     */
    private fun calibratedTouches(
        solved: Solved, request: AnswerInteraction.Request?, q: Question, pixels: IntArray, w: Int, h: Int,
        bmp: Bitmap, masks: List<PxRect>,
    ): Solved {
        val fp = ScreenFingerprint.of(pixels, w, h, masks)
        val keys = CalibratedKeypad.find(TemplateStore.templates.find(fp, null, trustedOnly = true), pixels, w, h)
            ?: return solved.copy(touchFailure = "لوحة الأرقام غير معروفة بعد؛ علّمها من المعايرة اليدوية")
        if (masks.any { it.left < keys.field.right && keys.field.left < it.right && it.top < keys.field.bottom &&
                keys.field.top < it.bottom }) return solved.copy(touchFailure = "الفقاعة فوق خانة الرقم؛ أبعدها ثم اطلب الجواب")
        when (CalibratedKeypad.fieldState(keys, pixels, w, h)) {
            FieldStamp.State.FILLED -> return solved.copy(touchFailure = "الخانة فيها إدخال سابق؛ لم أمسحه ولم أضف عليه")
            FieldStamp.State.UNKNOWN -> return solved.copy(touchFailure = "لم أتحقق أن الخانة فارغة؛ لم أكتب أو أمسح شيئًا")
            FieldStamp.State.EMPTY -> Unit
        }
        val numericRequest = request ?: return solved
        val plan = AnswerInteraction.plan(numericRequest, AnswerInteraction.Controls(
            w, h, keys.digits, submit = keys.submit, fieldIsEmpty = true))
            ?: return solved.copy(touchFailure = "تعذّر تحديد مفاتيح إدخال رقم من 1–4 خانات")
        val banner = questionRegion(q, pixels, w, h)?.takeUnless {
            it.left < keys.field.right && keys.field.left < it.right && it.top < keys.field.bottom && keys.field.top < it.bottom
        } ?: return solved.copy(touchFailure = "لم أجد منطقة السؤال كاملة للتحقق قبل اللمس")
        return solved.copy(touches = plan.taps, guardRect = banner,
            guardStamp = fingerprint(bmp, banner, masks), ignoredRects = masks,
            calibratedKeys = keys, submitLast = keys.submit != null,
            expectedNumber = plan.number, fieldRect = keys.field, fieldStamp = fingerprint(bmp, keys.field, masks),
            pictureStamp = solved.pictureRect?.let { fingerprint(bmp, it, masks) })
    }

    /** Preserve every question line, including its final word; input fields are outside [area]. */
    private fun fingerprint(bitmap: Bitmap, area: PxRect, ignored: List<PxRect> = emptyList()): IntArray? {
        val left = area.left.coerceIn(0, bitmap.width)
        val top = area.top.coerceIn(0, bitmap.height)
        val right = area.right.coerceIn(0, bitmap.width)
        val bottom = area.bottom.coerceIn(0, bitmap.height)
        if (right <= left || bottom <= top) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val small = Bitmap.createScaledBitmap(crop, TouchFrameGuard.WIDTH, TouchFrameGuard.HEIGHT, true)
        if (crop !== small && crop !== bitmap) crop.recycle()
        val pixels = IntArray(TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT)
        small.getPixels(pixels, 0, TouchFrameGuard.WIDTH, 0, 0, TouchFrameGuard.WIDTH, TouchFrameGuard.HEIGHT)
        if (small !== bitmap) small.recycle()
        val stamp = IntArray(pixels.size) { i ->
            val c = pixels[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        return TouchFrameMask.apply(stamp, PxRect(left, top, right, bottom), ignored)
    }

    private fun performAnswerTouches(solved: Solved, w: Int, h: Int, trigger: AnswerTrigger,
                                     retryAttempt: AutoAnswerRetry.Attempt?) {
        fun notStarted(why: String) {
            DiagLog.add("اللمس لم يبدأ: $why")
            retryAttempt?.finish(retryable = true, SystemClock.uptimeMillis())
        }
        val boxes = solved.touches ?: return notStarted("لا خطة لمس")
        val stamp = solved.guardStamp ?: return notStarted("لا بصمة للسؤال")
        val region = solved.guardRect ?: return notStarted("لا منطقة للسؤال")
        if (!TouchFrameGuard.usable(stamp)) return notStarted("بصمة السؤال غير صالحة")
        if (touchPending || !VolumeKeyService.isEnabled(this)) {
            if (!VolumeKeyService.isEnabled(this)) showPanel("فعّل خدمة إمكانية الوصول لتشغيل اللمس التلقائي")
            return notStarted(if (touchPending) "لمس سابق لسا شغّال" else "خدمة إمكانية الوصول مطفية")
        }
        touchPending = true
        val epoch = touchEpoch
        val window = VolumeKeyService.contextVersion()
        var nextTap = 0
        var mayHaveDispatched = false
        var entryRejected = false
        var cancelReason = "توقف الإدخال؛ تحقق من الشاشة"
        fun blocked(reason: String): Boolean { cancelReason = reason; return false }
        fun canContinue(): Boolean {
            if (destroyed || epoch != touchEpoch || !permitsTouch(trigger) ||
                window != VolumeKeyService.contextVersion() ||
                w != screenW || h != screenH || preview != null || projection == null
            ) return blocked("تغيّر التطبيق أو وضع الشاشة؛ أُلغي اللمس")
            return try {
                grabFrame()
                val frame = lastFrame ?: return blocked("لم تصل لقطة شاشة جديدة؛ لم أضغط")
                val ignored = solved.ignoredRects + overlayRects().map { PxRect(it.left, it.top, it.right, it.bottom) }
                if (frame.width != w || frame.height != h) return blocked("تغيّر مقاس اللقطة؛ لم أضغط")
                val nowStamp = fingerprint(frame, region, ignored)
                if (nowStamp?.let { TouchFrameGuard.matches(stamp, it) } != true) {
                    DiagLog.add("فحص السؤال قبل الضغطة ${nextTap + 1}: " +
                        (nowStamp?.let { TouchFrameGuard.explain(stamp, it) } ?: "لا لقطة للمنطقة"))
                    return blocked("تغيّر نص السؤال أو لم تستقر لقطته؛ لم أضغط")
                }
                val picture = solved.pictureRect
                if (picture != null && (solved.pictureStamp == null ||
                    fingerprint(frame, picture, ignored)?.let { TouchFrameGuard.matches(solved.pictureStamp, it) } != true
                )) return blocked("تغيّرت الصورة أو حُجب جزء منها؛ لم أضغط")
                val obstruction = viewRect(bubble)
                if (obstruction != null && boxes.any { r ->
                    r.left < obstruction.right && r.right > obstruction.left &&
                        r.top < obstruction.bottom && r.bottom > obstruction.top
                }) return blocked("الفقاعة تغطّي زر الإجابة؛ أبعدها ثم أعد الطلب")
                val numeric = solved.numericLayout
                val calibrated = solved.calibratedKeys
                if (calibrated != null) {
                    val pixels = IntArray(w * h)
                    frame.getPixels(pixels, 0, w, 0, 0, w, h)
                    if (!CalibratedKeypad.stillVisible(calibrated, pixels, w, h, boxes.getOrNull(nextTap - 1),
                            checkSubmit = nextTap == 0))
                        return blocked("لوحة الأرقام أو زر التأكيد لم يعد ظاهرًا؛ أوقفت الإدخال")
                    if (nextTap == 0 && CalibratedKeypad.fieldState(calibrated, pixels, w, h) != FieldStamp.State.EMPTY)
                        return blocked("الخانة لم تعد فارغة؛ لم أمسح أو أضف أرقامًا")
                }
                if (numeric != null || solved.choiceButtons.isNotEmpty()) {
                    val pixels = IntArray(w * h)
                    frame.getPixels(pixels, 0, w, 0, 0, w, h)
                    if (numeric != null) {
                        val padThere = GameVisualLayout.stillVisible(pixels, w, h, numeric, boxes.getOrNull(nextTap - 1))
                        val submitThere = numeric.submit == null || GameVisualLayout.hasSubmitControl(pixels, w, h, numeric)
                        if (!padThere || !submitThere) {
                            DiagLog.add("لوحة الأرقام قبل الضغطة ${nextTap + 1}: المفاتيح=${if (padThere) "ظاهرة" else "لا"}" +
                                " التأكيد=${if (submitThere) "ظاهر" else "لا"}")
                            return blocked("لوحة الأرقام أو زر التأكيد لم يعد ظاهرًا؛ أوقفت الإدخال")
                        }
                    }
                    if (numeric != null && nextTap == 0) {
                        val field = solved.fieldRect ?: return false
                        if (obstruction != null && field.left < obstruction.right && field.right > obstruction.left &&
                            field.top < obstruction.bottom && field.bottom > obstruction.top
                        ) return blocked("الفقاعة فوق خانة الرقم؛ أبعدها ثم أعد الطلب")
                        val ink = if (numeric.kind == GameVisualLayout.Kind.NUMERIC_DARK)
                            NumericFieldReader.Ink.GOLD else NumericFieldReader.Ink.LIGHT
                        val sample = NumericFieldInk.inspect(pixels, w, h, field, ink)
                        if (sample.state != NumericFieldInk.State.EMPTY) {
                            DiagLog.add("خانة الرقم قبل أول رقم: ${sample.state} ${sample.why}")
                            return blocked("الخانة لم تعد فارغة؛ لم أمسح أو أضف أرقامًا")
                        }
                    }
                    if (solved.choiceButtons.isNotEmpty()) {
                        val ready = if (solved.choiceFromLayout) ChoiceTouchGuard.isReadyFromLayout(
                            pixels, w, h, region, solved.choiceButtons, solved.choiceWords)
                        else ChoiceTouchGuard.isReady(pixels, w, h, solved.choiceButtons, solved.choiceWords)
                        if (!ready) {
                            val which = if (solved.choiceFromLayout) runCatching {
                                ChoiceTouchGuard.firstUnreadyFromLayout(pixels, w, h, region, solved.choiceButtons, solved.choiceWords)
                            }.getOrDefault(-1) else -1
                            DiagLog.add("الأزرار غير جاهزة قبل الضغطة ${nextTap + 1}: " +
                                if (which > 0) "الزر $which (ملوّن أو مغطّى)" else "شكل الأزرار تغيّر")
                            return blocked("أزرار الإجابة غير جاهزة للضغط؛ لم أضغط")
                        }
                    }
                    if (solved.choiceButtons.isNotEmpty() &&
                        (solved.choiceStamps.size != solved.choiceButtons.size ||
                            solved.choiceWords.size != solved.choiceButtons.size || solved.choiceWords.indices.any { index ->
                            fingerprint(frame, solved.choiceWords[index], ignored)?.let {
                                TouchFrameGuard.matches(solved.choiceStamps[index], it)
                            } != true
                        })
                    ) return blocked("تغيّر نص الخيارات أو حُجب؛ لم أضغط")
                }
                true
            } catch (_: Throwable) { blocked("تعذّر فحص الشاشة؛ أوقفت اللمس") }
        }
        touchJob = scope.launch {
            try {
                // Includes queued capture frames, all gesture callbacks and numeric readback.
                // Even a missing platform callback cannot leave the bubble busy indefinitely.
                withTimeout(15_000L) {
                    delay(80L)
                    if (destroyed || epoch != touchEpoch || !permitsTouch(trigger) ||
                        w != screenW || h != screenH || preview != null) {
                        DiagLog.add("اللمس لم يبدأ: تغيّرت الحالة (محاولة أحدث، الإعدادات، الدوران أو الصورة المرسلة)")
                        return@withTimeout
                    }
                    hidePanel()
                    hideHighlight()
                    val removedAt = System.nanoTime()
                    delay(100L)
                    // The checks below compare the question with the capture; a frame that still shows
                    // our answer panel over it fails them ("the question changed", "buttons not
                    // ready"). On the owner's phone the first frame without the panel came 236-272 ms
                    // after it was hidden (log, 26 Oct 2026), later than the checks' three tries.
                    var frameWaits = 0
                    while (frameWaits < CLEAN_TOUCH_POLLS) {
                        try { grabFrame() } catch (_: Throwable) { }
                        val at = lastFrameAtNs
                        val trusted = at > 0 && abs(at - System.nanoTime()) < 60_000_000_000L
                        if (!trusted || at > removedAt) break
                        delay(CLEAN_POLL_MS)
                        frameWaits++
                    }
                    if (frameWaits > 0) DiagLog.add("اللمس: انتظار إطار بدون اللوحة ${100 + frameWaits * CLEAN_POLL_MS}ms")
                    val ready = suspendCancellableCoroutine<Boolean> { continuation ->
                        TouchPreflight { delay, action -> check(handler.postDelayed({ action() }, delay)) }
                            .awaitReady(
                                isCurrent = { continuation.isActive && !destroyed && epoch == touchEpoch },
                                ready = ::canContinue,
                            ) { if (continuation.isActive) continuation.resume(it) }
                    }
                    if (!ready) {
                        DiagLog.add("اللمس لم يبدأ: $cancelReason")
                        showPanel("${solved.text}\n$cancelReason")
                        return@withTimeout
                    }
                    if (!touchAttempts.tryBegin(stamp, w, h)) {
                        DiagLog.add("اللمس: نفس الشاشة جُرّبت قبل، تُركت")
                        // This question already had an accepted run; it must not be replayed.
                        mayHaveDispatched = true
                        return@withTimeout
                    }
                    setBusy(true)
                    val finished = suspendCancellableCoroutine<Boolean> { continuation ->
                        // If the platform throws after accepting input, its outcome is uncertain.
                        mayHaveDispatched = true
                        val accepted = VolumeKeyService.tapAnswer(boxes, solved.submitLast, canContinue = {
                            continuation.isActive && canContinue().also { if (it) nextTap++ }
                        }, beforeSubmit = { complete -> verifyEntry(solved, epoch) { allowed ->
                            entryRejected = !allowed
                            complete(allowed)
                        } }) { if (continuation.isActive) continuation.resume(it) }
                        if (!accepted) {
                            // A refused request sent no stroke. Accepted runs stay consumed.
                            mayHaveDispatched = false
                            touchAttempts.reset()
                            cancelReason = "خدمة اللمس غير جاهزة أو مشغولة"
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                    DiagLog.add(when {
                        finished -> "اللمس اكتمل (${boxes.size} ضغطة)"
                        entryRejected -> "اللمس: الرقم المقروء بالخانة ما طابق، لم يُضغط التأكيد"
                        else -> "اللمس توقف: $cancelReason"
                    })
                    if (!finished) showPanel(if (entryRejected) "تعذّر التحقق من الرقم؛ لم يتم التأكيد"
                        else cancelReason)
                }
            } catch (_: TimeoutCancellationException) {
                DiagLog.add("اللمس: انتهت المهلة")
                if (!destroyed && epoch == touchEpoch) try {
                    showPanel("انتهت مهلة اللمس؛ جاهز للسؤال التالي")
                } catch (_: Throwable) { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!destroyed && epoch == touchEpoch) try {
                    showPanel("تعذّر إكمال اللمس؛ جاهز للسؤال التالي")
                } catch (_: Throwable) { }
            } finally {
                try {
                    if (!destroyed && epoch == touchEpoch && window == VolumeKeyService.contextVersion() &&
                        permitsTouch(trigger) && w == screenW && h == screenH) {
                        retryAttempt?.finish(retryable = !mayHaveDispatched, SystemClock.uptimeMillis())
                    } else retryAttempt?.cancel()
                } finally {
                    if (epoch == touchEpoch) {
                        // Invalidate any late readback before releasing the next request.
                        touchEpoch++
                        try { VolumeKeyService.cancelAnswer() } finally {
                            touchPending = false
                            touchJob = null
                            setBusy(false)
                        }
                    }
                }
            }
        }
    }

    /**
     * What one answer produced: the text for the bar, the option's button to box (screen pixels),
     * the picture "آخر صورة" shows, and the memory entry it came from (null if not from memory).
     * [isAnswer]: the text is an answer (from memory or the model), not an error or "no question";
     * only an answer gets a haptic tick (see [Reply.isAnswer]).
     * [source]: which step produced it, for the statistics; no default, so a new path cannot forget it.
     */
    private data class Solved(
        val text: String,
        val rect: PxRect?,
        val image: Bitmap?,
        val memoryKey: String?,
        val isAnswer: Boolean,
        val source: AnswerSource,
        /** A screen to learn the layout of once the answer is shown (see [learnLayout]), or null. */
        val learn: LearnFrame? = null,
        /** What to watch the game's result screen for after this answer (see [watchResult]), or null. */
        val watch: ResultWatch? = null,
        val request: AnswerInteraction.Request? = null,
        val rawReply: String = text,
        val pictureRect: PxRect? = null,
        val touches: List<PxRect>? = null,
        val guardRect: PxRect? = null,
        val guardStamp: IntArray? = null,
        val numericLayout: GameVisualLayout.Detected? = null,
        /** The pad the user drew, when no built-in adapter knows the game (see [calibratedTouches]). */
        val calibratedKeys: CalibratedKeypad.Keys? = null,
        val submitLast: Boolean = false,
        val choiceButtons: List<PxRect> = emptyList(),
        val choiceWords: List<PxRect> = emptyList(),
        val choiceStamps: List<IntArray> = emptyList(),
        val choiceFromLayout: Boolean = false,
        val ignoredRects: List<PxRect> = emptyList(),
        val pictureStamp: IntArray? = null,
        val fieldRect: PxRect? = null,
        val fieldStamp: IntArray? = null,
        val expectedNumber: String? = null,
        val touchFailure: String? = null,
    )

    /**
     * What a result watch needs: the multiple-choice [question] as read on the device (its option
     * texts and buttons) and the [answer], the option's text that was shown for it.
     */
    private class ResultWatch(val question: Question, val answer: String)

    /**
     * A result watch for [answer] to [q], or null. Only for a multiple-choice question read on the
     * device with a button for every option: the colour the game shows has to be tied to an option
     * on the screen, and a number or a question that was not read has none.
     */
    private fun watchFor(q: Question?, answer: String): ResultWatch? =
        if (q != null && !q.numeric && q.optionRects.size == q.options.size) ResultWatch(q, answer) else null

    /**
     * A copy of a screen no reader or template could read, kept to ask [client] for its layout in
     * the background. [bitmap] is ours: [startLearning] or [dropLearning] recycles it.
     */
    private class LearnFrame(val bitmap: Bitmap, val masks: List<PxRect>, val fingerprint: String, val client: Answerer)

    /**
     * What the device read: [question] (null when nothing could be read), a [candidate] template's
     * reading (only to be checked against the model's answer, never used to answer), the screen's
     * [fingerprint] when templates were tried (null when [ColorLayout] took the screen), and
     * whether some template [fits] the screen (then there is nothing new to learn). [picture] is the
     * picture the question shows, only for a question a TRUSTED template read on a screen where it
     * pins one (see [shownPicture]); null for everything else.
     */
    private class DeviceRead(
        val question: Question?,
        val candidate: Pair<String, Question>? = null,
        val fingerprint: String? = null,
        val fits: Boolean = false,
        val picture: ShownPicture? = null,
        /** True when [ColorLayout] read the screen (the original game). */
        val colorLayout: Boolean = false,
    )

    /** The picture a question shows: where it is in the capture (pixels) and its [PictureRegion.hash]. */
    private class ShownPicture(val rect: PxRect, val hash: String)

    /**
     * Answers the question on [bmp] (blocking; run off the main thread). [masks] are the places where
     * our own windows were painted black over it.
     * 1. Read it on the device. If that works: answer from memory when the question was seen before
     *    (unless it is [bypassKey], the answer being corrected), else ask the model with text only and
     *    remember the answer. A question about a picture skips this: its words do not decide it. When
     *    the game's picture is shown (see [DeviceRead.picture]) the whole screenshot answers it
     *    (the faster measured path), and it is remembered per picture, whatever its words say.
     * 2. Otherwise, or when the text answer cannot be used, send the screenshot as before. If the
     *    question was read, an image answer that names one of its options still gets the box and is
     *    remembered. A correction goes straight here: the text request would get the same reply again.
     *
     * Both requests share [deadline]: when it runs out they answer with the timeout error, which ends
     * the question (see [Reply.Kind.ERROR]).
     */
    private fun solve(bmp: Bitmap, masks: List<PxRect>, bypassKey: String?, deadline: AnswerDeadline): Solved {
        val c = currentClient()
        val memory = AnswerStore.memory
        val read = if (Prefs.smartReading(this)) readQuestion(bmp, masks) else DeviceRead(null)
        val q = read.question
        val picture = if (q != null) read.picture else null
        fun resolved(
            r: AnswerParser.Resolved, image: Bitmap?, source: AnswerSource,
            reply: String = r.answer, indexRequested: Boolean = false,
            key: String? = null,
        ): Solved = Solved(
            if (source == AnswerSource.MEMORY) "⚡ ${r.answer}" else r.answer,
            r.rect, image, key, true, source,
            watch = if (picture == null) watchFor(q, r.answer) else null,
            request = q?.let { AnswerInteraction.Request(it, r.answer, reply, indexRequested,
                literalAnswer = source == AnswerSource.MEMORY || source == AnswerSource.CALCULATOR) },
            rawReply = reply, pictureRect = picture?.rect,
        )
        fun canRemember(r: AnswerParser.Resolved, reply: String, indexRequested: Boolean): Boolean =
            q != null && (q.numeric || AnswerInteraction.matchesChoice(reply, r.answer, q.options, indexRequested))
        // The cheapest rung of the answer ladder: arithmetic is computed here, before the memory and the model.
        if (q != null) Calculator.answer(q)?.let { r -> return resolved(r, shownCopy(bmp), AnswerSource.CALCULATOR) }
        // A shown picture decides the question, whatever its words say: never the text-only request.
        val wordsDecide = q != null && picture == null && !QuestionPrompt.refersToPicture(q.questionText)
        var corrected: AnswerMemory.Hit? = null
        if (q != null && picture != null) {
            val hit = memory.lookup(q.questionText, q.options, q.numeric, picture.hash)
            if (hit != null && hit.key == bypassKey) {
                corrected = hit
            } else {
                if (hit != null) {
                    AnswerParser.resolveStored(q, hit.answer)?.let { r ->
                        return resolved(r, shownCopy(bmp), AnswerSource.MEMORY, reply = hit.answer, key = hit.key)
                    }
                }
                // No cropped-picture request: on the owner's phone it took 5.5 s on average against 2.4 s
                // for the whole screenshot (Oct 2026 statistics), so the screenshot below answers it and
                // its answer is remembered for this picture.
            }
        }
        if (q != null && wordsDecide) {
            val hit = memory.lookup(q.questionText, q.options, q.numeric)
            if (hit != null && hit.key == bypassKey) {
                corrected = hit
            } else {
                if (hit != null) {
                    AnswerParser.resolveStored(q, hit.answer)?.let { r ->
                        return resolved(r, shownCopy(bmp), AnswerSource.MEMORY, reply = hit.answer, key = hit.key)
                    }
                }
                val reply = c.answerText(QuestionPrompt.build(q), deadline)
                when (reply.kind) {
                    Reply.Kind.ANSWER -> AnswerParser.resolve(q, reply.text)?.let { r ->
                        if (canRemember(r, reply.text, true)) memory.put(q.questionText, q.options, q.numeric, r.answer)
                        return resolved(r, shownCopy(bmp), AnswerSource.TEXT, reply.text, indexRequested = true)
                    }
                    // Quota, key, region, no connection: the image request would fail the same way.
                    Reply.Kind.ERROR -> return Solved(
                        reply.text, null, null, null, isAnswer = false, source = AnswerSource.FAILED,
                    )
                    Reply.Kind.EMPTY -> {}
                }
            }
        }
        // Cheap signals only (legacyVisibleKind stays in the retry below): the short game prompt when
        // the device read the question or knows the layout, the general one for any other screen.
        val prompt = if (q != null || read.candidate != null || read.fits || read.colorLayout) {
            ImagePrompt.GAME
        } else ImagePrompt.GENERAL
        val firstImageReply = c.answer(bmp, prompt, deadline)
        val fallbackKind = if (q == null && firstImageReply.kind == Reply.Kind.ANSWER &&
            ScreenImage.isNoQuestion(firstImageReply.text)) legacyVisibleKind(bmp) else null
        val imageReply = if (fallbackKind != null) {
            val focused = ScreenImage.focusQuiz(bmp, fallbackKind == GameVisualLayout.Kind.NUMERIC_WHITE)
            try {
                val retry = c.answer(focused, ImagePrompt.GAME, deadline)
                if (retry.isAnswer) retry else firstImageReply
            } finally { if (focused !== bmp) focused.recycle() }
        } else firstImageReply
        val text = imageReply.text
        // An error message is never matched against the options (or taken for a number).
        if (q != null && imageReply.kind == Reply.Kind.ANSWER) {
            val r = if (q.numeric) {
                // Only a bare number counts (an error message can contain digits too).
                (AnswerParser.numberOnly(text) ?: AnswerParser.numberOnly(AnswerParser.stripLoneFirstLabel(text)))
                    ?.let { AnswerParser.Resolved(it, null) }
            } else {
                AnswerParser.resolveImageAnswer(q, text)
            }
            if (r != null) {
                if ((wordsDecide || picture != null) && canRemember(r, text, false)) {
                    // The entry that was corrected goes, but only if it was this question's own: a fuzzy hit
                    // belongs to another question, whose answer is still right for it.
                    if (corrected != null && corrected.exact) memory.remove(corrected.key)
                    memory.put(q.questionText, q.options, q.numeric, r.answer, picture?.hash)
                }
                // Not for a question about a picture: the memory is never read for it (words do not decide it).
                return resolved(r, c.lastSent, AnswerSource.IMAGE, text).copy(
                    watch = if (wordsDecide) watchFor(q, r.answer) else null)
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
                return Solved(r.answer, r.rect, c.lastSent, null, isAnswer = true, source = AnswerSource.IMAGE, rawReply = text)
            }
        }
        val source = if (imageReply.isAnswer) AnswerSource.IMAGE else AnswerSource.FAILED
        // The options were read on the device and this reply is none of them: say so on the panel
        // instead of showing it as if it were an option (the owner saw "words that are not among
        // the options", 26 Oct 2026). Nothing is tapped or remembered for it either way.
        val offOptions = q != null && !q.numeric && imageReply.isAnswer && !ScreenImage.isNoQuestion(text)
        if (offOptions) DiagLog.add("الجواب مش من الخيارات المقروءة: «${text.take(40)}» / " + q!!.options.joinToString("|").take(80))
        return Solved(
            if (offOptions) "غير مؤكد: $text" else text, null, c.lastSent, null, imageReply.isAnswer, source,
            learnFrameFor(read, bmp, masks, c, imageReply, text), rawReply = text,
        )
    }

    private fun legacyVisibleKind(bitmap: Bitmap): GameVisualLayout.Kind? = try {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val visual = GameVisualLayout.detect(pixels, w, h)
        if (visual != null && visual.kind != GameVisualLayout.Kind.CHOICE_GRID) visual.kind else {
            val choices = OptionWordDetector.detectChoices(pixels, w, h)
            if (choices != null && ChoiceTouchGuard.isReady(pixels, w, h, choices.buttons, choices.words))
                GameVisualLayout.Kind.CHOICE_GRID else null
        }
    } catch (_: Throwable) { null }


    /**
     * A copy of [bmp] to learn its layout from, or null. Only for a screen the templates were tried
     * on (not the original game, which [ColorLayout] reads), that none of them fits, that the model
     * answered (not an error or "no question"), and only when [learningGate] lets a request go.
     */
    private fun learnFrameFor(read: DeviceRead, bmp: Bitmap, masks: List<PxRect>, c: Answerer, reply: Reply, text: String): LearnFrame? {
        val fp = read.fingerprint ?: return null
        if (read.fits || read.question != null || !reply.isAnswer || ScreenImage.isNoQuestion(text)) return null
        // Several answers, "unsure" or a sentence: not a quiz screen a layout can be learned from.
        if (!ReplyShape.isSingle(text) || text.trim().length > MAX_LEARN_REPLY_CHARS) return null
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
            val layout = inspected.layout
            if (layout == null) {
                val t = readWithTemplates(src, pixels, w, h, masks)
                DiagLog.add("قراءة: اللعبة الأصلية لا (${inspected.rejection})، القوالب: " + when {
                    t.question != null -> "قُرئ السؤال"
                    t.candidate != null -> "قالب مرشّح قرأه"
                    t.fits -> "قالب يطابق لكن ما انقرأ"
                    else -> "لا يوجد"
                })
                return t
            }
            val q = readWith(src, pixels, w, h, inspected, layout, masks, null)
            DiagLog.add("قراءة: اللعبة الأصلية، " + when {
                q == null -> "الشكل معروف لكن النص ما انقرأ"
                q.numeric -> "سؤال رقم"
                else -> "سؤال ${q.options.size} خيارات"
            })
            return DeviceRead(q, colorLayout = true)
        } catch (t: Throwable) {
            DiagLog.add("قراءة: خطأ ${t.javaClass.simpleName}")
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
            return DeviceRead(q, null, fp, fits = true, picture = shownPicture(t, pixels, w, h))
        }
        return DeviceRead(null, null, fp, fits)
    }

    /**
     * The picture the question on this screen shows, when [t] says where its game pins one
     * ([LayoutTemplate.picture]) and one is pinned now ([PictureRegion.isShown] on the right half of that
     * rect, which is plain background otherwise). Null for a template without a picture rect, which is
     * every template but the parchment grid, and for a question without a picture.
     */
    private fun shownPicture(t: LayoutTemplate, pixels: IntArray, w: Int, h: Int): ShownPicture? {
        val rect = t.picture?.toPx(w, h) ?: return null
        if (!PictureRegion.isShown(pixels, w, h, PictureRegion.detectArea(rect))) return null
        return ShownPicture(rect, PictureRegion.hash(pixels, w, h, rect))
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

    private fun stopResultWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /**
     * Starts watching the screen for the game's result of the question in [w] (replacing any watch
     * before it). [base] is the question screen's luminance grid from before the answer, the
     * reference for "still the same screen"; the option buttons are left out of it here, since they
     * are exactly what the result changes.
     */
    private fun startResultWatch(w: ResultWatch, base: IntArray) {
        stopResultWatch()
        for (r in w.question.optionRects) {
            FrameSignature.ignoreRect(base, SIG_SIZE, screenW, screenH, r.left, r.top, r.right, r.bottom)
        }
        watchJob = scope.launch {
            try {
                watchResult(w, base)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Learning is best effort: whatever went wrong, the answer already shown stands.
            }
        }
    }

    /**
     * After an answer, quiz games colour the correct option for a moment. Every [WATCH_INTERVAL_MS],
     * for at most [WATCH_MAX_MS], this reads the newest frame ([ResultReader.correctOption], on
     * Dispatchers.Default) and, once the same option is green in two checks in a row
     * ([ResultConfirmer]), makes the answer memory say what the game showed (see [learnFromResult]).
     *
     * It stops without learning when
     * - the screen no longer looks like the question's: the screen, leaving the option buttons out,
     *   moved on by more than [WATCH_SAME_SCREEN_DIFF] (a new question, another app), the screen was
     *   rotated, or a frame cannot be read. A result screen of a game that changes more than the
     *   buttons is then not learned from: declining is the safe side;
     * - the options could not be judged in 3 checks in a row (covered by our own windows, see
     *   [ResultConfirmer]).
     * Our green box is not among the masks (it sits outside the buttons, and the reader samples only
     * their interior); the bubble, the panel and the preview are, as the reader cannot see through them.
     *
     * Frames are grabbed on the main thread, as everywhere. In auto mode the tick takes the new frames
     * and the watch only reads the one it kept: a frame taken here would be missing from the tick,
     * which would then take the screen for unchanged and not send the next question. Only option
     * pixels are copied to a private buffer before analysis, because the next tick may recycle the frame.
     */
    private suspend fun watchResult(w: ResultWatch, base: IntArray) {
        val q = w.question
        val optionRects = q.optionRects.map { Rect(it.left, it.top, it.right, it.bottom) }
        val confirmer = ResultConfirmer()
        var pixels: IntArray? = null // one buffer for every check of this watch
        val until = SystemClock.uptimeMillis() + WATCH_MAX_MS
        while (SystemClock.uptimeMillis() < until) {
            delay(WATCH_INTERVAL_MS)
            if (!autoMode) grabFrame()
            val frame = lastFrame ?: continue
            if (frame.width != screenW || frame.height != screenH) return
            val overlays = overlayRects()
            val sig = try {
                signature(frame, overlays + listOfNotNull(viewRect(highlight)) + optionRects)
            } catch (t: Throwable) {
                return
            }
            if (FrameSignature.diff(sig, base) > WATCH_SAME_SCREEN_DIFF) return
            // Only the options' bounding box is read (about a twelfth of the screen), straight from the
            // frame on this thread: no copy of the whole capture twice a second for 20 seconds.
            val box = PxRect(
                q.optionRects.minOf { it.left }.coerceAtLeast(0), q.optionRects.minOf { it.top }.coerceAtLeast(0),
                q.optionRects.maxOf { it.right }.coerceAtMost(frame.width), q.optionRects.maxOf { it.bottom }.coerceAtMost(frame.height),
            )
            if (box.width <= 0 || box.height <= 0) return
            val buffer = pixels?.takeIf { it.size == box.width * box.height }
                ?: IntArray(box.width * box.height).also { pixels = it }
            try {
                frame.getPixels(buffer, 0, box.width, box.left, box.top, box.width, box.height)
            } catch (t: Throwable) {
                return
            }
            fun moved(r: PxRect) = PxRect(r.left - box.left, r.top - box.top, r.right - box.left, r.bottom - box.top)
            val options = q.optionRects.map(::moved)
            val masks = overlays.map { moved(PxRect(it.left, it.top, it.right, it.bottom)) }
            val reading = withContext(Dispatchers.Default) {
                try {
                    val usable = ResultReader.optionsUsable(box.width, box.height, options, masks)
                    Pair(usable, ResultReader.correctOption(buffer, box.width, box.height, options, masks))
                } catch (t: Throwable) {
                    null
                }
            } ?: return
            when (val step = confirmer.offer(reading.first, reading.second)) {
                is ResultConfirmer.Step.Act -> {
                    learnFromResult(w, step.index)
                    return
                }
                ResultConfirmer.Step.GiveUp -> return
                ResultConfirmer.Step.Wait -> {}
            }
        }
    }

    /**
     * The game showed option [index] of the watched question as the correct one. When that is not
     * the answer that was shown, the memory entry for the question is replaced with the game's: the
     * game knows, the memory (or the model it came from) was wrong. When it is the same, nothing to do.
     */
    private fun learnFromResult(w: ResultWatch, index: Int) {
        val q = w.question
        val correct = q.options.getOrNull(index) ?: return
        if (AnswerMemory.keyOf(correct) == AnswerMemory.keyOf(w.answer)) return
        AnswerStore.memory.put(q.questionText, q.options, false, correct)
    }

    private fun toggleAuto() {
        cancelTouches()
        autoMode = !autoMode
        if (autoMode) touchAttempts.reset()
        updateBubbleStyle()
        Toast.makeText(this, if (autoMode) "الوضع التلقائي: مفعّل" else "الوضع التلقائي: متوقف", Toast.LENGTH_SHORT).show()
        autoJob?.cancel()
        autoJob = null
        hiddenAnswer.clear()
        if (autoMode) {
            lastSentSig = null
            prevTickSig = null
            autoRetry.reset()
            autoJob = scope.launch {
                while (isActive) {
                    delay(AUTO_INTERVAL_MS)
                    try {
                        autoTick()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // A transient capture/overlay failure must not kill the automatic reader.
                        prevTickSig = null
                    }
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
        if (busy || touchPending || preview != null) return
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
        val retry = autoRetry.due(sig, SystemClock.uptimeMillis())
        val verdict = FrameSignature.judge(sig, previous, if (retry) null else lastSentSig,
            panelShown, CHANGE_THRESHOLD, STABLE_THRESHOLD)
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
        cancelTouches()
        volumeTrigger = null
        autoJob?.cancel()
        stopResultWatch()
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
