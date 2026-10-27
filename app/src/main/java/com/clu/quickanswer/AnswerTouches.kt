package com.clu.quickanswer

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.clu.quickanswer.OverlayService.Companion.CLEAN_POLL_MS
import com.clu.quickanswer.interaction.AnswerInteraction
import com.clu.quickanswer.interaction.NumericEntryCheck
import com.clu.quickanswer.interaction.PrintedChoiceTouch
import com.clu.quickanswer.ocr.CalibratedKeypad
import com.clu.quickanswer.ocr.ChoiceTouchGuard
import com.clu.quickanswer.ocr.ColorLayout
import com.clu.quickanswer.ocr.FieldStamp
import com.clu.quickanswer.ocr.GameVisualLayout
import com.clu.quickanswer.ocr.NumericFieldInk
import com.clu.quickanswer.ocr.NumericFieldReader
import com.clu.quickanswer.ocr.OptionWordDetector
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import com.clu.quickanswer.ocr.ScreenFingerprint
import com.clu.quickanswer.ocr.TextLines
import com.clu.quickanswer.ocr.WordAnswerMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.math.abs

// The automatic touch of the answer, split out of OverlayService: planning the touches on the
// captured frame (prepareTouches), the guards that run before every stroke and the numeric-entry
// readback (performAnswerTouches, verifyEntry). The functions that need the service's state are
// extension functions of it, so they read and change the same touch state as before; that state is
// `internal` there for this file.


/** Before touching: at most this many [CLEAN_POLL_MS] waits for a frame without the panel. */
private const val CLEAN_TOUCH_POLLS = 15

