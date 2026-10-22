package com.clu.quickanswer

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Image preparation and prompt shared by every answer provider. */
object ScreenImage {

    class Prepared(val base64: String, val kept: Bitmap)

    const val BLACK_SCREEN =
        "الصورة سوداء: أوقف التطبيق وشغّله، واختر \"الشاشة بالكامل\" وليس تطبيقًا واحدًا"

    /** What the model is told to reply when the screen shows no question. */
    const val NO_QUESTION = "لا يوجد سؤال"

    /** True when [reply] is the model's "no question on screen" reply (ignoring spaces and a final full stop). */
    fun isNoQuestion(reply: String): Boolean = reply.trim().trimEnd('.', '!', '،', ' ') == NO_QUESTION

    const val PROMPT =
        "You are reading a landscape Arabic trivia game screenshot. The game question is " +
            "printed prominently in the central parchment/card, sometimes with an image, " +
            "and may use dark buttons, translucent gray buttons, or a number keypad. " +
            "Ignore any floating AI bubble, black notification saying 'لا يوجد سؤال', " +
            "scoreboard, chat, timer, and the text on side panels. " +
            "If the central question asks for a number, give ONLY the numeric answer " +
            "using ASCII digits 0-9, preferably 1 to 4 digits when possible; " +
            "do not include a sentence, units, or the key number you tapped. " +
            "For multiple-choice questions, return ONLY the exact printed " +
            "Arabic words of the correct answer as they appear inside its " +
            "answer button (for example: 'سبونج بوب' or 'القلب'). " +
            "Do NOT return the option position, index, letter, coordinates or " +
            "an explanation. For a numeric keypad question return ONLY digits. " +
            "If the question is clearly visible, " +
            "never reply that there is no question. If the answer cannot be determined " +
            "from the image, reply exactly 'غير متأكد'. " +
            "Only if there is genuinely no central quiz question, reply exactly: " + NO_QUESTION

    /**
     * Null if the capture is black; otherwise the JPEG (base64) to send and the bitmap to show. The
     * long side is scaled down to [maxSide] pixels and the JPEG is saved at [quality] (0-100).
     */
    fun prepare(bitmap: Bitmap, maxSide: Int = 1600, quality: Int = 85): Prepared? {
        if (isMostlyDark(bitmap)) return null
        return encode(cropToContent(bitmap), maxSide, quality)
    }

    /**
     * Fallback image for a visible quiz that was misclassified as "no question".
     * Zoom in on the parchment and answer controls, excluding the scoreboard,
     * avatars, side cards, and our notification across the top.
     *
     * This crop is ONLY for model recognition. Screen coordinates and safety
     * fingerprints always use the uncropped original capture.
     */
    fun focusQuiz(bitmap: Bitmap, whiteKeypad: Boolean): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val left = (width * 0.19f).toInt().coerceIn(0, width - 2)
        val right = (width * 0.78f).toInt().coerceIn(left + 1, width)
        val top = if (whiteKeypad) 0 else (height * 0.23f).toInt()
        val bottom = (height * 0.94f).toInt().coerceIn(top + 1, height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
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
