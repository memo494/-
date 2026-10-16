package com.clu.quickanswer

import android.graphics.Bitmap

interface Answerer {
    /** Returns the short answer, or a short Arabic error message. Never throws. */
    fun answer(bitmap: Bitmap): String

    /** The image last sent (after cropping/scaling), so the user can check it. */
    val lastSent: Bitmap?

    /**
     * Opens a connection ahead of the first question so the answer does not pay for the TLS
     * handshake. May block on the network, so call it off the main thread. Default: nothing to do.
     */
    fun warmUp() {}
}