/** Bind every answer source to the same interaction engine. All pixel/OCR work here is on IO. */
internal fun OverlayService.prepareTouches(solved: Solved, bmp: Bitmap, masks: List<PxRect>): Solved {
    // Refusals without a message of their own: the panel stays as it was, the step goes to the log.
    fun quiet(code: String): Solved { DiagLog.add("رفض اللمس: $code"); return solved }
    return try {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val request = solved.request
        val q = request?.question
        val picture = solved.pictureRect
        fun overlaps(a: PxRect, b: PxRect) = a.left < b.right && b.left < a.right &&
            a.top < b.bottom && b.top < a.bottom
        if (picture != null && TouchFrameMask.obscuresPicture(picture, masks)) {
            return solved.copy(touchFailure = "الفقاعة تغطّي الصورة؛ أبعدها ثم اطلب الجواب")
        }
        if (request != null && q != null && !q.numeric) {
            val banner = q.bannerRect ?: return quiet("no banner")
            val words = q.optionTextRects.takeIf { it.size == q.options.size } ?: q.optionRects
            if (!ChoiceTouchGuard.isReadyFromLayout(pixels, w, h, banner, q.optionRects, words)) {
                return solved.copy(touchFailure = "الأزرار غير جاهزة أو بعض الكتابة محجوب")
            }
            val plan = AnswerInteraction.plan(request, AnswerInteraction.Controls(w, h))
                ?: return solved.copy(touchFailure = "الإجابة لا تطابق خيارًا واضحًا؛ لم يتم الضغط")
            val questionRegion = questionRegion(q, pixels, w, h) ?: return solved.copy(
                touchFailure = "لم أجد منطقة السؤال كاملة للتحقق قبل اللمس")
            return solved.copy(touches = plan.taps, guardRect = questionRegion,
                guardStamp = fingerprint(bmp, questionRegion, masks), ignoredRects = masks,
                choiceButtons = q.optionRects, choiceWords = words, choiceFromLayout = true,
                choiceStamps = words.mapNotNull { fingerprint(bmp, it, masks) },
                pictureStamp = picture?.let { fingerprint(bmp, it, masks) })
        }
        // Verified skin detectors are optional geometry adapters. A learned number field alone
        // cannot authorize a guessed keypad; future adapters can supply other verified controls.
        val visual = GameVisualLayout.detect(pixels, w, h)
            ?.takeIf { it.kind != GameVisualLayout.Kind.CHOICE_GRID }
            ?.let { if (it.submit != null && !GameVisualLayout.hasSubmitControl(pixels, w, h, it))
                it.copy(submit = null) else it }
        // A number question on a pad no adapter knows: the pad the user drew, if any.
        if (q?.numeric == true && visual == null) return calibratedTouches(solved, request, q, pixels, w, h, bmp, masks)
        if (q?.numeric == true || request == null && visual != null) {
            if (visual == null) return solved.copy(touchFailure = "لوحة الأرقام غير معروفة بعد؛ لم أكتب شيئًا")
            // The keypad adapter measures this skin's field; a learned box can include its bevel or wood.
            // The dark skin's reference box can reach into the field's gold rim when the card is
            // drawn off the reference (owner's phone): check and read only the measured inside.
            val measured = visual.field?.let {
                if (visual.kind == GameVisualLayout.Kind.NUMERIC_DARK)
                    GameVisualLayout.fieldInterior(pixels, w, h, it) ?: it else it
            }
            val field = measured ?: q?.numericField ?: return quiet("no field")
            val ink = if (visual.kind == GameVisualLayout.Kind.NUMERIC_DARK)
                NumericFieldReader.Ink.GOLD else NumericFieldReader.Ink.LIGHT
            // Our own window over the field hides what is in it: never call that empty.
            if (masks.any { overlaps(it, field) }) return solved.copy(touchFailure =
                "الفقاعة فوق خانة الرقم؛ أبعدها ثم اطلب الجواب")
            val sample = NumericFieldInk.inspect(pixels, w, h, field, ink)
            DiagLog.add("خانة الرقم ${field.width}x${field.height}: ${sample.state} ${sample.why}")
            when (sample.state) {
                NumericFieldInk.State.FILLED -> return solved.copy(touchFailure =
                    "الخانة فيها إدخال سابق؛ لم أمسحه ولم أضف عليه")
                NumericFieldInk.State.UNKNOWN -> return solved.copy(touchFailure =
                    "لم أتحقق أن الخانة فارغة؛ لم أكتب أو أمسح شيئًا")
                NumericFieldInk.State.EMPTY -> Unit
            }
            val numericRequest = request ?: AnswerInteraction.Request(
                Question("", emptyList(), emptyList(), true, 1f, numericField = field),
                solved.rawReply, solved.rawReply)
            val plan = AnswerInteraction.plan(numericRequest, AnswerInteraction.Controls(
                w, h, visual.digits, submit = visual.submit, fieldIsEmpty = true))
                ?: return solved.copy(touchFailure = "تعذّر تحديد مفاتيح إدخال رقم من 1–4 خانات")
            val banner = q?.let { questionRegion(it, pixels, w, h) }?.takeUnless { overlaps(it, field) }
                ?: visual.questionArea
            return solved.copy(touches = plan.taps, guardRect = banner,
                guardStamp = fingerprint(bmp, banner, masks), ignoredRects = masks,
                numericLayout = visual.copy(clear = null, backspace = null), submitLast = visual.submit != null,
                expectedNumber = plan.number, fieldRect = field, fieldStamp = fingerprint(bmp, field, masks),
                pictureStamp = picture?.let { fingerprint(bmp, it, masks) })
        }
        if (request != null) return quiet("read question, no match") // never replace a rejected trusted reading with guessed positions
        // A mixed Arabic/Latin question may fail both question readers while every option is clear.
        // Geometry is detected again from the visible banner/buttons; candidate/model boxes are not used.
        // This fallback initially supports the four-row example. Two remaining white rows on
        // a red/green result screen must not be mistaken for a fresh two-option question.
        val first = ColorLayout().inspect(pixels, w, h)
        // The question text could not be cut (something over it), but the buttons may still be
        // verified and their words read: then the banner itself guards the touch.
        val stackInspection = if (first.layout == null && first.rejection == "question text band merged")
            ColorLayout().inspect(pixels, w, h, keepMergedQuestion = true) else first
        val stack = stackInspection.layout?.takeIf { !it.numeric && it.buttons.size == 4 }
        val stackWhy = stackInspection.rejection ?: stackInspection.layout?.let {
            if (it.numeric) "numeric" else "${it.buttons.size} buttons" } ?: "-"
        val stackWords = stack?.let { PrintedChoiceTouch.regions(it, pixels, w, h) }
        if (stack != null && (stack.questionLines.isNotEmpty() || first.layout == null) && stackWords != null) {
            val words = stackWords
            if (ChoiceTouchGuard.isReadyFromLayout(pixels, w, h, stack.banner, stack.buttons, words)) {
                val printed = synchronized(pipeline.ocrLock) { pipeline.ocr?.readWords(bmp, words) }.orEmpty()
                val independent = PrintedChoiceTouch.request(stack, printed, solved.rawReply)
                val plan = independent?.let { AnswerInteraction.plan(it, AnswerInteraction.Controls(w, h)) }
                val region = independent?.question?.let { questionRegion(it, pixels, w, h) }
                    ?: stack.banner.takeIf { independent != null && stack.questionLines.isEmpty() }
                if (plan != null && region != null) return solved.copy(touches = plan.taps,
                    guardRect = region, guardStamp = fingerprint(bmp, region, masks), ignoredRects = masks,
                    choiceButtons = stack.buttons, choiceWords = words, choiceFromLayout = true,
                    choiceStamps = words.mapNotNull { fingerprint(bmp, it, masks) })
            }
        }
        // Legacy fallback: independently read all four printed words; candidate/model boxes alone
        // never authorize a touch. The original reply is retained even when display matching was fuzzy.
        val choices = OptionWordDetector.detectChoices(pixels, w, h) ?: return quiet("no choices found ($stackWhy)")
        if (!ChoiceTouchGuard.isReady(pixels, w, h, choices.buttons, choices.words)) return quiet("choices not ready")
        val printed = synchronized(pipeline.ocrLock) { pipeline.ocr?.readWords(bmp, choices.words) } ?: return quiet("words not read")
        val printedOptions = printed.map { it.text }
        val literal = printedOptions.singleOrNull {
            AnswerInteraction.matchesChoice(solved.rawReply, it, printedOptions, indexRequested = false)
        } ?: return quiet("answer not among the words read: " + printedOptions.joinToString("|").take(80))
        val target = WordAnswerMatcher.match(literal, printed) ?: return quiet("word not placed")
        val actions = TouchPlanner.plan(solved.rawReply, target, emptyMap(), null, w, h) ?: return quiet("no plan")
        val content = ChoiceTouchGuard.stableContent(pixels, w, h, choices.buttons) ?: return solved.copy(
            touchFailure = "لم أجد نص السؤال كاملًا للتحقق قبل اللمس")
        if (content.picture != null && TouchFrameMask.obscuresPicture(content.picture, masks)) return solved.copy(
            touchFailure = "الفقاعة تغطّي الصورة؛ أبعدها ثم اطلب الجواب")
        solved.copy(touches = actions, guardRect = content.question, guardStamp = fingerprint(bmp, content.question, masks),
            choiceButtons = choices.buttons, choiceWords = choices.words,
            choiceStamps = choices.words.mapNotNull { fingerprint(bmp, it, masks) }, ignoredRects = masks,
            pictureRect = content.picture, pictureStamp = content.picture?.let { fingerprint(bmp, it, masks) })
    } catch (e: Throwable) { quiet("error ${e.javaClass.simpleName}") }
}

