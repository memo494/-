package com.clu.quickanswer

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The short haptic tick that tells the player an answer has appeared (see [Prefs.vibrateOnAnswer]). */
object Haptics {

    const val TICK_MS = 40L

    /** One short buzz. Does nothing, and never throws, on a phone without a vibrator or if the system refuses. */
    fun tick(c: Context) {
        try {
            val v = vibrator(c) ?: return
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
