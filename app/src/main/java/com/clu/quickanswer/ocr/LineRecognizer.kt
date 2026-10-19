package com.clu.quickanswer.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The neural network behind a small seam: Android runs it on onnxruntime-android, the JVM unit
 * tests on the desktop onnxruntime jar, and a fake can stand in where no model is needed.
 */
fun interface CtcModel {
    /**
     * Runs the recogniser on one line image. [input] is the planar (CHW) float image of 3 x
     * [LineRecognizer.HEIGHT] x [width], BGR channel order, scaled to -1..1.
     */
    fun run(input: FloatArray, width: Int): CtcLogits
}

/** One recognised text line: its text in reading order and the data to average a confidence from. */
class RecognizedLine(val text: String, val probSum: Double, val charCount: Int) {
    /** Mean max-softmax probability of the characters read, 0 when there were none. */
    val confidence: Float get() = if (charCount > 0) (probSum / charCount).toFloat() else 0f
}

/**
 * Reads a single line of Arabic text out of an image region with the PP-OCRv5 Arabic recogniser:
 * crop, resize to 48 px high (width keeps the aspect ratio), run the network, greedy CTC decode,
 * then [Ctc.predReverse] to turn the model's visual order into reading order.
 *
 * @param dictionary the model's character dictionary (one entry per class between the blank and
 *   the final space class), e.g. from [parseDictionary].
 */
class LineRecognizer(private val model: CtcModel, dictionary: List<String>) {

    /** Class index -> text: the CTC blank, the dictionary, then the space the model appends. */
    private val labels: Array<String> = (listOf("") + dictionary + " ").toTypedArray()

    /** [rect] is clipped to the image; an empty rect yields an empty line. */
    fun recognize(argb: IntArray, imgWidth: Int, imgHeight: Int, rect: PxRect): RecognizedLine {
        val l = rect.left.coerceIn(0, imgWidth)
        val t = rect.top.coerceIn(0, imgHeight)
        val r = rect.right.coerceIn(0, imgWidth)
        val b = rect.bottom.coerceIn(0, imgHeight)
        if (r - l < 1 || b - t < 1) return RecognizedLine("", 0.0, 0)
        val dstW = min(MAX_WIDTH, max(MIN_WIDTH, ceil(HEIGHT.toDouble() * (r - l) / (b - t)).toInt()))
        val input = preprocess(argb, imgWidth, l, t, r - l, b - t, dstW)
        val decoded = Ctc.greedyDecode(model.run(input, dstW), labels)
        return RecognizedLine(Ctc.predReverse(decoded.text), decoded.probSum, decoded.charCount)
    }

    /**
     * Bilinear resize of the crop to [dstW] x [HEIGHT], planar BGR, (v / 127.5 - 1). Sampling
     * follows cv2.INTER_LINEAR (pixel centres, no anti-aliasing), which the model was validated with.
     */
    private fun preprocess(argb: IntArray, imgW: Int, left: Int, top: Int, sw: Int, sh: Int, dstW: Int): FloatArray {
        val xi = IntArray(dstW)
        val xf = FloatArray(dstW)
        val scaleX = sw.toDouble() / dstW
        for (dx in 0 until dstW) {
            val f = (dx + 0.5) * scaleX - 0.5
            var i = floor(f).toInt()
            var frac = (f - i).toFloat()
            if (i < 0) { i = 0; frac = 0f }
            if (i >= sw - 1) { i = sw - 1; frac = 0f }
            xi[dx] = i
            xf[dx] = frac
        }
        val plane = HEIGHT * dstW
        val out = FloatArray(3 * plane)
        val scaleY = sh.toDouble() / HEIGHT
        for (dy in 0 until HEIGHT) {
            val f = (dy + 0.5) * scaleY - 0.5
            var i = floor(f).toInt()
            var fy = (f - i).toFloat()
            if (i < 0) { i = 0; fy = 0f }
            if (i >= sh - 1) { i = sh - 1; fy = 0f }
            val row0 = (top + i) * imgW + left
            val row1 = (top + min(i + 1, sh - 1)) * imgW + left
            for (dx in 0 until dstW) {
                val x0 = xi[dx]
                val x1 = min(x0 + 1, sw - 1)
                val fx = xf[dx]
                val p00 = argb[row0 + x0]
                val p10 = argb[row0 + x1]
                val p01 = argb[row1 + x0]
                val p11 = argb[row1 + x1]
                val o = dy * dstW + dx
                out[o] = norm(lerp(p00 and 0xFF, p10 and 0xFF, p01 and 0xFF, p11 and 0xFF, fx, fy)) // B
                out[plane + o] = norm(lerp(p00 shr 8 and 0xFF, p10 shr 8 and 0xFF, p01 shr 8 and 0xFF, p11 shr 8 and 0xFF, fx, fy)) // G
                out[2 * plane + o] = norm(lerp(p00 shr 16 and 0xFF, p10 shr 16 and 0xFF, p01 shr 16 and 0xFF, p11 shr 16 and 0xFF, fx, fy)) // R
            }
        }
        return out
    }

    private fun lerp(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float): Int {
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return (top + (bottom - top) * fy + 0.5f).toInt() // uint8 result, like OpenCV
    }

    private fun norm(v: Int): Float = v / 127.5f - 1f

    companion object {
        /** Input height the model expects. */
        const val HEIGHT = 48
        private const val MIN_WIDTH = 8
        private const val MAX_WIDTH = 3200

        /** Parses the dictionary file: one character per line (a trailing newline adds no entry). */
        fun parseDictionary(text: String): List<String> {
            val lines = text.split('\n').map { it.removeSuffix("\r") }
            return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        }
    }
}
