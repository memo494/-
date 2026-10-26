package com.clu.quickanswer.ocr

/**
 * A number pad the user taught Clu by drawing its keys ([LayoutTemplate.keys]), for games whose pad
 * no built-in adapter ([GameVisualLayout], [NumericKeypad]) recognises. The user is the authority on
 * WHERE the keys are; what is still checked on every frame is that they are THERE: each key's frame
 * looks as it did when drawn ([TemplateReader.matches] on [LayoutTemplate.keySignatures]), so a
 * dialog, an ad or another screen of the game never receives the taps.
 *
 * Pure Kotlin, no android.*.
 */
object CalibratedKeypad {

    /** The pad of template [templateId] on one capture, in its pixels. */
    data class Keys(
        val templateId: String,
        val digits: Map<Char, PxRect>,
        val submit: PxRect?,
        val field: PxRect,
        val signatures: List<RegionSignature>,
        val fieldEmpty: String,
    ) {
        /** Keys 0..9 then submit: the order of [signatures]. */
        val all: List<PxRect> get() = ('0'..'9').map { digits.getValue(it) } + listOfNotNull(submit)
    }

    /**
     * The calibrated pad of [t] on this capture, or null when [t] has none, the screen is not the one
     * it was drawn on ([TemplateReader.fits]), or any key or the submit button looks different now.
     */
    fun locate(t: LayoutTemplate, argb: IntArray, width: Int, height: Int): Keys? {
        if (!t.isValid || width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return null
        val keys = t.keys ?: return null
        val field = t.numericField ?: return null
        val sigs = t.keySignatures ?: return null
        val fieldEmpty = t.fieldEmpty ?: return null
        if (!TemplateReader.fits(t, argb, width, height)) return null
        val px = t.keyRegions.map { it.toPx(width, height) }
        val found = Keys(
            templateId = t.id,
            digits = ('0'..'9').zip(px.take(10)).toMap(),
            submit = px.getOrNull(10),
            field = field.toPx(width, height),
            signatures = sigs,
            fieldEmpty = fieldEmpty,
        )
        return found.takeIf { stillVisible(it, argb, width, height, pressed = null) }
    }

    /** The first trusted template among [templates] whose calibrated pad is on this capture. */
    fun find(templates: List<LayoutTemplate>, argb: IntArray, width: Int, height: Int): Keys? =
        templates.asSequence()
            .filter { it.state == TemplateState.TRUSTED && it.keys != null }
            .firstNotNullOfOrNull { locate(it, argb, width, height) }

    /**
     * Whether every key (and, with [checkSubmit], the submit button) still looks as drawn. The key
     * just [pressed] is left out: games highlight a key for a moment after a tap, and that is not the
     * pad going away. The submit button is compared only before typing starts: many games light or
     * dim it once a digit is in the field, and that must not stop the entry halfway (the typed
     * number is read back before it is pressed anyway).
     */
    fun stillVisible(
        keys: Keys, argb: IntArray, width: Int, height: Int, pressed: PxRect?, checkSubmit: Boolean = true,
    ): Boolean {
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return false
        val all = keys.all
        if (all.size != keys.signatures.size) return false
        val stored = ArrayList<RegionSignature>()
        val now = ArrayList<RegionSignature>()
        for ((i, r) in all.withIndex()) {
            if (r.left < 0 || r.top < 0 || r.right > width || r.bottom > height) return false
            if (r == pressed) continue
            if (i >= 10 && !checkSubmit) continue
            stored.add(keys.signatures[i])
            now.add(TemplateReader.signature(argb, width, height, r))
        }
        return TemplateReader.matches(stored, now)
    }

    /** Whether the field is still as empty as when the keys were drawn ([FieldStamp]). */
    fun fieldState(keys: Keys, argb: IntArray, width: Int, height: Int): FieldStamp.State =
        FieldStamp.state(keys.fieldEmpty, argb, width, height, keys.field)
}