/** Use measured question ink, not a banner that can also contain an animated badge. */
private fun questionRegion(q: Question, pixels: IntArray, w: Int, h: Int): PxRect? {
    val lines = q.questionTextRects.ifEmpty {
        val banner = q.bannerRect ?: return null
        val found = TextLines.findDetailed(pixels, w, h, banner, bounded = true)
        if (found.clipped) return null
        found.lines
    }
    if (lines.isEmpty()) return null
    return PxRect(lines.minOf { it.left }, lines.minOf { it.top }, lines.maxOf { it.right }, lines.maxOf { it.bottom })
}

/** Fresh field readback finishes asynchronously; no model lock or native inference on the UI thread. */
private fun OverlayService.verifyEntry(solved: Solved, epoch: Long, complete: (Boolean) -> Unit) {
    val expected = solved.expectedNumber
    val field = solved.fieldRect
    val initial = solved.fieldStamp
    if (expected == null || field == null || initial == null || destroyed || epoch != touchEpoch) {
        complete(false)
        return
    }
    if (windows.overlayRects().any { it.left < field.right && field.left < it.right &&
            it.top < field.bottom && field.top < it.bottom }) { complete(false); return }
    scope.launch {
        // Accessibility's UP may precede the game's next rendered frame. Read again, never retype.
        repeat(NumericEntryCheck.READBACK_ATTEMPTS) { attempt ->
            if (destroyed || epoch != touchEpoch) { complete(false); return@launch }
            if (attempt > 0) delay(NumericEntryCheck.READBACK_INTERVAL_MS)
            capture.grabFrame()
            val frame = capture.lastFrame
            val current = frame?.let { fingerprint(it, field, solved.ignoredRects) }
            if (frame != null && current != null && NumericEntryCheck.fieldChanged(initial, current)) {
                if (field.left < 0 || field.top < 0 || field.right > frame.width ||
                    field.bottom > frame.height || field.width <= 0 || field.height <= 0) {
                    complete(false)
                    return@launch
                }
                // Rounded gold fields need neighboring parchment to distinguish their corners
                // from clipped digits. Keep two pixels of context without widening the OCR field.
                val context = PxRect(maxOf(0, field.left - 2), maxOf(0, field.top - 2),
                    minOf(frame.width, field.right + 2), minOf(frame.height, field.bottom + 2))
                val localField = PxRect(field.left - context.left, field.top - context.top,
                    field.right - context.left, field.bottom - context.top)
                val snapshot = try {
                    val cropped = Bitmap.createBitmap(frame, context.left, context.top, context.width, context.height)
                    // A whole-frame crop can alias lastFrame, which the next capture recycles.
                    if (cropped === frame) frame.copy(Bitmap.Config.ARGB_8888, false) else cropped
                } catch (_: Throwable) { null }
                if (snapshot != null) {
                    val matches = withContext(Dispatchers.IO) {
                        try {
                            // A calibrated pad's ink colour is not known: the generic line reader.
                            val ink = when {
                                solved.calibratedKeys != null -> NumericFieldReader.Ink.AUTO
                                solved.numericLayout?.kind == GameVisualLayout.Kind.NUMERIC_DARK -> NumericFieldReader.Ink.GOLD
                                else -> NumericFieldReader.Ink.LIGHT
                            }
                            val read = synchronized(pipeline.ocrLock) { pipeline.ocr?.readField(snapshot, localField, ink) }
                            read != null && NumericEntryCheck.matches(expected, read.text, read.confidence, changed = true)
                        } catch (_: Throwable) { false }
                        finally { snapshot.recycle() }
                    }
                    if (matches) { complete(!destroyed && epoch == touchEpoch); return@launch }
                }
            }
        }
        complete(false)
    }
}

