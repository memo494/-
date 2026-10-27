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
import com.clu.quickanswer.ocr.KeypadFinder
import com.clu.quickanswer.ocr.LatinOcr
import com.clu.quickanswer.ocr.LayoutTemplate
import com.clu.quickanswer.ocr.ManualTemplate
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.RegionSnap
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
    private lateinit var autoButton: Button
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
        snaps.invalidate()
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
        // The pad of a number game: its keys and its confirm button (see CalibratedKeypad).
        val keyRow = row()
        for ((m, label) in listOf(Mode.KEY to "مفاتيح الأرقام", Mode.SUBMIT to "زر التأكيد")) {
            val b = barButton(label) { selectMode(m) }
            modeButtons[m] = b
            keyRow.addView(b, weighted())
        }
        val actionRow = if (landscape) modeRow else row()
        // Zooms where the user last worked (the middle at first); after the deepest step, back out.
        actionRow.addView(barButton("🔍 تكبير") {
            val x = view.lastTouchX; val y = view.lastTouchY
            if (x.isNaN()) view.zoomStep() else view.zoomStep(x, y)
        }, weighted())
        actionRow.addView(barButton("تراجع") { if (boxes.undo()) boxesChanged() }, weighted())
        actionRow.addView(barButton("مسح الكل") { boxes.clear(); boxesChanged() }, weighted())
        saveButton = barButton("حفظ") { save() }
        saveButton.setBackgroundColor(SAVE_COLOR)
        actionRow.addView(saveButton, weighted())
        root.addView(modeRow, wrapWidth())
        // Finds the ten keys from the digits printed on them; the user only checks them.
        autoButton = barButton("اكتشاف المفاتيح تلقائيًا") { detectKeys() }
        keyRow.addView(autoButton, weighted())
        root.addView(keyRow, wrapWidth())
        if (actionRow !== modeRow) root.addView(actionRow, wrapWidth())

        view = CalibrationView(this, bmp, boxes, onTap = ::tapped, onBoxDrawn = ::boxDrawn)
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    /** A box was drawn or placed ([added] false: [boxes] refused it); moves on to what comes next. */
    private fun boxDrawn(added: Boolean) {
        if (!added) Toast.makeText(this, if (mode == Mode.KEY) "رسمت المفاتيح العشرة كلها"
            else "الحد الأقصى ${LayoutTemplate.MAX_OPTIONS} خيارات", Toast.LENGTH_SHORT).show()
        // The first box is the question; the buttons come next.
        if (added && mode == Mode.QUESTION && boxes.options.isEmpty() && boxes.numericField == null) selectMode(Mode.OPTION)
        // After the field, the keys of its pad; after the tenth key, the confirm button.
        if (added && mode == Mode.FIELD && boxes.keys.isEmpty()) {
            selectMode(Mode.KEY)
            detectKeys() // usually all ten are found, and only the confirm button is left
        }
        if (added && mode == Mode.KEY && boxes.nextKey == null && boxes.submit == null) selectMode(Mode.SUBMIT)
        boxesChanged()
    }

    private fun selectMode(m: Mode) {
        // A control still being found was tapped for the previous mode.
        snaps.invalidate()
        mode = m
        view.mode = m
        // A tap on a key needs the digits read on the capture: start reading as the keys are reached.
        if (m == Mode.KEY) readGlyphs()
        for ((mm, b) in modeButtons) b.setBackgroundColor(if (mm == m) CalibrationView.colorOf(mm) else Color.TRANSPARENT)
        boxesChanged()
    }

    /** The hint under the title says what to draw now and how many options there are. */
    private fun boxesChanged() {
        val what = when (mode) {
            Mode.QUESTION -> "ارسم مربعًا حول كتابة السؤال بس (كل سطوره)، بدون الأزرار والخانة"
            Mode.OPTION -> "اضغط على كل زر جواب (أو ارسم مربعًا حوله) — الخيارات: ${boxes.options.size}"
            Mode.FIELD -> "اضغط على خانة الرقم وهي فاضية (أو ارسم مربعًا حولها)"
            Mode.KEY -> boxes.nextKey?.let { "اضغط على أي مفتاح وبيعرف رقمه لحاله، أو اسحب مربعًا حول مفتاح $it" +
                " (الناقص: ${(0..9).filter { d -> boxes.keyAt[d] == null }.joinToString("، ")})" }
                ?: "المفاتيح العشرة جاهزة ✓ — تأكد إنه كل رقم فوق مفتاحه، وبعدين زر التأكيد"
            Mode.SUBMIT -> "اضغط على زر التأكيد إذا اللعبة فيها واحد، ثم احفظ — «🔍 تكبير» إذا صغير"
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
        // Half a pad would type some digits and not others: all ten or none. Missing keys never block
        // saving the question and the field (as before keys could be drawn); the keys are left out.
        val keysComplete = boxes.keys.size == 10
        if (boxes.keys.isNotEmpty() && !keysComplete) toast(KEYS_INCOMPLETE)
        val keys = if (keysComplete) boxes.keys.toList() else emptyList()
        val submit = if (keysComplete) boxes.submit else null
        // Say which box is wrong rather than "the boxes make no sense": the usual slip is a question
        // box drawn round the whole card, over the buttons (26 Oct 2026, on the phone).
        val others = options + listOfNotNull(field, submit) + keys
        if (others.any { overlapShare(question, it) > 0.10f }) {
            toast(QUESTION_OVER_BUTTONS)
            return
        }
        val masks = masks
        saving = true
        saveButton.isEnabled = false
        thread(isDaemon = true, name = "calibration-save") {
            val t = try {
                ManualTemplate.build(question, options, field, pixels, w, h, masks, UUID.randomUUID().toString(),
                    System.currentTimeMillis(), keys, submit)
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

    /** The words OCR read on the capture, read once in the background when the screen opens. */
    @Volatile private var glyphs: List<KeypadFinder.Glyph>? = null

    /** The capture's pixels, copied once on the first tap that needs them (not once per tap). */
    private var capturePixels: IntArray? = null

    /** Finds the control under a tap on a worker thread ([RegionSnap] is too slow for the touch callback). */
    private val snaps = SnapQueue({ job -> thread(isDaemon = true, name = "calibration-snap") { job.run() } }, ::runOnUiThread)
    private var reading = false
    private var detectWhenRead = false

    /**
     * Reads the capture with on-device OCR (no network) once, off the main thread: the bitmap is
     * copied first because the activity may recycle its own while ML Kit still reads. [then] runs
     * on the main thread once the words are there.
     */
    private fun readGlyphs(then: (() -> Unit)? = null) {
        if (glyphs != null) { then?.invoke(); return }
        if (then != null) detectWhenRead = true
        if (reading) return
        val bmp = bitmap ?: return
        val copy = try { bmp.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null } ?: return
        reading = true
        thread(isDaemon = true, name = "calibration-ocr") {
            val read = try { LatinOcr().use { it.glyphs(copy) } } catch (_: Throwable) { emptyList() } finally { copy.recycle() }
            runOnUiThread {
                // A failed or timed-out read is not kept, so the next try reads again.
                glyphs = read.takeIf { it.isNotEmpty() }
                reading = false
                if (!isDestroyed && detectWhenRead) {
                    detectWhenRead = false
                    // Nothing read: say so once (no automatic re-read loop); the button tries again.
                    if (glyphs == null) toast(DETECT_FAILED) else detectKeys()
                }
            }
        }
    }

    /**
     * Proposes the keys from the digits printed on them ([KeypadFinder]): all ten when they are
     * all read, otherwise the ones that are (the user taps the rest, each tap set to the digit
     * under it).
     */
    private fun detectKeys() {
        val bmp = bitmap ?: return
        val read = glyphs
        if (read == null) {
            toast(DETECTING)
            readGlyphs { detectKeys() }
            return
        }
        val all = KeypadFinder.find(read, bmp.width, bmp.height)
        if (all != null && boxes.setKeys(all)) {
            toast(DETECTED)
            selectMode(if (boxes.submit == null) Mode.SUBMIT else Mode.KEY)
            return
        }
        val some = KeypadFinder.partial(read, bmp.width, bmp.height)
        if (some == null) {
            toast(DETECT_FAILED)
            selectMode(Mode.KEY)
            return
        }
        for ((d, r) in some.withIndex()) if (r != null && boxes.keyAt[d] == null) boxes.setKey(d, r)
        val missing = (0..9).filter { boxes.keyAt[it] == null }
        toast(DETECTED_SOME + missing.joinToString("، "))
        selectMode(Mode.KEY)
    }

    /**
     * A tap on the picture: in "keys" the digit printed under it gets its key (any order); for the
     * field, an answer button or the confirm button, the control under it is grown from its colour
     * ([RegionSnap], on a worker thread). When nothing is found there, the confirm button gets a
     * key-sized square on the tap and the other controls get nothing.
     */
    private fun tapped(x: Int, y: Int) {
        val bmp = bitmap ?: return
        when (mode) {
            Mode.KEY -> {
                // Only a digit actually read under the tap: guessing "the next missing digit" would
                // map a key to another digit and type wrong numbers. Unknown: say how to draw it.
                val hit = glyphs?.let { KeypadFinder.digitAt(it, bmp.width, bmp.height, x, y) }
                if (hit == null) {
                    toast(if (glyphs == null) DETECTING else KEY_NOT_READ + (boxes.nextKey ?: ""))
                    return // no box is placed
                }
                boxes.setKey(hit.first, hit.second)
                boxDrawn(true)
            }
            Mode.FIELD, Mode.OPTION, Mode.SUBMIT -> {
                val pixels = capturePixels ?: try {
                    IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                        .also { capturePixels = it }
                } catch (_: Throwable) { return }
                val w = bmp.width
                val h = bmp.height
                val tappedMode = mode
                // A tap while the previous one is still being found is ignored.
                snaps.request({ RegionSnap.around(pixels, w, h, x, y) }) { rect ->
                    if (isDestroyed || bitmap == null) return@request
                    when {
                        rect != null -> boxDrawn(boxes.add(tappedMode, rect))
                        // The button's colour gave no region: a key-sized square on the tap.
                        tappedMode == Mode.SUBMIT -> boxDrawn(boxes.add(tappedMode, boxes.boxAround(x, y, w, h)))
                    }
                    view.invalidate()
                }
            }
            Mode.QUESTION -> {}
        }
    }

    /** The share of the smaller of [a] and [b] that they overlap (0 when apart). */
    private fun overlapShare(a: PxRect, b: PxRect): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val smaller = minOf(a.width.toLong() * a.height, b.width.toLong() * b.height).coerceAtLeast(1)
        return (w.toLong() * h).toFloat() / smaller
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
        const val KEY_NOT_READ = "ما عرفت رقم هالمفتاح؛ ارسم مربعًا (اسحب) حول مفتاح الرقم "
        const val DETECTING = "عم بدوّر على الأرقام…"
        const val DETECTED = "لقيت المفاتيح العشرة ✓ — راجعها، وإذا في غلط اضغط تراجع"
        const val DETECTED_SOME = "لقيت جزء من المفاتيح ✓ — اضغط على الباقي: "
        const val DETECT_FAILED = "ما قدرت ألاقي لوحة أرقام كاملة؛ اضغط على المفاتيح بإيدك من 0 لـ 9"
        const val QUESTION_OVER_BUTTONS = "مربع السؤال لازم يكون حول كتابة السؤال بس، مش حول الأزرار أو الخانة — اضغط «السؤال» وارسمه من جديد"
        const val KEYS_INCOMPLETE = "المفاتيح ناقصة، فبنحفظ السؤال والخانة بدونها (ارجع كمّل المفاتيح العشرة وقت ما بدك)"
    }
}
