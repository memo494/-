package com.clu.quickanswer.ocr

/**
 * Learning a [LayoutTemplate] from a cloud model's description of a screenshot ("step 2"): the
 * model is shown the screenshot with [LayoutPrompt.TEXT] and replies with the boxes and texts of the
 * question and its options (or number field). [LayoutReplyParser] reads that reply,
 * [TemplateLearner] turns it into a CANDIDATE template, and the template is kept only when reading
 * the same screen with it on the device gives the texts the model saw ([TemplateLearner.confirms]).
 *
 * Pure Kotlin, no android.*: everything here is unit-tested on the JVM.
 */

/** What the model is asked when the screen's layout is unknown. Its reply is read by [LayoutReplyParser]. */
object LayoutPrompt {
    const val TEXT =
        "You see a phone screenshot of a quiz game. Locate the quiz question and where it is answered. " +
            "Reply with ONLY a JSON object, no markdown, in this shape:\n" +
            "{\"kind\":\"choices\" or \"numeric\" or \"none\", \"language\":\"ar\" or \"en\", " +
            "\"question\":{\"box\":[ymin,xmin,ymax,xmax],\"text\":\"...\"}, " +
            "\"options\":[{\"box\":[ymin,xmin,ymax,xmax],\"text\":\"...\"}], " +
            "\"numeric_field\":{\"box\":[ymin,xmin,ymax,xmax]}}\n" +
            "Boxes are integers from 0 to 1000 relative to the image size, y before x, (0,0) at the top left.\n" +
            "question.box covers the whole question text, every line of it, and nothing else: not the " +
            "category label, the timer, the scores, the players or a picture.\n" +
            "kind \"choices\": options lists every answer button in reading order; each box covers the " +
            "whole button (its edges, not only its text) and text is exactly what is written on it.\n" +
            "kind \"numeric\": the answer is typed as a number; options is empty and numeric_field.box " +
            "covers the box where the typed number appears.\n" +
            "Copy every text exactly as written, in its own language and spelling. " +
            "If there is no quiz question on the screen, reply {\"kind\":\"none\"}."
}

/** A box in 0..1000 coordinates of the image that was sent: [yMin, xMin, yMax, xMax], the order Gemini uses. */
data class Box1000(val yMin: Int, val xMin: Int, val yMax: Int, val xMax: Int) {
    val isValid: Boolean get() = yMin in 0..1000 && xMin in 0..1000 && yMax in 0..1000 && xMax in 0..1000 &&
        yMin < yMax && xMin < xMax
}

enum class LayoutKind { CHOICES, NUMERIC }

data class LayoutOption(val box: Box1000, val text: String)

/** A parsed, validated reply to [LayoutPrompt.TEXT]. [options] is empty for [LayoutKind.NUMERIC]. */
data class LayoutReply(
    val kind: LayoutKind,
    /** "ar", "en" or null when the model gave something else. */
    val language: String?,
    val question: Box1000,
    val questionText: String,
    val options: List<LayoutOption>,
    /** Set for [LayoutKind.NUMERIC], null otherwise. */
    val numericField: Box1000?,
)

/**
 * Where the sent image came from: rows [cropTop] until [cropBottom] of a [captureWidth] x
 * [captureHeight] capture, full width, scaled uniformly (that is what ScreenImage.prepare does: it
 * may cut letterbox rows, never columns). Maps the model's boxes back to the capture.
 */
data class SentGeometry(val captureWidth: Int, val captureHeight: Int, val cropTop: Int, val cropBottom: Int) {

    /** [b] (in the sent image) as fractions of the whole capture. */
    fun toRelRect(b: Box1000): RelRect {
        val cropH = (cropBottom - cropTop).toFloat()
        return RelRect(
            left = b.xMin / 1000f,
            top = (cropTop + b.yMin / 1000f * cropH) / captureHeight,
            right = b.xMax / 1000f,
            bottom = (cropTop + b.yMax / 1000f * cropH) / captureHeight,
        )
    }

    companion object {
        /** The whole capture was sent. */
        fun whole(width: Int, height: Int) = SentGeometry(width, height, 0, height)
    }
}

