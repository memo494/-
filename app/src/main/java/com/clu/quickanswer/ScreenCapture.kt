package com.clu.quickanswer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * The screen capture of [OverlayService]: the virtual display, its image reader and the newest frame
 * it produced. Main thread only, as it was inside the service (this class starts no thread).
 */
internal class ScreenCapture(private val context: Context) {

    companion object {
        /** Side of the luminance grid [signature] makes of a frame. */
        const val SIG_SIZE = 32
    }

    private val wm: WindowManager by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    var screenW = 0
        private set
    var screenH = 0
        private set
    private var density = 0

    // The virtual display only emits frames when the screen changes, so keep the last one.
    var lastFrame: Bitmap? = null
        private set

    /** When the display produced [lastFrame] ([android.media.Image.getTimestamp], ns), or 0 if unknown. */
    var lastFrameAtNs = 0L
        private set

    // Where our own windows were painted black in a capture, by capture: the reader must know what is hidden.
    val captureMasks = java.util.WeakHashMap<Bitmap, List<Rect>>()

    /**
     * The virtual display keeps the size it was created with, so after rotating to landscape the
     * screen would be squeezed into a portrait frame. Match it to the current orientation.
     * Returns true when the screen size changed.
     */
    fun resizeCapture(): Boolean {
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
        return true
    }

    fun setupCapture(proj: MediaProjection) {
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

    /**
     * Takes the newest frame the virtual display has produced (if any) as [lastFrame]; true when a
     * new one arrived. The display only produces frames when the screen changes, so on a static
     * screen this costs next to nothing. Main thread.
     */
    fun grabFrame(): Boolean {
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
    fun captureMasked(overlayRects: () -> List<Rect>): Bitmap? {
        grabFrame()
        return maskedCopy(overlayRects)
    }

    /** A full-size copy of [lastFrame] with our overlays painted black (the caller owns and recycles it). */
    fun maskedCopy(overlayRects: () -> List<Rect>): Bitmap? {
        val bmp = lastFrame?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        val masks = overlayRects()
        for (r in masks) canvas.drawRect(r, paint)
        captureMasks[bmp] = masks
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
    fun signature(bmp: Bitmap, overlays: List<Rect>): IntArray {
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

    /** Releases the display and the reader and drops the kept frame, in this order (the service's teardown). */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        lastFrame?.recycle()
        lastFrame = null
    }
}