/**
 * Number entry on a pad the user drew ([CalibratedKeypad]): no built-in adapter knows this game.
 * The same rules as the built-in pads: the field must be proven empty (here against its empty
 * look when calibrated), digits then the confirm button only, and the confirm is sent only after
 * the field reads back the expected number ([verifyNumericEntry] with the generic reader).
 */
private fun calibratedTouches(
    solved: Solved, request: AnswerInteraction.Request?, q: Question, pixels: IntArray, w: Int, h: Int,
    bmp: Bitmap, masks: List<PxRect>,
): Solved {
    val fp = ScreenFingerprint.of(pixels, w, h, masks)
    val keys = CalibratedKeypad.find(TemplateStore.templates.find(fp, null, trustedOnly = true), pixels, w, h)
        ?: return solved.copy(touchFailure = "لوحة الأرقام غير معروفة بعد؛ علّمها من المعايرة اليدوية")
    if (masks.any { it.left < keys.field.right && keys.field.left < it.right && it.top < keys.field.bottom &&
            keys.field.top < it.bottom }) return solved.copy(touchFailure = "الفقاعة فوق خانة الرقم؛ أبعدها ثم اطلب الجواب")
    when (CalibratedKeypad.fieldState(keys, pixels, w, h)) {
        FieldStamp.State.FILLED -> return solved.copy(touchFailure = "الخانة فيها إدخال سابق؛ لم أمسحه ولم أضف عليه")
        FieldStamp.State.UNKNOWN -> return solved.copy(touchFailure = "لم أتحقق أن الخانة فارغة؛ لم أكتب أو أمسح شيئًا")
        FieldStamp.State.EMPTY -> Unit
    }
    val numericRequest = request ?: return solved
    val plan = AnswerInteraction.plan(numericRequest, AnswerInteraction.Controls(
        w, h, keys.digits, submit = keys.submit, fieldIsEmpty = true))
        ?: return solved.copy(touchFailure = "تعذّر تحديد مفاتيح إدخال رقم من 1–4 خانات")
    val banner = questionRegion(q, pixels, w, h)?.takeUnless {
        it.left < keys.field.right && keys.field.left < it.right && it.top < keys.field.bottom && keys.field.top < it.bottom
    } ?: return solved.copy(touchFailure = "لم أجد منطقة السؤال كاملة للتحقق قبل اللمس")
    return solved.copy(touches = plan.taps, guardRect = banner,
        guardStamp = fingerprint(bmp, banner, masks), ignoredRects = masks,
        calibratedKeys = keys, submitLast = keys.submit != null,
        expectedNumber = plan.number, fieldRect = keys.field, fieldStamp = fingerprint(bmp, keys.field, masks),
        pictureStamp = solved.pictureRect?.let { fingerprint(bmp, it, masks) })
}

