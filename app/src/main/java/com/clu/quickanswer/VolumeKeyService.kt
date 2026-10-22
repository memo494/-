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
 * answer (like tapping the bubble) and the volume does not change. When the bubble is not running,
 * or the setting is off, the key is left alone and works as usual. It reads no screen content.
 */
class VolumeKeyService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var gestureBusy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        gestureBusy = false
        super.onDestroy()
    }

    /** Sequence of short taps with gaps to preserve repeated digits. */
    private fun tapSequence(boxes: List<PxRect>, result: (Boolean) -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || gestureBusy || boxes.isEmpty() || boxes.size > 5) return false
        val builder = GestureDescription.Builder()
        boxes.forEachIndexed { i, rect ->
            val path = Path().apply { moveTo(rect.centerX, rect.centerY) }
            builder.addStroke(GestureDescription.StrokeDescription(path, i * 170L, 80L))
        }
        gestureBusy = true
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureBusy = false
                result(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureBusy = false
                result(false)
            }
        }
        return try {
            if (dispatchGesture(builder.build(), callback, main)) true
            else { gestureBusy = false; false }
        } catch (_: Exception) {
            gestureBusy = false
            false
        }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        @Volatile private var active: VolumeKeyService? = null

        fun tapAnswer(boxes: List<PxRect>, result: (Boolean) -> Unit): Boolean =
            active?.tapSequence(boxes, result) ?: false

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
