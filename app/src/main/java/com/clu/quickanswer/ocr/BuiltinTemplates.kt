package com.clu.quickanswer.ocr

/**
 * The layout templates the app ships with (assets/templates/builtin.json), so the games we already
 * know are read on the device from the first question instead of after learning them. They are
 * built from the owner's screenshots by the local RealScreenCalibration test and start TRUSTED;
 * like any template they go back to CANDIDATE after [LayoutTemplates.DEMOTE_AFTER] failures.
 *
 * They are merged into the store (see [LayoutTemplates.merge]) once per [VERSION]: a learned
 * template for the same layout wins, and one the user cleared or that failed out is not forced
 * back until a new version of the file ships.
 */
object BuiltinTemplates {
    const val ASSET = "templates/builtin.json"

    /** Raise when builtin.json changes, so phones that already merged it merge the new one. */
    const val VERSION = 1

    /** [LayoutTemplate.source] of a built-in template. */
    const val SOURCE = "builtin"
}
