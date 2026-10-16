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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
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

class OverlayService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "clu_overlay"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.clu.quickanswer.STOP"
        private const val AUTO_INTERVAL_MS = 1500L
        private const val SIG_SIZE = 32
        private const val CHANGE_THRESHOLD = 6.0
        private const val STABLE_THRESHOLD = 2.0
        private const val LONG_PRESS_MS = 600L
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

    private var autoMode = false
    private var autoJob: Job? = null
    @Volatile private var busy = false
    private var lastSentSig: IntArray? = null
    private var prevTickSig: IntArray? = null
    private var client: GeminiClient? = null
    // The virtual display only emits frames when the screen changes, so keep the last one.
    private var lastFrame: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
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
        client = GeminiClient(Prefs.apiKey(this), Prefs.model(this))

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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Clu")
            .setContentText("الفقاعة تعمل")
            .setOngoing(true)
            .addAction(0, "إيقاف", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeCapture()
    }

    /**
     * The virtual display keeps the size it was created with, so after rotating to landscape the
     * screen would be squeezed into a portrait frame. Match it to the current orientation.
     */
    private fun resizeCapture() {
        val vd = virtualDisplay ?: return
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return
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
        panel?.maxWidth = (screenW * 0.8f).toInt()
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

    private fun showPanel(text: String) {
        val p = panel ?: TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxWidth = (screenW * 0.8f).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#CC111111"))
            }
            setOnClickListener { hidePanel() }
            // Long-press shows exactly what was sent to Gemini, to check what it saw.
            setOnLongClickListener { showSentImage(); true }
        }.also { panel = it }
        p.setCompoundDrawables(null, null, null, null)
        p.text = text
        val existing = panelParams
        if (existing != null && p.isAttachedToWindow) {
            try {
                wm.updateViewLayout(p, existing)
            } catch (_: Exception) {
            }
            return
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        panelParams = params
        wm.addView(p, params)
    }

    private fun showSentImage() {
        val p = panel ?: return
        val bmp = client?.lastSent ?: return
        var w = (screenW * 0.75f).toInt()
        var h = w * bmp.height / bmp.width
        val maxH = (screenH * 0.5f).toInt()
        if (h > maxH) { w = w * maxH / h; h = maxH }
        val d = android.graphics.drawable.BitmapDrawable(resources, bmp).apply { setBounds(0, 0, w, h) }
        p.setCompoundDrawables(null, d, null, null)
        p.text = "الصورة المرسلة"
        panelParams?.let { try { wm.updateViewLayout(p, it) } catch (_: Exception) {} }
    }

    private fun hidePanel() {
        val p = panel ?: return
        if (p.isAttachedToWindow) {
            try {
                wm.removeView(p)
            } catch (_: Exception) {
            }
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
        viewRect(bubble)?.let { canvas.drawRect(it, paint) }
        viewRect(panel)?.let { canvas.drawRect(it, paint) }
        return bmp
    }

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

    private fun signature(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, SIG_SIZE, SIG_SIZE, true)
        val px = IntArray(SIG_SIZE * SIG_SIZE)
        small.getPixels(px, 0, SIG_SIZE, 0, 0, SIG_SIZE, SIG_SIZE)
        if (small !== bmp) small.recycle()
        return IntArray(px.size) {
            val c = px[it]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    // ---------------------------------------------------------------- actions

    private fun setBusy(b: Boolean) {
        busy = b
        updateBubbleStyle()
    }

    private fun answerNow() {
        if (busy) return
        if (panel?.isAttachedToWindow == true) {
            // Remove the old answer first so it doesn't hide part of the question.
            hidePanel()
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
        sendFrame(bmp, null)
    }

    private fun sendFrame(bmp: Bitmap, sig: IntArray?) {
        setBusy(true)
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                try {
                    client?.answer(bmp) ?: "خطأ"
                } finally {
                    bmp.recycle()
                }
            }
            if (sig != null) lastSentSig = sig
            setBusy(false)
            showPanel(answer)
        }
    }

    private fun toggleAuto() {
        autoMode = !autoMode
        updateBubbleStyle()
        Toast.makeText(this, if (autoMode) "الوضع التلقائي: مفعّل" else "الوضع التلقائي: متوقف", Toast.LENGTH_SHORT).show()
        autoJob?.cancel()
        autoJob = null
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

    private suspend fun autoTick() {
        if (busy) return
        val bmp = captureMasked(newOnly = true) ?: return
        val sig = withContext(Dispatchers.Default) { signature(bmp) }
        val prev = prevTickSig
        prevTickSig = sig
        val last = lastSentSig
        val changed = last == null || diff(sig, last) > CHANGE_THRESHOLD
        val stable = prev != null && diff(sig, prev) < STABLE_THRESHOLD
        if (changed && stable && !busy) {
            sendFrame(bmp, sig)
        } else {
            bmp.recycle()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        autoJob?.cancel()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        hidePanel()
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
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
