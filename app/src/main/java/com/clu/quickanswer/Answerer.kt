package com.clu.quickanswer

import android.graphics.Bitmap

interface Answerer {
    /** Returns the short answer, or a short Arabic error message. Never throws. */
    fun answer(bitmap: Bitmap): String

    /** The image last sent (after cropping/scaling), so the user can check it. */
    val lastSent: Bitmap?
}
