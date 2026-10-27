package com.clu.quickanswer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.LayoutDirection
import com.clu.quickanswer.OverlayLook
import org.junit.Rule
import org.junit.Test

/**
 * The bubble in its three states and the answer panel, drawn over a light and a dark game background,
 * so their look can be checked without a device (see [SettingsScreenshotTest] for how to record). Sizes
 * match the real windows: a 56dp bubble, the panel's 18sp bold text, its padding and its 0.8 alpha.
 */
class OverlayLookScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            layoutDirection = LayoutDirection.RTL,
            locale = "ar",
            screenHeight = 900
        ),
        theme = "Theme.Clu",
        supportsRtl = true
    )

    @Test
    fun overLightGame() = draw(0xFFE8D6B4.toInt())

    @Test
    fun overDarkGame() = draw(0xFF1B2130.toInt())

    private fun draw(game: Int) {
        val context = paparazzi.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(game)
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        val bubbles = LinearLayout(context).apply { gravity = Gravity.CENTER }
        for (state in OverlayLook.BubbleState.values()) {
            val bubble = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                OverlayLook.styleBubble(this, state, density)
            }
            bubbles.addView(
                bubble,
                LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    marginStart =
                        dp(12)
                    marginEnd = dp(12)
                }
            )
        }
        root.addView(bubbles)
        for (answer in listOf("⚡ سورة طه", "انتهى الوقت (10 ث)، جرّب مرة ثانية")) {
            val panel = TextView(context).apply {
                text = answer
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                ellipsize = TextUtils.TruncateAt.END
                background = OverlayLook.panelBackground(density)
                alpha = 0.8f
            }
            root.addView(
                panel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24) }
            )
        }
        paparazzi.snapshot(root)
    }
}
