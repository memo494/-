package com.clu.quickanswer.ocr

/** Pixel rectangle; [right] and [bottom] are exclusive. No android.graphics so it works on a plain JVM. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Where the parts of a quiz screen are, in input-image pixels. Every field is geometry only; no
 * text has been read yet.
 *
 * @property banner the cream question banner.
 * @property buttons the white answer buttons below the banner, top to bottom. Empty on a numeric
 *   question.
 * @property numericField the number-entry box when the question wants a number typed in (it
 *   overlaps the bottom edge of the banner), otherwise null.
 * @property questionLines tight boxes of the question's text lines, top to bottom.
 * @property optionLines tight boxes of the text lines inside each of [buttons] (same order).
 */
class ScreenLayout(
    val banner: PxRect,
    val buttons: List<PxRect>,
    val numericField: PxRect?,
    val questionLines: List<PxRect>,
    val optionLines: List<List<PxRect>>,
    val digitKeys: Map<Char, PxRect> = emptyMap(),
    val numericSubmit: PxRect? = null,
) {
    val numeric: Boolean get() = numericField != null
}

/**
 * A multiple-choice or numeric question read off the screen.
 *
 * @property questionText the question, with its lines joined by a space.
 * @property options the answer texts, top to bottom. Empty when [numeric].
 * @property optionRects the whole button of each option in input-image pixel coordinates (the area
 *   to tap), same order and size as [options].
 * @property numeric true when the answer is typed into a number field.
 * @property meanConfidence mean of the per-character max softmax probability over every character
 *   that was read (question and options), 0..1.
 */
data class Question(
    val questionText: String,
    val options: List<String>,
    val optionRects: List<PxRect>,
    val numeric: Boolean,
    val meanConfidence: Float,
    val digitKeys: Map<Char, PxRect> = emptyMap(),
    val bannerRect: PxRect? = null,
    val numericSubmit: PxRect? = null,
    val optionTextRects: List<PxRect> = emptyList(),
    val numericField: PxRect? = null,
)
