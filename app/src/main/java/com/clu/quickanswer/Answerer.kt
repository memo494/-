package com.clu.quickanswer

import android.graphics.Bitmap

/** What one provider call produced, see [Answerer.answerText]. */
class Reply(val text: String, val kind: Kind) {
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
    /** Returns the short answer, or a short Arabic error message. Never throws. */
    fun answer(bitmap: Bitmap): String

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
