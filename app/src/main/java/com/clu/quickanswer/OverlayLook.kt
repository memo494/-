package com.clu.quickanswer

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/**
 * The look of Clu's windows over a game: the bubble in its three states and the answer panel, in the
 * app's identity (the launcher icon's indigo-violet, the amber spark). The same in light and dark mode:
 * what is behind them is the game, not the theme. Only colours and shapes live here; sizes, padding and
 * the panel's window alpha stay in [OverlayWindows] (the masks and the panel's placement depend on them).
 */
internal object OverlayLook {

    enum class BubbleState { IDLE, BUSY, AUTO }

    /** The launcher icon's gradient (top-left to bottom-right): Clu is ready. */
    private val IDLE_COLORS = intArrayOf(0xFF4338CA.toInt(), 0xFF7C3AED.toInt())

    /** Amber, the spark: a question is being answered. */
    private val BUSY_COLORS = intArrayOf(0xFFFBBF24.toInt(), 0xFFD97706.toInt())

    /** Emerald: auto mode is watching the screen. */
    private val AUTO_COLORS = intArrayOf(0xFF10B981.toInt(), 0xFF047857.toInt())

    /** Dark text on amber (white on it is under 2:1); white on the other two. */
    private const val ON_BUSY = 0xFF1F1300.toInt()

    /** The panel: the dark theme's surface, opaque (the window is drawn at the panel's alpha). */
    private const val PANEL_COLOR = 0xFF14152A.toInt()

    /** A faint indigo edge, so the dark panel stays apart from a dark game. */
    private const val PANEL_EDGE = 0x66A5B4FC

    fun bubbleState(busy: Boolean, auto: Boolean): BubbleState = when {
        busy -> BubbleState.BUSY
        auto -> BubbleState.AUTO
        else -> BubbleState.IDLE
    }

    /** What the bubble says: ask, working, auto. */
    fun bubbleText(state: BubbleState): String = when (state) {
        BubbleState.IDLE -> "؟"
        BubbleState.BUSY -> "…"
        BubbleState.AUTO -> "A"
    }

    /** Paints [tv] (the bubble) for [state]; [density] is the screen's (px per dp). */
    fun styleBubble(tv: TextView, state: BubbleState, density: Float) {
        val colors = when (state) {
            BubbleState.IDLE -> IDLE_COLORS
            BubbleState.BUSY -> BUSY_COLORS
            BubbleState.AUTO -> AUTO_COLORS
        }
        tv.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            shape = GradientDrawable.OVAL
            setStroke((2 * density).toInt(), Color.WHITE)
        }
        tv.setTextColor(if (state == BubbleState.BUSY) ON_BUSY else Color.WHITE)
        tv.text = bubbleText(state)
    }

    /** The answer panel's background. */
    fun panelBackground(density: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 14 * density
        setColor(PANEL_COLOR)
        setStroke(maxOf(1, density.toInt()), PANEL_EDGE)
    }
}
