package com.clu.quickanswer

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Image preparation and prompt shared by every answer provider. */
object ScreenImage {

    class Prepared(val base64: String, val kept: Bitmap)

    const val BLACK_SCREEN =
        "الصورة سوداء: أوقف التطبيق وشغّله، واختر \"الشاشة بالكامل\" وليس تطبيقًا واحدًا"

    const val PROMPT =
        "You see a phone screenshot. Read the question(s) on it, including any images, " +
            "diagrams or answer options. Reply with ONLY the final answer, in the same " +
            "language as the question: for multiple choice give the option letter/number " +
            "and its text; otherwise the short answer (number, word or phrase). No " +
            "explanation, no reasoning, no preamble. For a question asking for a number or " +
            "quantity, answer with digits only: your best single number estimate (the " +
            "typical or most recent real value), even if the exact number varies. " +
            "Ignore scores, player names, " +
            "timers, buttons and number keypads; they are not part of the question. If " +
            "there are several questions, put " +
            "each answer on its own line, numbered. If no question is visible, reply " +
            "exactly: لا يوجد سؤال"

    /**
     * Null if the capture is black; otherwise the JPEG (base64) to send and the bitmap to show. The
     * long side is scaled down to [maxSide] pixels and the JPEG is saved at [quality] (0-100).
     */
    fun prepare(bitmap: Bitmap, maxSide: Int = 1600, quality: Int = 85): Prepared? {
        if (isMostlyDark(bitmap)) return null
        return encode(cropToContent(bitmap), maxSide, quality)
    }

    /** Rows/columns sampled from a small copy; "dark" = luminance below 40. */
    private fun darkMap(src: Bitmap): Triple<Int, Int, BooleanArray> {
        val w = 120
        val h = maxOf(1, src.height * w / src.width)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val lit = BooleanArray(px.size) {
            val c = px[it]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 > 40
        }
        return Triple(w, h, lit)
    }

    // A capture limited to one app (Android 14+), or a protected app, comes back black.
    private fun isMostlyDark(src: Bitmap): Boolean {
        val (_, _, lit) = darkMap(src)
        return lit.count { it } < lit.size / 50
    }

    /**
     * Games and videos often sit letterboxed in a black screen; sending the whole screen wastes
     * resolution on the bars, so keep only the tallest band of mostly-lit rows when there is one.
     */
    private fun cropToContent(src: Bitmap): Bitmap {
        val (w, h, lit) = darkMap(src)
        var bestStart = 0
        var bestEnd = 0
        var start = -1
        for (y in 0..h) {
            val content = y < h && (0 until w).count { lit[y * w + it] } > w / 2
            if (content && start < 0) start = y
            if (!content && start >= 0) {
                if (y - start > bestEnd - bestStart) { bestStart = start; bestEnd = y }
                start = -1
            }
        }
        val band = bestEnd - bestStart
        if (band < h / 5 || band > h * 9 / 10) return src
        val scale = src.height.toFloat() / h
        val margin = src.height / 50
        val top = maxOf(0, (bestStart * scale).toInt() - margin)
        val bottom = minOf(src.height, (bestEnd * scale).toInt() + margin)
        return Bitmap.createBitmap(src, 0, top, src.width, bottom - top)
    }

    private fun encode(src: Bitmap, maxSide: Int, quality: Int): Prepared {
        val longest = maxOf(src.width, src.height)
        val bmp = if (longest > maxSide) {
            val s = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(
                src,
                maxOf(1, (src.width * s).toInt()),
                maxOf(1, (src.height * s).toInt()),
                true
            )
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        // The caller recycles the original capture, so never keep a reference to it.
        val kept = if (bmp === src) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        return Prepared(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), kept)
    }
}
