package com.clu.quickanswer

import android.graphics.Bitmap
import com.clu.quickanswer.ocr.PxRect

/**
 * The last screen [OverlayService] read, kept in memory so the user can draw the boxes of a new game
 * on it ([CalibrationActivity]). Process-wide because the service and the activity share nothing
 * else. Never written to disk by the app: a capture shows other players and the owner (decision 5).
 * Only the owner saves it, by hand, from the settings ("حفظ آخر لقطة"), to send a failing screen.
 *
 * Only one capture is kept (about 10 MB for a 1080 x 2400 capture): [keep] recycles the previous one.
 * [get] hands out a copy of its own, so a new capture arriving while the activity is still showing
 * the old one cannot recycle a bitmap that is being drawn.
 */
object LastCapture {

    /** A capture, with the places ([masks]) where our own windows were painted black over it. The caller owns [bitmap]. */
    class Capture(val bitmap: Bitmap, val masks: List<PxRect>)

    private var bitmap: Bitmap? = null
    private var masks: List<PxRect> = emptyList()

    /**
     * Keeps [source] itself and its [masks], replacing (and recycling) the previous capture. The
     * caller hands [source] over: it must not use or recycle it afterwards. No copy is made: the
     * question's capture is not needed by anyone else once it is answered, and copying it cost a
     * full-screen bitmap (about 10 MB) per question.
     */
    @Synchronized
    fun keep(source: Bitmap, masks: List<PxRect>) {
        bitmap?.takeIf { it !== source }?.recycle()
        bitmap = source
        this.masks = masks.toList()
    }

    /** Whether a capture is kept (cheap: nothing is copied). */
    @Synchronized
    fun has(): Boolean = bitmap != null

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
