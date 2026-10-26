package com.clu.quickanswer

import android.graphics.Bitmap
import com.clu.quickanswer.ocr.PxRect

/**
 * The last screen [OverlayService] read, kept in memory so the user can draw the boxes of a new game
 * on it ([CalibrationActivity]). Process-wide because the service and the activity share nothing
 * else. Never written to disk: a capture shows other players and the owner (decision 5).
 *
 * Only one copy is kept (about 10 MB for a 1080 x 2400 capture): [set] recycles the previous one.
 * [get] hands out a copy of its own, so a new capture arriving while the activity is still showing
 * the old one cannot recycle a bitmap that is being drawn.
 */
object LastCapture {

    /** A capture, with the places ([masks]) where our own windows were painted black over it. The caller owns [bitmap]. */
    class Capture(val bitmap: Bitmap, val masks: List<PxRect>)

    private var bitmap: Bitmap? = null
    private var masks: List<PxRect> = emptyList()

    /**
     * Keeps a copy of [source] (the caller keeps and recycles its own) and its [masks], replacing
     * the previous capture. If the copy cannot be made (out of memory) nothing is kept: a stale
     * capture would be taken for the last one.
     */
    @Synchronized
    fun set(source: Bitmap, masks: List<PxRect>) {
        bitmap?.recycle()
        bitmap = null
        this.masks = emptyList()
        try {
            bitmap = source.copy(Bitmap.Config.ARGB_8888, false)
            this.masks = masks.toList()
        } catch (_: Throwable) {
            // Calibration then says there is no capture; answering is not affected.
        }
    }

    /** A copy of the last capture that the caller must recycle, or null when there is none. */
    @Synchronized
    fun get(): Capture? {
        val b = bitmap ?: return null
        return try {
            b.copy(Bitmap.Config.ARGB_8888, false)?.let { Capture(it, masks) }
        } catch (_: Throwable) {
            null
        }
    }
}