/** Preserve every question line, including its final word; input fields are outside [area]. */
private fun fingerprint(bitmap: Bitmap, area: PxRect, ignored: List<PxRect> = emptyList()): IntArray? {
    val left = area.left.coerceIn(0, bitmap.width)
    val top = area.top.coerceIn(0, bitmap.height)
    val right = area.right.coerceIn(0, bitmap.width)
    val bottom = area.bottom.coerceIn(0, bitmap.height)
    if (right <= left || bottom <= top) return null
    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    val small = Bitmap.createScaledBitmap(crop, TouchFrameGuard.WIDTH, TouchFrameGuard.HEIGHT, true)
    if (crop !== small && crop !== bitmap) crop.recycle()
    val pixels = IntArray(TouchFrameGuard.WIDTH * TouchFrameGuard.HEIGHT)
    small.getPixels(pixels, 0, TouchFrameGuard.WIDTH, 0, 0, TouchFrameGuard.WIDTH, TouchFrameGuard.HEIGHT)
    if (small !== bitmap) small.recycle()
    val stamp = IntArray(pixels.size) { i ->
        val c = pixels[i]
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    }
    return TouchFrameMask.apply(stamp, PxRect(left, top, right, bottom), ignored)
}

internal fun OverlayService.performAnswerTouches(solved: Solved, w: Int, h: Int, trigger: AnswerTrigger,
                                                 retryAttempt: AutoAnswerRetry.Attempt?) {
    fun notStarted(why: String) {
        DiagLog.add("اللمس لم يبدأ: $why")
        retryAttempt?.finish(retryable = true, SystemClock.uptimeMillis())
    }
    val boxes = solved.touches ?: return notStarted("لا خطة لمس")
    val stamp = solved.guardStamp ?: return notStarted("لا بصمة للسؤال")
    val region = solved.guardRect ?: return notStarted("لا منطقة للسؤال")
    if (!TouchFrameGuard.usable(stamp)) return notStarted("بصمة السؤال غير صالحة")
    if (touchPending || !VolumeKeyService.isEnabled(this)) {
        if (!VolumeKeyService.isEnabled(this)) windows.showPanel("فعّل خدمة إمكانية الوصول لتشغيل اللمس التلقائي")
        return notStarted(if (touchPending) "لمس سابق لسا شغّال" else "خدمة إمكانية الوصول مطفية")
    }
    touchPending = true
    val epoch = touchEpoch
    val window = VolumeKeyService.contextVersion()
    var nextTap = 0
    var mayHaveDispatched = false
    var entryRejected = false
    var cancelReason = "توقف الإدخال؛ تحقق من الشاشة"
    fun blocked(reason: String): Boolean { cancelReason = reason; return false }
    fun canContinue(): Boolean {
        if (destroyed || epoch != touchEpoch || !permitsTouch(trigger) ||
            window != VolumeKeyService.contextVersion() ||
            w != capture.screenW || h != capture.screenH || windows.preview != null || projection == null
        ) return blocked("تغيّر التطبيق أو وضع الشاشة؛ أُلغي اللمس")
        return try {
            capture.grabFrame()
            val frame = capture.lastFrame ?: return blocked("لم تصل لقطة شاشة جديدة؛ لم أضغط")
            val ignored = solved.ignoredRects + windows.overlayRects().map { PxRect(it.left, it.top, it.right, it.bottom) }
            if (frame.width != w || frame.height != h) return blocked("تغيّر مقاس اللقطة؛ لم أضغط")
            val nowStamp = fingerprint(frame, region, ignored)
            if (nowStamp?.let { TouchFrameGuard.matches(stamp, it) } != true) {
                DiagLog.add("فحص السؤال قبل الضغطة ${nextTap + 1}: " +
                    (nowStamp?.let { TouchFrameGuard.explain(stamp, it) } ?: "لا لقطة للمنطقة"))
                return blocked("تغيّر نص السؤال أو لم تستقر لقطته؛ لم أضغط")
            }
            val picture = solved.pictureRect
            if (picture != null && (solved.pictureStamp == null ||
                fingerprint(frame, picture, ignored)?.let { TouchFrameGuard.matches(solved.pictureStamp, it) } != true
            )) return blocked("تغيّرت الصورة أو حُجب جزء منها؛ لم أضغط")
            val obstruction = windows.viewRect(windows.bubble)
            if (obstruction != null && boxes.any { r ->
                r.left < obstruction.right && r.right > obstruction.left &&
                    r.top < obstruction.bottom && r.bottom > obstruction.top
            }) return blocked("الفقاعة تغطّي زر الإجابة؛ أبعدها ثم أعد الطلب")
            val numeric = solved.numericLayout
            val calibrated = solved.calibratedKeys
            if (calibrated != null) {
                // One copy of this frame's pixels for every check before this tap (and the next ones on it).
                val pixels = capture.framePixels() ?: return blocked("لم تصل لقطة شاشة جديدة؛ لم أضغط")
                if (!CalibratedKeypad.stillVisible(calibrated, pixels, w, h, boxes.getOrNull(nextTap - 1),
                        checkSubmit = nextTap == 0))
                    return blocked("لوحة الأرقام أو زر التأكيد لم يعد ظاهرًا؛ أوقفت الإدخال")
                if (nextTap == 0 && CalibratedKeypad.fieldState(calibrated, pixels, w, h) != FieldStamp.State.EMPTY)
                    return blocked("الخانة لم تعد فارغة؛ لم أمسح أو أضف أرقامًا")
            }
            if (numeric != null || solved.choiceButtons.isNotEmpty()) {
                // One copy of this frame's pixels for every check before this tap (and the next ones on it).
                val pixels = capture.framePixels() ?: return blocked("لم تصل لقطة شاشة جديدة؛ لم أضغط")
                if (numeric != null) {
                    val padThere = GameVisualLayout.stillVisible(pixels, w, h, numeric, boxes.getOrNull(nextTap - 1))
                    val submitThere = numeric.submit == null || GameVisualLayout.hasSubmitControl(pixels, w, h, numeric)
                    if (!padThere || !submitThere) {
                        DiagLog.add("لوحة الأرقام قبل الضغطة ${nextTap + 1}: المفاتيح=${if (padThere) "ظاهرة" else "لا"}" +
                            " التأكيد=${if (submitThere) "ظاهر" else "لا"}")
                        return blocked("لوحة الأرقام أو زر التأكيد لم يعد ظاهرًا؛ أوقفت الإدخال")
                    }
                }
                if (numeric != null && nextTap == 0) {
                    val field = solved.fieldRect ?: return false
                    if (obstruction != null && field.left < obstruction.right && field.right > obstruction.left &&
                        field.top < obstruction.bottom && field.bottom > obstruction.top
                    ) return blocked("الفقاعة فوق خانة الرقم؛ أبعدها ثم أعد الطلب")
                    val ink = if (numeric.kind == GameVisualLayout.Kind.NUMERIC_DARK)
                        NumericFieldReader.Ink.GOLD else NumericFieldReader.Ink.LIGHT
                    val sample = NumericFieldInk.inspect(pixels, w, h, field, ink)
                    if (sample.state != NumericFieldInk.State.EMPTY) {
                        DiagLog.add("خانة الرقم قبل أول رقم: ${sample.state} ${sample.why}")
                        return blocked("الخانة لم تعد فارغة؛ لم أمسح أو أضف أرقامًا")
                    }
                }
                if (solved.choiceButtons.isNotEmpty()) {
                    val ready = if (solved.choiceFromLayout) ChoiceTouchGuard.isReadyFromLayout(
                        pixels, w, h, region, solved.choiceButtons, solved.choiceWords)
                    else ChoiceTouchGuard.isReady(pixels, w, h, solved.choiceButtons, solved.choiceWords)
                    if (!ready) {
                        val which = if (solved.choiceFromLayout) runCatching {
                            ChoiceTouchGuard.firstUnreadyFromLayout(pixels, w, h, region, solved.choiceButtons, solved.choiceWords)
                        }.getOrDefault(-1) else -1
                        DiagLog.add("الأزرار غير جاهزة قبل الضغطة ${nextTap + 1}: " +
                            if (which > 0) "الزر $which (ملوّن أو مغطّى)" else "شكل الأزرار تغيّر")
                        return blocked("أزرار الإجابة غير جاهزة للضغط؛ لم أضغط")
                    }
                }
                if (solved.choiceButtons.isNotEmpty() &&
                    (solved.choiceStamps.size != solved.choiceButtons.size ||
                        solved.choiceWords.size != solved.choiceButtons.size || solved.choiceWords.indices.any { index ->
                        fingerprint(frame, solved.choiceWords[index], ignored)?.let {
                            TouchFrameGuard.matches(solved.choiceStamps[index], it)
                        } != true
                    })
                ) return blocked("تغيّر نص الخيارات أو حُجب؛ لم أضغط")
            }
            true
        } catch (_: Throwable) { blocked("تعذّر فحص الشاشة؛ أوقفت اللمس") }
    }
    touchJob = scope.launch {
        try {
            // Includes queued capture frames, all gesture callbacks and numeric readback.
            // Even a missing platform callback cannot leave the bubble busy indefinitely.
            withTimeout(15_000L) {
                delay(80L)
                if (destroyed || epoch != touchEpoch || !permitsTouch(trigger) ||
                    w != capture.screenW || h != capture.screenH || windows.preview != null) {
                    DiagLog.add("اللمس لم يبدأ: تغيّرت الحالة (محاولة أحدث، الإعدادات، الدوران أو الصورة المرسلة)")
                    return@withTimeout
                }
                windows.hidePanel()
                windows.hideHighlight()
                val removedAt = System.nanoTime()
                delay(100L)
                // The checks below compare the question with the capture; a frame that still shows
                // our answer panel over it fails them ("the question changed", "buttons not
                // ready"). On the owner's phone the first frame without the panel came 236-272 ms
                // after it was hidden (log, 26 Oct 2026), later than the checks' three tries.
                var frameWaits = 0
                while (frameWaits < CLEAN_TOUCH_POLLS) {
                    try { capture.grabFrame() } catch (_: Throwable) { }
                    val at = capture.lastFrameAtNs
                    val trusted = at > 0 && abs(at - System.nanoTime()) < 60_000_000_000L
                    if (!trusted || at > removedAt) break
                    delay(CLEAN_POLL_MS)
                    frameWaits++
                }
                if (frameWaits > 0) DiagLog.add("اللمس: انتظار إطار بدون اللوحة ${100 + frameWaits * CLEAN_POLL_MS}ms")
                val ready = suspendCancellableCoroutine<Boolean> { continuation ->
                    TouchPreflight { delay, action -> check(handler.postDelayed({ action() }, delay)) }
                        .awaitReady(
                            isCurrent = { continuation.isActive && !destroyed && epoch == touchEpoch },
                            ready = ::canContinue,
                        ) { if (continuation.isActive) continuation.resume(it) }
                }
                if (!ready) {
                    DiagLog.add("اللمس لم يبدأ: $cancelReason")
                    windows.showPanel("${solved.text}\n$cancelReason")
                    return@withTimeout
                }
                if (!touchAttempts.tryBegin(stamp, w, h)) {
                    DiagLog.add("اللمس: نفس الشاشة جُرّبت قبل، تُركت")
                    // This question already had an accepted run; it must not be replayed.
                    mayHaveDispatched = true
                    return@withTimeout
                }
                setBusy(true)
                val finished = suspendCancellableCoroutine<Boolean> { continuation ->
                    // If the platform throws after accepting input, its outcome is uncertain.
                    mayHaveDispatched = true
                    val accepted = VolumeKeyService.tapAnswer(boxes, solved.submitLast, canContinue = {
                        continuation.isActive && canContinue().also { if (it) nextTap++ }
                    }, beforeSubmit = { complete -> verifyEntry(solved, epoch) { allowed ->
                        entryRejected = !allowed
                        complete(allowed)
                    } }) { if (continuation.isActive) continuation.resume(it) }
                    if (!accepted) {
                        // A refused request sent no stroke. Accepted runs stay consumed.
                        mayHaveDispatched = false
                        touchAttempts.reset()
                        cancelReason = "خدمة اللمس غير جاهزة أو مشغولة"
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                DiagLog.add(when {
                    finished -> "اللمس اكتمل (${boxes.size} ضغطة)"
                    entryRejected -> "اللمس: الرقم المقروء بالخانة ما طابق، لم يُضغط التأكيد"
                    else -> "اللمس توقف: $cancelReason"
                })
                if (!finished) windows.showPanel(if (entryRejected) "تعذّر التحقق من الرقم؛ لم يتم التأكيد"
                    else cancelReason)
            }
        } catch (_: TimeoutCancellationException) {
            DiagLog.add("اللمس: انتهت المهلة")
            if (!destroyed && epoch == touchEpoch) try {
                windows.showPanel("انتهت مهلة اللمس؛ جاهز للسؤال التالي")
            } catch (_: Throwable) { }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (!destroyed && epoch == touchEpoch) try {
                windows.showPanel("تعذّر إكمال اللمس؛ جاهز للسؤال التالي")
            } catch (_: Throwable) { }
        } finally {
            try {
                if (!destroyed && epoch == touchEpoch && window == VolumeKeyService.contextVersion() &&
                    permitsTouch(trigger) && w == capture.screenW && h == capture.screenH) {
                    retryAttempt?.finish(retryable = !mayHaveDispatched, SystemClock.uptimeMillis())
                } else retryAttempt?.cancel()
            } finally {
                if (epoch == touchEpoch) {
                    // Invalidate any late readback before releasing the next request.
                    touchEpoch++
                    try { VolumeKeyService.cancelAnswer() } finally {
                        touchPending = false
                        touchJob = null
                        setBusy(false)
                    }
                }
            }
        }
    }
}
