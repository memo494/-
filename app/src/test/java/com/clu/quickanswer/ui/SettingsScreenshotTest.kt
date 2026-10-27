package com.clu.quickanswer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.graphics.ColorUtils
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.Density
import com.android.resources.LayoutDirection
import com.android.resources.NightMode
import com.clu.quickanswer.AnswerSource
import com.clu.quickanswer.AnswerStats
import com.clu.quickanswer.GeminiModels
import com.clu.quickanswer.R
import com.clu.quickanswer.WelcomeDialog
import com.clu.quickanswer.showStatus
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import org.junit.Rule
import org.junit.Test

/**
 * Draws the settings screen and the icon to PNG on the JVM, light and dark, on a phone, right-to-left in
 * Arabic, so a change to the look can be seen (and reviewed) without a device. The pictures are in
 * app/src/test/snapshots/images: `./gradlew recordPaparazziDebug` writes them, `verifyPaparazziDebug` compares
 * against them; a plain `testDebugUnitTest` only draws and does not compare, so it never fails on a pixel.
 *
 * Everything on the screen is sample data typed here (no key, no real capture).
 */
class SettingsScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = PHONE,
        theme = "Theme.Clu",
        // The whole scrolling screen, not only the first 2340 pixels of it.
        renderingMode = SessionParams.RenderingMode.V_SCROLL,
        supportsRtl = true,
        // Pictures at the phone's own pixels, not shrunk to fit 1000 pixels of height.
        useDeviceResolution = true
    )

    @Test
    fun settingsLight() = settings(night = false, running = false, overlayGranted = false)

    @Test
    fun settingsDark() = settings(night = true, running = false, overlayGranted = false)

    @Test
    fun settingsLightRunning() = settings(night = false, running = true, overlayGranted = true)

    @Test
    fun settingsDarkRunning() = settings(night = true, running = true, overlayGranted = true)

    /** A tablet or a phone on its side: the content stays 600dp wide and centered. */
    @Test
    fun settingsWideLight() = settings(
        night = false,
        running = false,
        overlayGranted = false,
        device = PHONE.copy(screenWidth = 1440, screenHeight = 2560)
    )

    @Test
    fun launcherIconLight() = icons(night = false)

    @Test
    fun launcherIconDark() = icons(night = true)

    @Test
    fun welcomeDialogLight() = welcome(night = false)

    @Test
    fun welcomeDialogDark() = welcome(night = true)

    /** The first-launch dialog, built by the same code as the app's ([WelcomeDialog]). */
    private fun welcome(night: Boolean) {
        // The whole height of the dialog: its text scrolls, and a fixed canvas would cut it.
        paparazzi.unsafeUpdateConfig(
            deviceConfig = PHONE.copy(screenHeight = SHORT_HEIGHT, nightMode = mode(night)),
            renderingMode = SessionParams.RenderingMode.V_SCROLL
        )
        val context = paparazzi.context
        val dialog = WelcomeDialog.builder(
            context
        ).setPositiveButton(WelcomeDialog.BUTTON, null).create()
        dialog.show()
        // The window itself cannot be drawn: take the dialog's content out of it, on the rounded surface
        // a Material 3 dialog has, at the width a phone gives it, over the screen dimmed as behind a dialog.
        val panel = dialog.findViewById<View>(androidx.appcompat.R.id.parentPanel)!!
        (panel.parent as ViewGroup).removeView(panel)
        // Showing it made layoutlib dim everything behind its window; with the window gone the dim goes too.
        dialog.dismiss()
        val surface = GradientDrawable().apply {
            cornerRadius = dp(context, 28).toFloat()
            setColor(context.getColor(R.color.clu_surface_container_high))
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val dialogFrame = FrameLayout(context).apply {
            background = surface
            addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        }
        val screen = FrameLayout(context).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap)
            setBackgroundColor(
                ColorUtils.compositeColors(SCRIM, context.getColor(R.color.clu_background))
            )
            setPadding(dp(context, 16), dp(context, 48), dp(context, 16), dp(context, 48))
            addView(
                dialogFrame,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap)
            )
        }
        paparazzi.snapshot(screen)
    }

    private fun settings(
        night: Boolean,
        running: Boolean,
        overlayGranted: Boolean,
        device: DeviceConfig = PHONE
    ) {
        paparazzi.unsafeUpdateConfig(deviceConfig = device.copy(nightMode = mode(night)))
        val screen = paparazzi.inflate<View>(R.layout.activity_main)
        fill(screen, running, overlayGranted)
        paparazzi.snapshot(screen)
    }

    /** What MainActivity puts into the screen at run time, with sample values. */
    private fun fill(screen: View, running: Boolean, overlayGranted: Boolean) {
        val context = paparazzi.context
        screen.findViewById<Chip>(R.id.statusChip).showStatus(running)
        screen.findViewById<View>(R.id.overlayPermissionCard).visibility =
            if (overlayGranted) View.GONE else View.VISIBLE

        screen.findViewById<RadioGroup>(R.id.providerGroup).check(R.id.providerGemini)
        screen.findViewById<RadioGroup>(R.id.panelPositionGroup).check(R.id.panelPosTop)
        showText(screen, R.id.apiKeyInput, "sample-key-not-real")
        showText(screen, R.id.claudeModelInput, "claude-haiku-5-5")

        val models = GeminiModels.mergeWithCatalog(emptyList())
        val adapter =
            ArrayAdapter(context, R.layout.spinner_item, models.map { it.pickerText(false) })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        screen.findViewById<AppCompatSpinner>(R.id.modelSpinner).adapter = adapter
        screen.findViewById<TextView>(R.id.modelIdText).text = models.first().id

        val on = mapOf(
            R.id.smartReadingCheck to true,
            R.id.highlightCheck to true,
            R.id.learnFromResultsCheck to true,
            R.id.vibrateCheck to false,
            R.id.autoTouchCheck to false,
            R.id.volumeKeyCheck to true
        )
        for ((id, checked) in on) screen.findViewById<CompoundButton>(id).isChecked = checked
        screen.findViewById<TextView>(R.id.volumeKeyStatus).text =
            if (running) "✓ خفض الصوت يحلّ ويضغط الإجابة أثناء عمل الفقاعة، دون تشغيل A" else "زر الصوت غير مفعّل بعد في إمكانية الوصول"

        screen.findViewById<TextView>(R.id.clearMemoryButton).text = "مسح الذاكرة (128 سؤال)"
        screen.findViewById<TextView>(R.id.templatesStatus).text =
            "قوالب الألعاب: 3 معتمد، 2 قيد التعلّم"
        screen.findViewById<View>(R.id.clearTemplatesButton).isEnabled = true

        val stats = AnswerStats()
        repeat(9) { stats.record(AnswerSource.MEMORY, 40L, DAY) }
        repeat(4) { stats.record(AnswerSource.TEXT, 1800L, DAY) }
        stats.record(AnswerSource.IMAGE, 3200L, DAY)
        stats.record(AnswerSource.FAILED, 900L, DAY)
        screen.findViewById<TextView>(R.id.statsText).text =
            AnswerStats.describe(stats.today(DAY), stats.allTime())
        screen.findViewById<View>(R.id.clearStatsButton).isEnabled = true
    }

    /**
     * Types [text] into the field [id] of a TextInputLayout. The label floats up with an animation that
     * layoutlib never plays, so it is switched off to get the picture of the settled state.
     */
    private fun showText(screen: View, id: Int, text: String) {
        val edit = screen.findViewById<EditText>(id)
        (edit.parent.parent as TextInputLayout).isHintAnimationEnabled = false
        edit.setText(text)
    }

    /**
     * The launcher icon three ways (its two layers with the safe zone and the visible area drawn over
     * them; as the system shows it, masked; as a themed icon) next to the in-app logo.
     */
    private fun icons(night: Boolean) {
        // A short canvas: the icons need a few hundred pixels, not a phone screen.
        paparazzi.unsafeUpdateConfig(
            deviceConfig = PHONE.copy(screenHeight = SHORT_HEIGHT, nightMode = mode(night)),
            renderingMode = SessionParams.RenderingMode.NORMAL
        )
        val context = paparazzi.context
        fun row() = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        // Top: the two layers on the 108dp canvas with the guides, then the icon as a launcher shows it
        // (only the central 72 units, in a circle or a rounded square: the system's own mask cannot be
        // drawn here). Bottom: the themed icon (Android 13+) and the logo used inside the app.
        val top = row().apply {
            addView(
                FrameLayout(context).apply {
                    addView(layerImage(context, R.drawable.ic_launcher_background))
                    addView(layerImage(context, R.drawable.ic_launcher_foreground))
                    addView(
                        GuideView(context),
                        FrameLayout.LayoutParams(dp(context, 108), dp(context, 108))
                    )
                },
                LinearLayout.LayoutParams(dp(context, 108), dp(context, 108))
            )
            for (circle in listOf(true, false)) {
                addView(spacer(context))
                addView(
                    MaskedIcon(context, circle, mono = false),
                    LinearLayout.LayoutParams(dp(context, 72), dp(context, 72))
                )
            }
        }
        val bottom = row().apply {
            addView(
                MaskedIcon(context, circle = true, mono = true),
                LinearLayout.LayoutParams(dp(context, 72), dp(context, 72))
            )
            addView(spacer(context))
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_clu_logo)
                },
                LinearLayout.LayoutParams(dp(context, 64), dp(context, 64))
            )
        }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            setBackgroundColor(context.getColor(R.color.clu_background))
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            addView(top)
            addView(
                bottom,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin =
                        dp(context, 16)
                }
            )
        }
        paparazzi.snapshot(page)
    }

    private fun layerImage(context: Context, res: Int) = ImageView(context).apply {
        setImageResource(res)
        layoutParams = LinearLayout.LayoutParams(dp(context, 108), dp(context, 108))
    }

    private fun spacer(context: Context) = View(context).apply {
        layoutParams =
            LinearLayout.LayoutParams(dp(context, 12), 1)
    }

    /** The 66dp safe zone (circle) and the 72dp area every launcher shows (square), over the 108dp canvas. */
    private class GuideView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * context.resources.displayMetrics.density
            color = Color.argb(160, 255, 255, 255)
        }

        override fun onDraw(canvas: Canvas) {
            val unit = width / 108f
            canvas.drawCircle(width / 2f, height / 2f, 33f * unit, paint)
            canvas.drawRect(18f * unit, 18f * unit, 90f * unit, 90f * unit, paint)
        }
    }

    /** The 72 central units of the launcher icon, clipped to [circle] or a rounded square; [mono] is the themed icon. */
    private class MaskedIcon(
        context: Context,
        private val circle: Boolean,
        private val mono: Boolean
    ) : View(context) {
        private val layers = if (mono) {
            listOf(
                context.getDrawable(R.drawable.ic_launcher_monochrome)!!.mutate().apply {
                    setTint(context.getColor(R.color.clu_on_primary_container))
                }
            )
        } else {
            listOf(
                context.getDrawable(R.drawable.ic_launcher_background)!!,
                context.getDrawable(R.drawable.ic_launcher_foreground)!!
            )
        }
        private val themedBackground = context.getColor(R.color.clu_primary_container)

        override fun onDraw(canvas: Canvas) {
            val size = width.toFloat()
            val mask = Path().apply {
                if (circle) {
                    addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
                } else {
                    addRoundRect(
                        RectF(0f, 0f, size, size),
                        size * 0.3f,
                        size * 0.3f,
                        Path.Direction.CW
                    )
                }
            }
            canvas.clipPath(mask)
            if (mono) canvas.drawColor(themedBackground)
            val unit = size / 72f
            canvas.translate(-18f * unit, -18f * unit)
            for (layer in layers) {
                layer.setBounds(0, 0, (108 * unit).toInt(), (108 * unit).toInt())
                layer.draw(canvas)
            }
        }
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density).toInt()

    private fun mode(night: Boolean) = if (night) NightMode.NIGHT else NightMode.NOTNIGHT

    private companion object {
        /** A phone, in Arabic and right-to-left like the real screen. */
        val PHONE: DeviceConfig = DeviceConfig.PIXEL_5.copy(
            // 360dp wide (xhdpi), the narrow end of phones, drawn 720 pixels wide to keep the PNGs small.
            screenWidth = 720,
            screenHeight = 1560,
            xdpi = 320,
            ydpi = 320,
            density = Density.XHIGH,
            locale = "ar",
            layoutDirection = LayoutDirection.RTL
        )

        const val DAY = "2026-10-26"

        /** The dim behind a Material 3 dialog: black at 32%. */
        const val SCRIM = 0x52000000

        /** Taller than wide, or layoutlib takes the screen for a landscape one and swaps the sides. */
        const val SHORT_HEIGHT = 800
    }
}
