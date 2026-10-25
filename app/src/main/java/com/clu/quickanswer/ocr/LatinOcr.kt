package com.clu.quickanswer.ocr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * On-device English OCR for the same known quiz layout as the Arabic reader.
 * Bundled ML Kit Latin model: no internet/model download is needed at first use.
 *
 * This deliberately cannot guess the positions of buttons in unfamiliar games: it only reads the
 * [ScreenLayout] the caller found with [ColorLayout] (the layout is analysed once per screenshot,
 * by the caller, and shared with the Arabic reader). Only the part of the screen that holds the
 * banner and the answer buttons goes to ML Kit. Other screens continue down the original
 * full-image model path.
 *
 * Call [read] and [warmUp] on a worker thread only (Tasks.await must not run on Android's
 * main thread). Instances are reused across questions and closed with the service.
 */
class LatinOcr : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Runs the recognizer once on a small blank bitmap so the model is loaded before the first real
     * question (which has only [READ_TIMEOUT_MS] to answer). Never throws, whatever goes wrong.
     */
    fun warmUp() {
        var blank: Bitmap? = null
        try {
            blank = Bitmap.createBitmap(WARM_UP_WIDTH, WARM_UP_HEIGHT, Bitmap.Config.ARGB_8888)
            blank.eraseColor(Color.WHITE)
            Tasks.await(
                recognizer.process(InputImage.fromBitmap(blank, 0)),
                WARM_UP_TIMEOUT_MS, TimeUnit.MILLISECONDS
            )
        } catch (_: Throwable) {
        } finally {
            blank?.recycle()
        }
    }

    /**
     * The question on [bitmap] given its already found [layout], or null when it cannot be read
     * reliably (see [LatinQuestionAssembler]). [masks] are the rectangles painted over the capture.
     * [bitmap] must be readable by ML Kit (not a hardware bitmap).
     */
    fun read(bitmap: Bitmap, layout: ScreenLayout, masks: List<PxRect> = emptyList()): Question? {
        val area = readArea(layout, bitmap.width, bitmap.height) ?: return null
        var crop: Bitmap? = null
        try {
            crop = Bitmap.createBitmap(bitmap, area.left, area.top, area.width, area.height)
            // Keep CPU/network response bounded for games with very short timers.
            val text = try {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(crop, 0)),
                    READ_TIMEOUT_MS, TimeUnit.MILLISECONDS
                )
            } catch (_: Exception) {
                return null
            }
            val segments = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { box ->
                        LatinQuestionAssembler.Segment(
                            line.text,
                            PxRect(
                                box.left + area.left, box.top + area.top,
                                box.right + area.left, box.bottom + area.top
                            ),
                            line.confidence
                        )
                    }
                }
            }
            return LatinQuestionAssembler.assemble(layout, segments, masks)
        } finally {
            // createBitmap returns the source itself when the area is the whole (immutable) bitmap.
            if (crop !== bitmap) crop?.recycle()
        }
    }

    /**
     * Every word ML Kit reads on the whole [bitmap], with its box: what [KeypadFinder] looks for
     * key digits in. For the calibration screen, not the answer flow: it reads the full capture and
     * may take a second. Empty on any failure. Worker thread only.
     */
    fun glyphs(bitmap: Bitmap): List<KeypadFinder.Glyph> = try {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), GLYPHS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { e ->
            e.boundingBox?.let { b -> KeypadFinder.Glyph(e.text, PxRect(b.left, b.top, b.right, b.bottom)) }
        }
    } catch (_: Throwable) {
        emptyList()
    }

    /** Banner, buttons and number field of [layout] plus [CROP_MARGIN] px, inside a [width] x [height] bitmap; null if empty. */
    private fun readArea(layout: ScreenLayout, width: Int, height: Int): PxRect? {
        var left = layout.banner.left
        var top = layout.banner.top
        var right = layout.banner.right
        var bottom = layout.banner.bottom
        for (r in layout.buttons + listOfNotNull(layout.numericField)) {
            left = minOf(left, r.left); top = minOf(top, r.top)
            right = maxOf(right, r.right); bottom = maxOf(bottom, r.bottom)
        }
        val area = PxRect(
            maxOf(0, left - CROP_MARGIN), maxOf(0, top - CROP_MARGIN),
            minOf(width, right + CROP_MARGIN), minOf(height, bottom + CROP_MARGIN)
        )
        return if (area.width > 0 && area.height > 0) area else null
    }

    override fun close() {
        recognizer.close()
    }

    private companion object {
        const val CROP_MARGIN = 8
        const val READ_TIMEOUT_MS = 650L
        const val GLYPHS_TIMEOUT_MS = 4_000L
        const val WARM_UP_TIMEOUT_MS = 5_000L
        const val WARM_UP_WIDTH = 320
        const val WARM_UP_HEIGHT = 64
    }
}
