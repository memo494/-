package com.clu.quickanswer

import android.graphics.Bitmap
import android.util.Base64
import com.clu.quickanswer.ocr.SentGeometry
import com.clu.quickanswer.ocr.PxRect
import java.io.ByteArrayOutputStream

/** Image preparation and prompt shared by every answer provider. */
object ScreenImage {

    /**
     * [base64] is the JPEG to send and [kept] the bitmap to show. [geometry] says which part of the
     * capture they show (the cropped rows, full width). Scaling the JPEG down does not change it:
     * boxes in the sent image are fractions of it.
     */
    class Prepared(val base64: String, val kept: Bitmap, val geometry: SentGeometry)

    const val BLACK_SCREEN =
        "الصورة سوداء: أوقف التطبيق وشغّله، واختر \"الشاشة بالكامل\" وليس تطبيقًا واحدًا"

    /** What the model is told to reply when the screen shows no question. */
    const val NO_QUESTION = "لا يوجد سؤال"

    /** True when [reply] is the model's "no question on screen" reply (ignoring spaces and a final full stop). */
    fun isNoQuestion(reply: String): Boolean = reply.trim().trimEnd('.', '!', '،', ' ') == NO_QUESTION

    /** What the model is told to reply when it cannot read the question or is not sure. */
    const val UNSURE = "غير متأكد"

    /**
     * The screenshot prompt for a quiz game screen, used when a cheap signal says the screen is a
     * game (a question was read on the device, a layout template fits, or the colour layout
     * matched). Short on purpose, and it keeps the rule the games need: answer only the central
     * question and ignore the side panels, which hold other text the model would take for a second
     * question. A choice is answered with its printed text (matched against the options that were
     * read, see [AnswerParser.resolveImageAnswer]) and a number with digits only.
     */
    const val GAME_PROMPT =
        "You see a quiz game screenshot. Answer ONLY the main question in the central card or " +
            "banner (it may have a picture, buttons or a number keypad). Ignore our floating " +
            "bubble, any black notification saying '" + NO_QUESTION + "', scores, player names, " +
            "timers, chat and the text on side panels. Reply with ONLY the answer, one line, " +
            "never numbered, no explanation. Multiple choice: the exact printed words of the " +
            "correct option as written inside its button (for example 'سبونج بوب' or 'القلب'), " +
            "never its letter, number or position. A number (including a keypad question): ONLY " +
            "the number in ASCII digits 0-9 (never ٠-٩), no words or units; a keypad takes a whole " +
            "number of at most 4 digits, so answer in the unit that fits; work it out " +
            "carefully, output only the result; for an estimate give your best single number. " +
            "If the question is visible, never reply that there is none; reply exactly '" + UNSURE +
            "' only when it cannot be read. Only if there is no quiz question at all, reply " +
            "exactly: " + NO_QUESTION

    /**
     * The screenshot prompt for any other screen: an exam or worksheet, a document, a website or an
     * app, in any language. It defines what a question is, because without that the model answers
     * chat messages, ads and dialogs, and auto mode only skips a screen on [NO_QUESTION]. One question
     * is answered alone and never numbered (a "1." in front breaks the match against the options
     * read, see [AnswerParser.resolveImageAnswer]); several get one "N. answer" line each. Priority
     * when rules clash: the printed option text, then ASCII digits, then the question's language.
     */
    const val GENERAL_PROMPT =
        "You see a phone screenshot: a quiz, an exam or worksheet, a document, a website or an " +
            "app, in any language. A question is a quiz, exam, homework or trivia question " +
            "asking for an answer, such as multiple choice, true/false, fill in the blank, a " +
            "calculation or a short-answer question. Chat messages, ads, dialogs, menus, " +
            "settings and forms are not questions. Ignore our floating bubble and any black " +
            "notification saying '" + NO_QUESTION + "'. Reply with ONLY the final answer, in the " +
            "question's language, no explanation. Rules, in this order: 1) a choice: the exact " +
            "printed text of the correct option, never its letter or position; several correct: " +
            "their texts joined by ' + '; options that are only pictures: their printed label. " +
            "2) a number or a calculation: ONLY the number in ASCII digits 0-9 (never ٠-٩), no " +
            "units; work it out carefully, output only the result. 3) fill in the blank: only " +
            "the missing words. 4) matching or ordering: pairs such as '1-ب، 2-أ'. 5) otherwise: " +
            "the shortest correct answer. One question: the answer alone, never numbered. " +
            "Several questions: one line each, 'N. answer', N as printed. If a question is " +
            "visible, give your best answer; reply exactly '" + UNSURE + "' only when it cannot " +
            "be read. If no question is visible, reply exactly: " + NO_QUESTION

