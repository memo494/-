package com.clu.quickanswer.ocr

import android.content.Context
import android.graphics.Bitmap

/**
 * Android entry point of the on-device Arabic OCR: loads the model and dictionary from the app's
 * assets once and reads questions off bitmaps. Create one instance and keep it; building the
 * session takes a while, reading a screenshot takes a fraction of a second.
 *
 * Reading blocks while the network runs, so call [read] from a background thread.
 */
class ArabicOcr private constructor(
    private val model: OrtCtcModel,
    private val reader: ScreenReader,
) : AutoCloseable {

    /** The question on [bitmap], or null when it cannot be read reliably (see [ScreenReader]). */
    fun read(bitmap: Bitmap): Question? {
        // Hardware bitmaps cannot be read pixel by pixel.
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            return reader.read(pixels, w, h)
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    override fun close() = model.close()

    companion object {
        const val MODEL_ASSET = "ocr/inference.onnx"
        const val DICTIONARY_ASSET = "ocr/dict.txt"

        fun create(context: Context, threads: Int = 4): ArabicOcr {
            val assets = context.applicationContext.assets
            val modelBytes = assets.open(MODEL_ASSET).use { it.readBytes() }
            val dictionary = assets.open(DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val model = OrtCtcModel(modelBytes, threads)
            val recognizer = LineRecognizer(model, LineRecognizer.parseDictionary(dictionary))
            return ArabicOcr(model, ScreenReader(recognizer))
        }
    }
}
