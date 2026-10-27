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
import android.graphics.Color
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import com.clu.quickanswer.ocr.ResultConfirmer
import com.clu.quickanswer.ocr.ResultReader
import com.clu.quickanswer.ocr.ScreenFingerprint
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
        // An answer that auto mode hid is brought back only if it still has this long to live.
        private const val HIDDEN_ANSWER_MIN_LEFT_MS = 1_000L

        // A second tap within this time after an answer from memory asks the model again and
        // overwrites the remembered answer (the way to correct a wrong one).
        private const val CORRECTION_WINDOW_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Warming is asked for on every trigger (bubble, volume key, auto mode seeing a change), so the
     * first question after a pause does not pay for a cold TLS handshake (~0.3 s); at most once a second.
     */
    private val warmUps = WarmUpThrottle(1_000L)

    private var projection: MediaProjection? = null
    private val capture = ScreenCapture(this)

    private val hiddenAnswer = HiddenAnswer()
    // The picture that goes with the answer last shown: what "آخر صورة" previews. Not client.lastSent,
    // which also moves on to auto-mode sends whose "no question" reply is never shown.
    private var shownImage: Bitmap? = null

    private val windows = OverlayWindows(
        context = this,
        handler = handler,
        capture = capture,
        hiddenAnswer = hiddenAnswer,
        isBusy = { busy },
        isAutoMode = { autoMode },
        onBubbleTouchDown = { warmUp() },
        onBubbleTap = { answerNow() },
        onBubbleLongPress = { toggleAuto() },
    )

    private val pipeline = AnswerPipeline(this, scope, isDestroyed = { destroyed })

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
    // Set when the service stops on purpose (the user's stop, or onDestroy), so only a projection the
    // system stopped (screen locked, status-bar chip) offers the "start again" notification.
    @Volatile private var stoppingByUs = false

    @Volatile private var destroyed = false

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
            if (projection == null) stopSelf() else windows.showLastImage(shownImage ?: pipeline.client?.lastSent)
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

        GeminiRuntime.init(this)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(RESTART_NOTIF_ID)
        pipeline.currentClient()
        warmUp()
        AnswerStore.init(this)
        TemplateStore.init(this)
        StatsStore.init(this)
        pipeline.loadOcr()
        pipeline.loadLatinOcr()

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

        capture.setupCapture(proj)
        windows.createBubble()
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        cancelTouches()
        // New screen size: the bubble may now be off-screen (landscape -> portrait), so pull it
        // back in first, then re-measure the panel (max width depends on orientation) and re-place
        // it (it follows the bubble in NEXT_TO_BUBBLE mode). The centred preview is sized for the
        // old screen, so just close it.
        // The box marks a button of the old layout (and must not push the re-placed panel around).
        windows.hideHighlight()
        if (capture.resizeCapture()) {
            // The auto mode's previous signature describes the old orientation.
            prevTickSig = null
            windows.clampBubble()
        }
        windows.refreshPanel()
        windows.hidePreview()
    }

    /** Lets the answer provider open its connection early. Never touches the network on the main thread. */
    private fun warmUp() {
        if (projection == null && pipeline.client == null) return
        if (!warmUps.tryAcquire(SystemClock.uptimeMillis())) return
        scope.launch(Dispatchers.IO) {
            try {
                pipeline.currentClient().warmUp()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun setBusy(b: Boolean) {
        busy = b
        windows.updateBubbleStyle()
    }

    private fun answerNow(trigger: AnswerTrigger = AnswerTrigger.BUBBLE) {
        if (busy || touchPending || destroyed) return
        // The volume key reaches here without a bubble touch-down: open the connection while we read.
        warmUp()
        stopResultWatch()
        hiddenAnswer.clear()
        if (windows.panelAdded || windows.preview != null) {
            // Remove the old answer (and the sent-image preview) first so they don't hide part of the question.
            windows.hidePanel()
            windows.hidePreview()
            setBusy(true)
            awaitFrameWithoutOverlays(trigger, SystemClock.uptimeMillis(), System.nanoTime())
            return
        }
        val bmp = capture.captureMasked(windows::overlayRects)
        if (bmp == null) {
            windows.showPanel("لم يتم الحصول على إجابة")
            return
        }
        // In auto mode count this as the last frame sent, or the next tick would see the question as
        // "new", hide this answer and ask again.
        touchAttempts.reset()
        autoRetry.reset()
        sendFrame(bmp, if (autoMode) capture.signature(bmp, windows.ignoreRects()) else null, fromAuto = false, trigger = trigger)
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
            try { capture.grabFrame() } catch (_: Throwable) { }
            val waited = SystemClock.uptimeMillis() - startMs
            val frameAt = capture.lastFrameAtNs
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
        val maskRects = capture.captureMasks.remove(bmp).orEmpty()
        val masks = maskRects.map { PxRect(it.left, it.top, it.right, it.bottom) }
        val capturedEpoch = touchEpoch
        val capturedWindow = VolumeKeyService.contextVersion()
        val touchRequested = permitsTouch(trigger)
        DiagLog.add("طلب ${trigger.name}${if (fromAuto) " تلقائي" else ""} ${capturedW}x$capturedH، لمس=${if (touchRequested) "نعم" else "لا"}")
        val retryAttempt = sig?.let { autoRetry.begin(it) }
        fun requestIsCurrent() = !destroyed && capturedEpoch == touchEpoch &&
            capturedWindow == VolumeKeyService.contextVersion() &&
            capturedW == capture.screenW && capturedH == capture.screenH
        // The question screen as the watch will compare it (see startResultWatch); [bmp] is gone after solve.
        val watchBase = if (Prefs.learnFromResults(this)) {
            try {
                    capture.signature(bmp, maskRects)
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
                        pipeline.solve(bmp, masks, bypass, deadline).let {
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
                        runCatching { work.await() }.getOrNull()?.learn?.let { pipeline.dropLearning(it) }
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
                    windows.showPanel(AnswerDeadline.TIMEOUT_TEXT)
                    return@launch
                }
                if (destroyed) {
                    solved.learn?.let { pipeline.dropLearning(it) }
                    return@launch
                }
                if (sig != null) {
                    lastSentSig = sig
                    if (requestIsCurrent()) retryAttempt?.resolved(solved.isAnswer, touchRequested,
                        solved.touches != null, SystemClock.uptimeMillis())
                    else retryAttempt?.cancel()
                }
                if (fromAuto && ScreenImage.isNoQuestion(solved.text)) {
                    solved.learn?.let { pipeline.dropLearning(it) }
                    return@launch
                }
                // After the not-a-quiz return above: a screen auto mode skipped is not a failed question.
                StatsStore.record(solved.source, SystemClock.elapsedRealtime() - started)
                lastHitKey = solved.memoryKey
                lastHitAt = if (solved.memoryKey != null) SystemClock.uptimeMillis() else 0L
                shownImage = solved.image ?: shownImage
                val model = if (solved.source == AnswerSource.TEXT || solved.source == AnswerSource.IMAGE)
                    runCatching { pipeline.currentClient().lastModel }.getOrNull() ?: "-" else "-"
                DiagLog.add("الجواب «${solved.text.take(60)}» من ${solved.source.name}" +
                    " ($model) خلال ${SystemClock.elapsedRealtime() - started}ms، " +
                    when {
                        !touchRequested || !solved.isAnswer -> "بدون لمس"
                        solved.touches != null -> "خطة لمس: ${solved.touches.size} ضغطة"
                        else -> "لا لمس: ${solved.touchFailure ?: "بدون سبب"}"
                    })
                // The answer only: the owner found the time and model next to it distracting.
                windows.showPanel(if (touchRequested && solved.isAnswer && solved.touches == null)
                    "${solved.text}\n${solved.touchFailure ?: "لم أجد طريقة إدخال موثوقة لهذا الشكل"}" else solved.text)
                if (solved.isAnswer && Prefs.vibrateOnAnswer(this@OverlayService)) Haptics.tick(this@OverlayService)
                // The box marks a button in the captured frame; skip it if the screen rotated meanwhile.
                val rect = solved.rect
                if (rect != null && capturedW == capture.screenW && capturedH == capture.screenH && Prefs.highlightAnswer(this@OverlayService)) {
                    windows.showHighlight(rect)
                }
                // After the answer is on screen, never before it (decision 10).
                solved.learn?.let { pipeline.startLearning(it) }
                // The game's result screen may show another option as the right one; skip it if the screen rotated.
                val watch = solved.watch
                if (watch != null && watchBase != null && capturedW == capture.screenW && capturedH == capture.screenH &&
                    Prefs.learnFromResults(this@OverlayService)
                ) {
                    startResultWatch(watch, watchBase)
                }
                if (touchRequested && permitsTouch(trigger) && capturedEpoch == touchEpoch &&
                    capturedWindow == VolumeKeyService.contextVersion() &&
                    capturedW == capture.screenW && capturedH == capture.screenH && solved.touches != null) {
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
                if (!destroyed) try { windows.showPanel("تعذّرت المحاولة؛ يمكنك طلب الإجابة من جديد") } catch (_: Throwable) { }
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
                    val printed = synchronized(pipeline.ocrLock) { pipeline.ocr?.readWords(bmp, words) }.orEmpty()
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
            val printed = synchronized(pipeline.ocrLock) { pipeline.ocr?.readWords(bmp, choices.words) } ?: return quiet("words not read")
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
        if (windows.overlayRects().any { it.left < field.right && field.left < it.right &&
                it.top < field.bottom && field.top < it.bottom }) { complete(false); return }
        scope.launch {
            // Accessibility's UP may precede the game's next rendered frame. Read again, never retype.
            repeat(NumericEntryCheck.READBACK_ATTEMPTS) { attempt ->
                if (destroyed || epoch != touchEpoch) { complete(false); return@launch }
                if (attempt > 0) delay(NumericEntryCheck.READBACK_INTERVAL_MS)
                capture.grabFrame()
                val frame = capture.lastFrame
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
                                val read = synchronized(pipeline.ocrLock) { pipeline.ocr?.readField(snapshot, localField, ink) }
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
            if (!VolumeKeyService.isEnabled(this)) windows.showPanel("فعّل خدمة إمكانية الوصول لتشغيل اللمس التلقائي")
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
                w != capture.screenW || h != capture.screenH || windows.preview != null || projection == null
            ) return blocked("تغيّر التطبيق أو وضع الشاشة؛ أُلغي اللمس")
            return try {
                capture.grabFrame()
                val frame = capture.lastFrame ?: return blocked("لم تصل لقطة شاشة جديدة؛ لم أضغط")
                val ignored = solved.ignoredRects + windows.overlayRects().map { PxRect(it.left, it.top, it.right, it.bottom) }
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
                val obstruction = windows.viewRect(windows.bubble)
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
                        w != capture.screenW || h != capture.screenH || windows.preview != null) {
                        DiagLog.add("اللمس لم يبدأ: تغيّرت الحالة (محاولة أحدث، الإعدادات، الدوران أو الصورة المرسلة)")
                        return@withTimeout
                    }
                    windows.hidePanel()
                    windows.hideHighlight()
                    val removedAt = System.nanoTime()
                    delay(100L)
                    // The checks below compare the question with the capture; a frame that still shows
                    // our answer panel over it fails them ("the question changed", "buttons not
                    // ready"). On the owner's phone the first frame without the panel came 236-272 ms
                    // after it was hidden (log, 26 Oct 2026), later than the checks' three tries.
                    var frameWaits = 0
                    while (frameWaits < CLEAN_TOUCH_POLLS) {
                        try { capture.grabFrame() } catch (_: Throwable) { }
                        val at = capture.lastFrameAtNs
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
                        windows.showPanel("${solved.text}\n$cancelReason")
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
                    if (!finished) windows.showPanel(if (entryRejected) "تعذّر التحقق من الرقم؛ لم يتم التأكيد"
                        else cancelReason)
                }
            } catch (_: TimeoutCancellationException) {
                DiagLog.add("اللمس: انتهت المهلة")
                if (!destroyed && epoch == touchEpoch) try {
                    windows.showPanel("انتهت مهلة اللمس؛ جاهز للسؤال التالي")
                } catch (_: Throwable) { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!destroyed && epoch == touchEpoch) try {
                    windows.showPanel("تعذّر إكمال اللمس؛ جاهز للسؤال التالي")
                } catch (_: Throwable) { }
            } finally {
                try {
                    if (!destroyed && epoch == touchEpoch && window == VolumeKeyService.contextVersion() &&
                        permitsTouch(trigger) && w == capture.screenW && h == capture.screenH) {
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
            FrameSignature.ignoreRect(base, ScreenCapture.SIG_SIZE, capture.screenW, capture.screenH, r.left, r.top, r.right, r.bottom)
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
            if (!autoMode) capture.grabFrame()
            val frame = capture.lastFrame ?: continue
            if (frame.width != capture.screenW || frame.height != capture.screenH) return
            val overlays = windows.overlayRects()
            val sig = try {
                capture.signature(frame, overlays + listOfNotNull(windows.viewRect(windows.highlight)) + optionRects)
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
        windows.updateBubbleStyle()
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
        if (busy || touchPending || windows.preview != null) return
        // Is the panel part of the frame being judged?
        val panelShown = windows.panelAdded
        val gotNewFrame = capture.grabFrame()
        val previous = prevTickSig
        // The virtual display only emits a frame when the screen changes, so "no new frame" means
        // the screen is exactly as at the last tick: count it as one more stable tick (reusing that
        // signature) instead of waiting for a frame that may never come on a static screen.
        // Only the very first tick has nothing to reuse and takes the kept frame.
        val sig = if (gotNewFrame || previous == null) {
            val frame = capture.lastFrame ?: return
            // Taken from the unmasked frame: the cells our windows touch are ignored anyway.
            capture.signature(frame, windows.ignoreRects())
        } else {
            previous
        }
        val retry = autoRetry.due(sig, SystemClock.uptimeMillis())
        val verdict = FrameSignature.judge(sig, previous, if (retry) null else lastSentSig,
            panelShown, CHANGE_THRESHOLD, STABLE_THRESHOLD)
        prevTickSig = sig
        when (verdict) {
            FrameSignature.Verdict.HIDE_PANEL -> if (windows.panelAdded) {
                warmUp()
                hiddenAnswer.remember(windows.panelText, windows.panelDeadline, windows.highlightBox)
                windows.hidePanel()
            }
            FrameSignature.Verdict.SEND -> {
                val toSend = capture.maskedCopy(windows::overlayRects) ?: return
                hiddenAnswer.clear()
                sendFrame(toSend, sig, fromAuto = true)
            }
            FrameSignature.Verdict.IDLE -> if (!windows.panelAdded) {
                hiddenAnswer.take(SystemClock.uptimeMillis(), HIDDEN_ANSWER_MIN_LEFT_MS)?.let {
                    windows.showPanel(it.text, it.leftMs)
                    windows.restoreHighlight(it.box)
                }
            }
            // The screen is changing: a question may be sent within a tick or two.
            FrameSignature.Verdict.WAIT -> warmUp()
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
        windows.hidePanel()
        windows.hidePreview()
        windows.hideHighlight()
        pipeline.closeReaders()
        windows.removeBubble()
        capture.release()
        shownImage = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