    /**
     * Null if the capture is black; otherwise the JPEG (base64) to send and the bitmap to show, with
     * [Prepared.geometry] saying which rows of [bitmap] they are (the whole image: 0 until its
     * height). The long side is scaled down to [maxSide] pixels and the JPEG is saved at [quality]
     * (0-100).
     */
    fun prepare(bitmap: Bitmap, maxSide: Int = 1600, quality: Int = 85): Prepared? {
        if (isMostlyDark(bitmap)) return null
        val cropped = cropToContent(bitmap)
        val geometry = SentGeometry(bitmap.width, bitmap.height, cropped.top, cropped.bottom)
        return encode(cropped.bitmap, maxSide, quality, geometry)
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
        val bounds = focusQuizBounds(bitmap.width, bitmap.height, whiteKeypad)
        return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width, bounds.height)
    }

    /** The question's photo is pinned on the left; never crop away the evidence it asks about. */
    fun focusQuizBounds(width: Int, height: Int, whiteKeypad: Boolean): PxRect {
        require(width >= 2 && height >= 2)
        val left = (width * 0.05f).toInt().coerceIn(0, width - 2)
        val right = (width * 0.78f).toInt().coerceIn(left + 1, width)
        val top = if (whiteKeypad) 0 else (height * 0.23f).toInt()
        val bottom = (height * 0.94f).toInt().coerceIn(top + 1, height)
        return PxRect(left, top, right, bottom)
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

    /** What [cropToContent] kept: [bitmap] shows rows [top] (inclusive) until [bottom] of the source. */
    private class Cropped(val bitmap: Bitmap, val top: Int, val bottom: Int)

    /**
     * Games and videos often sit letterboxed in a black screen; sending the whole screen wastes
     * resolution on the bars, so keep only the tallest band of mostly-lit rows when there is one
     * (see [contentRows]).
     */
    private fun cropToContent(src: Bitmap): Cropped {
        val (w, h, lit) = darkMap(src)
        val (top, bottom) = contentRows(lit, w, h, src.height) ?: return Cropped(src, 0, src.height)
        return Cropped(Bitmap.createBitmap(src, 0, top, src.width, bottom - top), top, bottom)
    }

    /**
     * The pure part of [cropToContent]: which rows of the source image to keep, as (top inclusive,
     * bottom exclusive), or null to keep the whole image. [lit] is the [w] x [h] map of [darkMap]
     * (row by row) and [srcHeight] the height of the image it was sampled from.
     *
     * A row is content when more than half of its cells are lit; the band is the tallest run of
     * content rows. It is scaled to the source (srcHeight / h), widened by a margin of srcHeight / 50
     * on each side and clamped to 0..[srcHeight]. Null when there is no band, or when it is under a
     * fifth of the map (a banner, not a letterboxed screen) or over nine tenths (nothing to cut).
     */
    internal fun contentRows(lit: BooleanArray, w: Int, h: Int, srcHeight: Int): Pair<Int, Int>? {
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
        if (band <= 0 || band < h / 5 || band > h * 9 / 10) return null
        val scale = srcHeight.toFloat() / h
        val margin = srcHeight / 50
        val top = maxOf(0, (bestStart * scale).toInt() - margin)
        val bottom = minOf(srcHeight, (bestEnd * scale).toInt() + margin)
        return top to bottom
    }

    /**
     * Longest side, in pixels, of the picture of a question when it is sent with the question (see
     * [preparePicture]). A photo of a landmark or a character is recognised at this size, and the
     * request stays a few tens of kilobytes (the whole screenshot is sent at 1280 or 1600).
     */
    const val PICTURE_MAX_SIDE = 512

    /** JPEG quality of the picture of a question: the same as the Gemini screenshot. */
    const val PICTURE_QUALITY = 80

    /**
     * The picture [picture] (a crop of the screen where the game shows a question's picture), as the
     * JPEG to send and the bitmap to show, scaled to [PICTURE_MAX_SIDE] at [PICTURE_QUALITY]. Not
     * [prepare]: that one takes a dark image for a black capture and cuts dark bars off, and a night
     * scene is a picture too. [Prepared.geometry] is the whole crop.
     */
    fun preparePicture(picture: Bitmap): Prepared =
        encode(picture, PICTURE_MAX_SIDE, PICTURE_QUALITY, SentGeometry.whole(picture.width, picture.height))

    /**
     * [width] x [height] scaled down, keeping its shape, until its longer side is at most [maxSide]
     * (the longer side is exactly [maxSide] then); never enlarged, and no side under 1. Integer
     * arithmetic, so the longer side is not a pixel short through float rounding.
     */
    internal fun scaledSize(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        return maxOf(1, (width.toLong() * maxSide / longest).toInt()) to maxOf(1, (height.toLong() * maxSide / longest).toInt())
    }

    private fun encode(src: Bitmap, maxSide: Int, quality: Int, geometry: SentGeometry): Prepared {
        val (w, h) = scaledSize(src.width, src.height, maxSide)
        val bmp = if (w != src.width || h != src.height) Bitmap.createScaledBitmap(src, w, h, true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        // The caller recycles the original capture, so never keep a reference to it.
        val kept = if (bmp === src) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        return Prepared(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), kept, geometry)
    }
}
