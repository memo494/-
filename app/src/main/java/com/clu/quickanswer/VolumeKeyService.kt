package com.clu.quickanswer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.clu.quickanswer.ocr.PxRect
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service: while the Clu bubble is running, pressing VOLUME DOWN asks for an
 * answer and enters it on the verified game controls; the volume does not change. When the bubble is not running,
 * or the setting is off, the key is left alone and works as usual. It reads no screen content.
 */
class VolumeKeyService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: ForegroundWindowGuard
    private val taps = TapSequence(
        dispatch = { rect, duration, complete ->
            val path = Path().apply { moveTo(rect.centerX, rect.centerY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = complete(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = complete(false)
            }, main)
        },
        schedule = { delay, action -> check(main.postDelayed({ action() }, delay)) },
        pressDurationMs = TapSequence.GAME_PRESS_MS,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        active?.takeIf { it !== this }?.taps?.cancel()
        windows = ForegroundWindowGuard(packageName)
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        taps.cancel()
        super.onDestroy()
    }

    private fun tapSequence(
        boxes: List<PxRect>, submitLast: Boolean, canContinue: () -> Boolean,
        result: (Boolean) -> Unit,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || active !== this) return false
        return taps.start(boxes, submitLast, canContinue, result)
    }

    private fun cancelSequence() {
        if (Looper.myLooper() == Looper.getMainLooper()) taps.cancel()
        else main.post { taps.cancel() }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val trigger = OverlayService.volumeTrigger ?: return false
        if (!Prefs.volumeKey(this)) return false
        // One answer per press: act on the first down event, swallow repeats and the up event so the
        // volume stays where it is.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) main.post { trigger() }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // An in-game window/focus event does not imply a different target app. Per-tap pixels
        // still verify the keypad/options; only a package change invalidates the app context here.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!::windows.isInitialized || windows.onWindow(event.packageName?.toString())) taps.cancel()
        }
    }

    override fun onInterrupt() {
        taps.cancel()
        if (::windows.isInitialized) windows.reset()
    }

    companion object {
        @Volatile private var active: VolumeKeyService? = null

        fun tapAnswer(
            boxes: List<PxRect>,
            submitLast: Boolean = false,
            canContinue: () -> Boolean = { true },
            result: (Boolean) -> Unit,
        ): Boolean = active?.tapSequence(boxes, submitLast, canContinue, result) ?: false

        fun cancelAnswer() { active?.cancelSequence() }

        /** Whether the user has turned this service on in the system's Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, VolumeKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
