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
    private val lineRecognizer: LineRecognizer,
) : AutoCloseable {

    /**
     * The question on [bitmap], or null when it cannot be read reliably (see [ScreenReader]).
     * [masks] are the rectangles painted over the capture (our own windows), in bitmap pixels.
     */
    fun read(bitmap: Bitmap, masks: List<PxRect> = emptyList()): Question? {
        // Hardware bitmaps cannot be read pixel by pixel.
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            return reader.read(pixels, w, h, masks)
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }

    /**
     * OCR the word rectangles found from actual bright text pixels. This is
     * separate from the strict legacy question detector and works when it
     * rejects a 2-column quiz. Do not auto-tap without a successful match.
     */
    fun readWords(bitmap: Bitmap, words: List<PxRect>): List<WordAnswerMatcher.Candidate> {
        if (words.size != 4) return emptyList()
        val src = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        try {
            val width = src.width
            val height = src.height
            val argb = IntArray(width * height)
            src.getPixels(argb, 0, width, 0, 0, width, height)
            return words.map { region ->
                val line = lineRecognizer.recognize(argb, width, height, region)
                WordAnswerMatcher.Candidate(line.text, region, line.confidence)
            }
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }
    /**
     * Like [read], for a caller that already holds the screenshot's pixels ([argb], row-major, [width] x
     * [height]) and ran [ColorLayout.inspect] on them ([inspected]): nothing is copied or analysed again.
     */
    fun read(argb: IntArray, width: Int, height: Int, inspected: ColorLayout.Result, masks: List<PxRect>): Question? =
        reader.read(argb, width, height, masks, inspected)

    /** A visible numeric field, read off the main thread under the same model lock as questions. */
    fun readField(bitmap: Bitmap, field: PxRect, ink: NumericFieldReader.Ink = NumericFieldReader.Ink.AUTO): RecognizedLine {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return NumericFieldReader(lineRecognizer).read(pixels, bitmap.width, bitmap.height, field, ink)
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
            return ArabicOcr(model, ScreenReader(recognizer), recognizer)
        }
    }
}
