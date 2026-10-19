package com.clu.quickanswer

import android.graphics.Bitmap

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

interface Answerer {
    /**
     * Asks about the whole screenshot. [Reply.text] is the short answer, or a short Arabic error
     * message ([Reply.kind] tells which). Never throws.
     */
    fun answer(bitmap: Bitmap): Reply

    /**
     * Asks a question that was already read off the screen (see [QuestionPrompt]); no image is
     * sent. Same models, quota handling and fallbacks as [answer]. Never throws.
     */
    fun answerText(prompt: String): Reply

    /** The image last sent (after cropping/scaling), so the user can check it. */
    val lastSent: Bitmap?

    /**
     * Opens a connection ahead of the first question so the answer does not pay for the TLS
     * handshake. May block on the network, so call it off the main thread. Default: nothing to do.
     */
    fun warmUp() {}
}
