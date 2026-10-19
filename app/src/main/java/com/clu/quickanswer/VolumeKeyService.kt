package com.clu.quickanswer

import android.accessibilityservice.AccessibilityService
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
