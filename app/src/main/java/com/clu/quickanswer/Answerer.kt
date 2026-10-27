package com.clu.quickanswer

import android.graphics.Bitmap
import android.os.SystemClock
import com.clu.quickanswer.ocr.LayoutPrompt
import com.clu.quickanswer.ocr.SentGeometry

/** What one provider call produced, see [Answerer.answerText]. */
class Reply(val text: String, val kind: Kind) {
    /**
     * True when [text] is a real answer to show as one: not an error, not an empty or refused reply,
     * and not the model's "no question on screen". Decides whether showing it earns a haptic tick.
     */
    val isAnswer: Boolean
        get() = kind == Kind.ANSWER && text.isNotBlank() && !ScreenImage.isNoQuestion(text)

    enum class Kind {
        /** The model's answer. */
        ANSWER,

        /** The provider answered but with nothing usable (empty or refused): the image path may still work. */
        EMPTY,

        /** [text] is a short Arabic error (quota, key, region, connection) that would hit the image path too. */
        ERROR
    }

    companion object {
        fun answer(text: String) = Reply(text, Kind.ANSWER)
        fun empty(message: String) = Reply(message, Kind.EMPTY)
        fun error(message: String) = Reply(message, Kind.ERROR)
    }
}

/**
 * A model's reply to [LayoutPrompt.TEXT] (see [Answerer.layout]). [text] is the raw reply, not parsed
 * yet. [geometry] says where the image the model saw came from in the capture (which rows were kept,
 * see [ScreenImage.Prepared.geometry]), which is what maps the boxes in [text] back to the capture.
 */
class LayoutAnswer(val text: String, val geometry: SentGeometry)

/** Which screenshot prompt to send: the short one for quiz games, or the general one (see [ScreenImage]). */
enum class ImagePrompt(val text: String) {
    GAME(ScreenImage.GAME_PROMPT),
    GENERAL(ScreenImage.GENERAL_PROMPT)
}

/**
 * A fresh answer budget starting now, on the device's monotonic clock. The default of every
 * [Answerer] call that has no deadline from its caller; [OverlayService] makes one per question and
 * passes it through so the reading, the text request and the image request share one budget.
 */
fun newAnswerDeadline(): AnswerDeadline = AnswerDeadline.start(SystemClock::elapsedRealtime)

interface Answerer {
    /**
     * Asks about the whole screenshot with [prompt]. [Reply.text] is the short answer, or a short
     * Arabic error message ([Reply.kind] tells which). Never throws. Gives up at [deadline]: no
     * request starts after it and none waits past it, and the reply is then the error
     * [AnswerDeadline.TIMEOUT_TEXT] (an [Reply.Kind.ERROR], so no other request is tried).
     */
    fun answer(
        bitmap: Bitmap,
        prompt: ImagePrompt = ImagePrompt.GENERAL,
        deadline: AnswerDeadline = newAnswerDeadline(),
    ): Reply

    /**
     * Asks a question that was already read off the screen (see [QuestionPrompt]); no image is
     * sent. Same models, quota handling, fallbacks and [deadline] as [answer]. Never throws.
     */
    fun answerText(prompt: String, deadline: AnswerDeadline = newAnswerDeadline()): Reply

    /**
     * Asks a question that was already read off the screen and shows a picture: [prompt] (see
     * [QuestionPrompt.buildPicture]) and the [picture] (a crop of the screen, scaled and encoded here,
     * see [ScreenImage.preparePicture]) in ONE request. Same models, quota handling, fallbacks and
     * error mapping and [deadline] as [answerText]; [lastSent] becomes the picture as sent. Never throws.
     *
     * Default: not supported, an [Reply.Kind.EMPTY] reply, so the caller falls back to the screenshot.
     */
    fun answerPicture(prompt: String, picture: Bitmap, deadline: AnswerDeadline = newAnswerDeadline()): Reply =
        Reply.empty("")

    /**
     * Asks for the screen's layout: the screenshot together with [LayoutPrompt.TEXT], so a template
     * can be learned from the reply. Background work only: ONE request to the user's own model, with
     * no fallbacks, no retries and no fan-out, so it never competes with the answers for quota or
     * time. It must not change [lastSent] or [lastModel].
     *
     * Null on any failure: a black screen, a model the quota tracker knows is out of quota, no
     * network, a non-2xx status, or an empty reply. Never throws. Blocks, so call it off the main
     * thread. Default: not supported (null).
     */
    fun layout(bitmap: Bitmap): LayoutAnswer? = null

    /** The image last sent (after cropping/scaling), so the user can check it. */
    val lastSent: Bitmap?

    /** The model that gave the last answer (shown next to it, to see when a slower fallback answered). */
    val lastModel: String? get() = null

    /**
     * Opens a connection ahead of the first question so the answer does not pay for the TLS
     * handshake. May block on the network, so call it off the main thread. Default: nothing to do.
     */
    fun warmUp() {}
}
