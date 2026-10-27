package com.clu.quickanswer

import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip

/**
 * The chip under the app name on the settings screen: green "يعمل" while the bubble runs, neutral
 * "متوقف" otherwise. Colors are the palette's, so they follow light and dark.
 */
fun Chip.showStatus(running: Boolean) {
    text = if (running) "يعمل" else "متوقف"
    val background = if (running) R.color.clu_success_container else R.color.clu_surface_variant
    val label = if (running) R.color.clu_on_success_container else R.color.clu_on_surface_variant
    val dot = if (running) R.color.clu_success else R.color.clu_outline
    chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, background))
    setTextColor(ContextCompat.getColor(context, label))
    chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, dot))
}
