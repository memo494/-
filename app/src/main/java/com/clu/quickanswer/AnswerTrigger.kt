package com.clu.quickanswer

/** A deliberate volume press can enter an answer even when continuous scanning is off. */
internal enum class AnswerTrigger {
    BUBBLE, VOLUME_DOWN, AUTOMATIC;

    fun permitsTouch(autoMode: Boolean, autoTouchEnabled: Boolean, volumeEnabled: Boolean): Boolean =
        when (this) {
            BUBBLE -> autoTouchEnabled
            VOLUME_DOWN -> volumeEnabled
            AUTOMATIC -> autoMode && autoTouchEnabled
        }
}