object LayoutReplyParser {

    /**
     * The reply in [text], or null when it is not a usable layout. Tolerant of what models wrap
     * around JSON: a ```json fence, or words before the first '{' / after the last '}'.
     *
     * Null when: no JSON object; "kind" is "none", missing or unknown; the question has no valid
     * box or blank text; "choices" with fewer than 2 or more than 8 options, or an option with no
     * valid box or blank text; "numeric" without a valid numeric_field box.
     *
     * A box is an array of 4 numbers [ymin, xmin, ymax, xmax]; numbers may be decimals (rounded to
     * the nearest integer) and are clamped to 0..1000 before validation (models overshoot by a few
     * units at the image edge); then ymin < ymax and xmin < xmax must hold.
     * "language" is lower-cased; "ar…" becomes "ar", "en…" becomes "en", anything else null.
     * Texts are trimmed. For "numeric", any options in the reply are ignored.
     */
    fun parse(text: String): LayoutReply? = TODO("issue: layout learning, part 1")
}

object TemplateLearner {
    /** Added on each side of the model's question box (fraction of the capture): models draw it tight. */
    const val QUESTION_MARGIN_X = 0.01f
    const val QUESTION_MARGIN_Y = 0.01f

    /** Smallest region side (fraction of the capture). A smaller box is a model mistake. */
    const val MIN_REGION_SIDE = 0.02f

    /** Two regions overlapping by more than this share of the smaller one's area are a model mistake. */
    const val MAX_OVERLAP = 0.10f

    const val MIN_QUESTION_SIMILARITY = 0.8
    const val MIN_OPTION_SIMILARITY = 0.7

    /**
     * [reply]'s options in the order the on-device readers list them: rows from top to bottom, and
     * inside a row right to left for Arabic (or an unknown language) and left to right for English.
     * Two options are in one row when their vertical ranges overlap by at least half of the shorter
     * one's height. Empty for a numeric reply.
     */
    fun ordered(reply: LayoutReply): List<LayoutOption> = TODO("issue: layout learning, part 1")

    /** NUMERIC for a numeric reply; GRID when some row of [ordered] holds two or more options; else VERTICAL. */
    fun arrangementOf(reply: LayoutReply): Arrangement = TODO("issue: layout learning, part 1")

    /**
     * A CANDIDATE template for the capture [argb] ([width] x [height], with our own windows at
     * [masks]) from [reply], or null when the reply cannot be a sane layout.
     *
     * - Rectangles: [SentGeometry.toRelRect]; the question grows by [QUESTION_MARGIN_X] /
     *   [QUESTION_MARGIN_Y] on every side, clamped to 0..1; options in [ordered] order.
     * - Null when [width]/[height] differ from [geometry]'s capture size; a region is not
     *   [RelRect.isValid] or has a side under [MIN_REGION_SIDE]; two options, or an option and the
     *   question, or the number field and the question overlap by more than [MAX_OVERLAP] of the
     *   smaller area; or the result is not [LayoutTemplate.isValid].
     * - fingerprint = [ScreenFingerprint.of] (argb, width, height, masks); signatures =
     *   [TemplateReader.signature] of each region in pixels, in [LayoutTemplate.regions] order.
     * - id = [id]; packageName null; arrangement [arrangementOf]; language = reply.language;
     *   state CANDIDATE; hits 0; misses 0; source "model"; createdAt = lastUsedAt = [now].
     */
    fun build(
        reply: LayoutReply,
        geometry: SentGeometry,
        argb: IntArray,
        width: Int,
        height: Int,
        masks: List<PxRect>,
        id: String,
        now: Long,
    ): LayoutTemplate? = TODO("issue: layout learning, part 1")

    /**
     * Whether [read] (what the device read with the new template) is what the model saw: both
     * numeric or both multiple choice; question [ArabicText.similarity] >= [MIN_QUESTION_SIMILARITY];
     * for choices the same number of options and option i similar to [ordered] option i by at least
     * [MIN_OPTION_SIMILARITY].
     */
    fun confirms(read: Question, reply: LayoutReply): Boolean = TODO("issue: layout learning, part 1")
}
