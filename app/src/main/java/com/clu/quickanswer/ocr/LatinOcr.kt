package com.clu.quickanswer.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * On-device English OCR for the same known quiz layout as the Arabic reader.
 * Bundled ML Kit Latin model: no internet/model download is needed at first use.
 *
 * This deliberately cannot guess the positions of buttons in unfamiliar games.
 * Those screens continue down the original full-image model path.
 *
 * Call [read] on a worker thread only (Tasks.await must not run on Android's
 * main thread). Instances are reused across questions and closed with the service.
 */
class LatinOcr : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val colorLayout = ColorLayout()

    fun read(bitmap: Bitmap, masks: List<PxRect> = emptyList()): Question? {
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
        try {
            val pixels = IntArray(src.width * src.height)
            src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
            val layout = colorLayout.analyze(pixels, src.width, src.height) ?: return null
            // Keep CPU/network response bounded for games with very short timers.
            val text = try {
                Tasks.await(
                    recognizer.process(InputImage.fromBitmap(src, 0)),
                    650L, TimeUnit.MILLISECONDS
                )
            } catch (_: Exception) {
                return null
            }
            val segments = text.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    line.boundingBox?.let { box ->
                        LatinQuestionAssembler.Segment(
                            line.text,
                            PxRect(box.left, box.top, box.right, box.bottom)
                        )
                    }
                }
            }
            return LatinQuestionAssembler.assemble(layout, segments, masks)
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    override fun close() {
        recognizer.close()
    }
}
