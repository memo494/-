package com.clu.quickanswer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clu.quickanswer.CalibrationBoxes.Mode
import com.clu.quickanswer.ocr.LayoutTemplate
import com.clu.quickanswer.ocr.ManualTemplate
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.TemplateReader
import java.util.UUID
import kotlin.concurrent.thread

/**
 * "معايرة يدوية": the user draws the boxes of a game on the last screen Clu captured
 * ([LastCapture]), one for the question and one per answer button (or one for the number field),
 * and the result is saved as a TRUSTED template ([ManualTemplate]) that is used from the next
 * question on. For a game that automatic learning has not learned (yet).
 *
 * Built in code, not XML: the screen is one picture with a few buttons. Everything that can be
 * decided without a device ([CalibrationBoxes], [ManualTemplate]) is unit-tested.
 */
class CalibrationActivity : AppCompatActivity() {

    private var bitmap: Bitmap? = null
    private var masks = emptyList<PxRect>()
    private val boxes = CalibrationBoxes()

    private lateinit var view: CalibrationView
    private lateinit var hint: TextView
    private lateinit var saveButton: Button
    private val modeButtons = LinkedHashMap<Mode, Button>()
    private var mode = Mode.QUESTION
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Idempotent: the store must be loaded for a saved template to reach the file.
        TemplateStore.init(this)
        val capture = LastCapture.get()
        if (capture == null) {
            setContentView(noCaptureView())
            return
        }
        bitmap = capture.bitmap
        masks = capture.masks
        // The picture fills the screen when the screen is turned like it was when it was taken.
        val landscape = capture.bitmap.width > capture.bitmap.height
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(buildUi(capture.bitmap, landscape))
        selectMode(Mode.QUESTION)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Our own copy (see LastCapture.get); a save in progress has already read its pixels.
        bitmap?.recycle()
        bitmap = null
    }

    private fun noCaptureView(): View = TextView(this).apply {
        text = "ما في لقطة بعد. شغّل الفقاعة واطلب جواب مرة، وبعدين ارجع هون."
        textSize = 18f
        gravity = Gravity.CENTER
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun buildUi(bmp: Bitmap, landscape: Boolean): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BACKGROUND)
            fitsSystemWindows = true
        }
        hint = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 13f
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        root.addView(hint, wrapWidth())

        val modeRow = row()
        for ((m, label) in listOf(Mode.QUESTION to "السؤال", Mode.OPTION to "خيار", Mode.FIELD to "خانة الرقم")) {
            val b = barButton(label) { selectMode(m) }
            modeButtons[m] = b
            modeRow.addView(b, weighted())
        }
        val actionRow = if (landscape) modeRow else row()
        actionRow.addView(barButton("تراجع") { if (boxes.undo()) boxesChanged() }, weighted())
        actionRow.addView(barButton("مسح الكل") { boxes.clear(); boxesChanged() }, weighted())
        saveButton = barButton("حفظ") { save() }
        saveButton.setBackgroundColor(SAVE_COLOR)
        actionRow.addView(saveButton, weighted())
        root.addView(modeRow, wrapWidth())
        if (actionRow !== modeRow) root.addView(actionRow, wrapWidth())

        view = CalibrationView(this, bmp, boxes) { added ->
            if (!added) Toast.makeText(this, "الحد الأقصى ${LayoutTemplate.MAX_OPTIONS} خيارات", Toast.LENGTH_SHORT).show()
            // The first box is the question; the buttons come next.
            if (added && mode == Mode.QUESTION && boxes.options.isEmpty() && boxes.numericField == null) selectMode(Mode.OPTION)
            boxesChanged()
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun selectMode(m: Mode) {
        mode = m
        view.mode = m
        for ((mm, b) in modeButtons) b.setBackgroundColor(if (mm == m) CalibrationView.colorOf(mm) else Color.TRANSPARENT)
        boxesChanged()
    }

    /** The hint under the title says what to draw now and how many options there are. */
    private fun boxesChanged() {
        val what = when (mode) {
            Mode.QUESTION -> "ارسم مربعًا حول السؤال (كل سطوره)"
            Mode.OPTION -> "ارسم مربعًا حول كل زر جواب (الزر كله) — الخيارات: ${boxes.options.size}"
            Mode.FIELD -> "ارسم مربعًا حول خانة كتابة الرقم"
        }
        hint.text = what
        view.invalidate()
    }

    /**
     * Builds the template from the boxes and saves it. The pixels are read here, on the main thread
     * (the bitmap is recycled with the activity); the rest runs on a thread of its own and ends in
     * [finishSave] on the main thread.
     */
    private fun save() {
        val bmp = bitmap ?: return
        if (saving) return
        val question = boxes.question
        if (question == null) {
            toast(INVALID_BOXES)
            return
        }
        val w = bmp.width
        val h = bmp.height
        val pixels = try {
            IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        } catch (_: Throwable) {
            toast(SAVE_FAILED)
            return
        }
        val options = boxes.options.toList()
        val field = boxes.numericField
        val masks = masks
        saving = true
        saveButton.isEnabled = false
        thread(isDaemon = true, name = "calibration-save") {
            val t = try {
                ManualTemplate.build(question, options, field, pixels, w, h, masks, UUID.randomUUID().toString(), System.currentTimeMillis())
            } catch (_: Throwable) {
                return@thread finishSave(SAVE_FAILED)
            }
            if (t == null) return@thread finishSave(INVALID_BOXES)
            // The same check the reader makes: no text lines in the question box, and it would never read.
            val layout = try {
                TemplateReader.layoutFor(t, pixels, w, h)
            } catch (_: Throwable) {
                return@thread finishSave(SAVE_FAILED)
            }
            if (layout == null) return@thread finishSave(NO_QUESTION_TEXT)
            // On the store's own thread, after its file has loaded, so nothing is lost on a fresh start.
            TemplateStore.runAfterLoad {
                // add() hands back the template already stored for the same layout instead of adding.
                // Boxes the user drew by hand win over it (a candidate still learning, or a template
                // that reads this screen badly, which is why the user is calibrating): replace it.
                val stored = try {
                    val existing = TemplateStore.templates.add(t)
                    if (existing.id != t.id) {
                        TemplateStore.templates.remove(existing.id)
                        TemplateStore.templates.add(t)
                    } else existing
                } catch (_: Throwable) {
                    null
                }
                when {
                    stored == null -> finishSave(SAVE_FAILED)
                    stored.id != t.id -> finishSave(ALREADY_KNOWN)
                    else -> finishSave(SAVED, close = true)
                }
            }
        }
    }

    /** Shows [message] and lets the user save again; closes the screen after a save that worked. */
    private fun finishSave(message: String, close: Boolean = false) {
        runOnUiThread {
            saving = false
            // The application context: the result is worth showing even if the screen was left meanwhile.
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            if (!isDestroyed) {
                saveButton.isEnabled = true
                if (close) finish()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun barButton(label: String, onClick: () -> Unit) =
        Button(this, null, android.R.attr.buttonBarButtonStyle).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun wrapWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF121212.toInt()
        const val SAVE_COLOR = 0xFF2E7D32.toInt()

        const val INVALID_BOXES = "المربعات مش منطقية: لازم سؤال + خيارين أو أكثر، أو سؤال + خانة رقم، وبدون تداخل"
        const val NO_QUESTION_TEXT = "ما قدرت ألاقي كتابة جوّا مربع السؤال، كبّره شوي"
        const val ALREADY_KNOWN = "هاي الشاشة إلها قالب من قبل"
        const val SAVED = "تم الحفظ ✓ — رح يستعمله من السؤال الجاي"
        const val SAVE_FAILED = "تعذّر الحفظ"
    }
}
