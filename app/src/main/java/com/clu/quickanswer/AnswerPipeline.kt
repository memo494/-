package com.clu.quickanswer

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.clu.quickanswer.interaction.AnswerInteraction
import com.clu.quickanswer.ocr.ArabicOcr
import com.clu.quickanswer.ocr.CalibratedKeypad
import com.clu.quickanswer.ocr.ChoiceTouchGuard
import com.clu.quickanswer.ocr.ColorLayout
import com.clu.quickanswer.ocr.GameVisualLayout
import com.clu.quickanswer.ocr.LatinOcr
import com.clu.quickanswer.ocr.LayoutReplyParser
import com.clu.quickanswer.ocr.LayoutTemplate
import com.clu.quickanswer.ocr.LearningGate
import com.clu.quickanswer.ocr.OptionWordDetector
import com.clu.quickanswer.ocr.PictureRegion
import com.clu.quickanswer.ocr.PxRect
import com.clu.quickanswer.ocr.Question
import com.clu.quickanswer.ocr.ScreenFingerprint
import com.clu.quickanswer.ocr.ScreenLayout
import com.clu.quickanswer.ocr.TemplateLearner
import com.clu.quickanswer.ocr.TemplateReader
import com.clu.quickanswer.ocr.TemplateState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads the question on a capture and answers it, cheapest step first (see docs/ARCHITECTURE.md): the
 * on-device readers, the answer memory, then the cloud model. It owns the readers and the answer
 * provider, and the background learning of new layouts. [OverlayService] decides when to ask and what
 * to show; [isDestroyed] tells the loaders to close a reader that finishes loading after the stop.
 */
internal class AnswerPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isDestroyed: () -> Boolean,
) {

    companion object {
        // A reply for learning a layout is a quiz option or a number, which is short; a sentence means
        // a document or an app, not a quiz screen worth a layout request.
        private const val MAX_LEARN_REPLY_CHARS = 40
        private const val SHOWN_IMAGE_MAX_SIDE = 1024
    }

    private val destroyed: Boolean get() = isDestroyed()

    // The answer provider for the settings it was made from (clientKey); see currentClient().
    @Volatile var client: Answerer? = null
        private set
    private var clientKey: String? = null
    private val clientLock = Any()

    // On-device question reader; loaded in the background when the service starts (null until then,
    // or if it cannot load on this phone). Every use holds ocrLock, so it is never closed mid-read.
    val ocrLock = Any()
    var ocr: ArabicOcr? = null
        private set
    private val latinOcrLock = Any()
    private val colorLayout = ColorLayout()

    // When a layout request may go out in the background (see learnLayout).
    private val learningGate = LearningGate()
    private var latinOcr: LatinOcr? = null

    /**
     * The answer provider for the current settings. Rebuilt when the provider, key or model was
     * changed in the settings screen (saved when it is left), so the change applies to the running
     * bubble without a restart. Any thread.
     */
    fun currentClient(): Answerer {
        val claude = Prefs.provider(context) == "claude"
        val key = if (claude) {
            "claude|${Prefs.claudeKey(context)}|${Prefs.claudeModel(context)}"
        } else {
            "gemini|${Prefs.apiKey(context)}|${Prefs.model(context)}"
        }
        synchronized(clientLock) {
            val existing = client
            if (existing != null && key == clientKey) return existing
            val fresh: Answerer = if (claude) {
                ClaudeClient(Prefs.claudeKey(context), Prefs.claudeModel(context))
            } else {
                GeminiClient(Prefs.apiKey(context), Prefs.model(context))
            }
            client = fresh
            clientKey = key
            return fresh
        }
    }

    /**
     * A result watch for [answer] to [q], or null. Only for a multiple-choice question read on the
     * device with a button for every option: the colour the game shows has to be tied to an option
     * on the screen, and a number or a question that was not read has none.
     */
    private fun watchFor(q: Question?, answer: String): ResultWatch? =
        if (q != null && !q.numeric && q.optionRects.size == q.options.size) ResultWatch(q, answer) else null

    /**
     * Answers the question on [bmp] (blocking; run off the main thread). [masks] are the places where
     * our own windows were painted black over it.
     * 1. Read it on the device. If that works: answer from memory when the question was seen before
     *    (unless it is [bypassKey], the answer being corrected), else ask the model with text only and
     *    remember the answer. A question about a picture skips this: its words do not decide it. When
     *    the game's picture is shown (see [DeviceRead.picture]) the whole screenshot answers it
     *    (the faster measured path), and it is remembered per picture, whatever its words say.
     * 2. Otherwise, or when the text answer cannot be used, send the screenshot as before. If the
     *    question was read, an image answer that names one of its options still gets the box and is
     *    remembered. A correction goes straight here: the text request would get the same reply again.
     *
     * Both requests share [deadline]: when it runs out they answer with the timeout error, which ends
     * the question (see [Reply.Kind.ERROR]).
     */
    fun solve(bmp: Bitmap, masks: List<PxRect>, bypassKey: String?, deadline: AnswerDeadline): Solved {
        val c = currentClient()
        val memory = AnswerStore.memory
        val read = if (Prefs.smartReading(context)) readQuestion(bmp, masks) else DeviceRead(null)
        val q = read.question
        val picture = if (q != null) read.picture else null
        fun resolved(
            r: AnswerParser.Resolved, image: Bitmap?, source: AnswerSource,
            reply: String = r.answer, indexRequested: Boolean = false,
            key: String? = null,
        ): Solved = Solved(
            if (source == AnswerSource.MEMORY) "⚡ ${r.answer}" else r.answer,
            r.rect, image, key, true, source,
            watch = if (picture == null) watchFor(q, r.answer) else null,
            request = q?.let { AnswerInteraction.Request(it, r.answer, reply, indexRequested,
                literalAnswer = source == AnswerSource.MEMORY || source == AnswerSource.CALCULATOR) },
            rawReply = reply, pictureRect = picture?.rect,
        )
        fun canRemember(r: AnswerParser.Resolved, reply: String, indexRequested: Boolean): Boolean =
            q != null && (q.numeric || AnswerInteraction.matchesChoice(reply, r.answer, q.options, indexRequested))
        // The cheapest rung of the answer ladder: arithmetic is computed here, before the memory and the model.
        if (q != null) Calculator.answer(q)?.let { r -> return resolved(r, shownCopy(bmp), AnswerSource.CALCULATOR) }
        // A shown picture decides the question, whatever its words say: never the text-only request.
        val wordsDecide = q != null && picture == null && !QuestionPrompt.refersToPicture(q.questionText)
        var corrected: AnswerMemory.Hit? = null
        if (q != null && picture != null) {
            val hit = memory.lookup(q.questionText, q.options, q.numeric, picture.hash)
            if (hit != null && hit.key == bypassKey) {
                corrected = hit
            } else {
                if (hit != null) {
                    AnswerParser.resolveStored(q, hit.answer)?.let { r ->
                        return resolved(r, shownCopy(bmp), AnswerSource.MEMORY, reply = hit.answer, key = hit.key)
                    }
                }
                // No cropped-picture request: on the owner's phone it took 5.5 s on average against 2.4 s
                // for the whole screenshot (Oct 2026 statistics), so the screenshot below answers it and
                // its answer is remembered for this picture.
            }
        }
        if (q != null && wordsDecide) {
            val hit = memory.lookup(q.questionText, q.options, q.numeric)
            if (hit != null && hit.key == bypassKey) {
                corrected = hit
            } else {
                if (hit != null) {
                    AnswerParser.resolveStored(q, hit.answer)?.let { r ->
                        return resolved(r, shownCopy(bmp), AnswerSource.MEMORY, reply = hit.answer, key = hit.key)
                    }
                }
                val reply = c.answerText(QuestionPrompt.build(q), deadline)
                when (reply.kind) {
                    Reply.Kind.ANSWER -> AnswerParser.resolve(q, reply.text)?.let { r ->
                        if (canRemember(r, reply.text, true)) memory.put(q.questionText, q.options, q.numeric, r.answer)
                        return resolved(r, shownCopy(bmp), AnswerSource.TEXT, reply.text, indexRequested = true)
                    }
                    // Quota, key, region, no connection: the image request would fail the same way.
                    Reply.Kind.ERROR -> return Solved(
                        reply.text, null, null, null, isAnswer = false, source = AnswerSource.FAILED,
                    )
                    Reply.Kind.EMPTY -> {}
                }
            }
        }
        // Cheap signals only (legacyVisibleKind stays in the retry below): the short game prompt when
        // the device read the question or knows the layout, the general one for any other screen.
        val prompt = if (q != null || read.candidate != null || read.fits || read.colorLayout) {
            ImagePrompt.GAME
        } else ImagePrompt.GENERAL
        val firstImageReply = c.answer(bmp, prompt, deadline)
        val fallbackKind = if (q == null && firstImageReply.kind == Reply.Kind.ANSWER &&
            ScreenImage.isNoQuestion(firstImageReply.text)) legacyVisibleKind(bmp) else null
        val imageReply = if (fallbackKind != null) {
            val focused = ScreenImage.focusQuiz(bmp, fallbackKind == GameVisualLayout.Kind.NUMERIC_WHITE)
            try {
                val retry = c.answer(focused, ImagePrompt.GAME, deadline)
                if (retry.isAnswer) retry else firstImageReply
            } finally { if (focused !== bmp) focused.recycle() }
        } else firstImageReply
        val text = imageReply.text
        // An error message is never matched against the options (or taken for a number).
        if (q != null && imageReply.kind == Reply.Kind.ANSWER) {
            val r = if (q.numeric) {
                // Only a bare number counts (an error message can contain digits too).
                (AnswerParser.numberOnly(text) ?: AnswerParser.numberOnly(AnswerParser.stripLoneFirstLabel(text)))
                    ?.let { AnswerParser.Resolved(it, null) }
            } else {
                AnswerParser.resolveImageAnswer(q, text)
            }
            if (r != null) {
                if ((wordsDecide || picture != null) && canRemember(r, text, false)) {
                    // The entry that was corrected goes, but only if it was this question's own: a fuzzy hit
                    // belongs to another question, whose answer is still right for it.
                    if (corrected != null && corrected.exact) memory.remove(corrected.key)
                    memory.put(q.questionText, q.options, q.numeric, r.answer, picture?.hash)
                }
                // Not for a question about a picture: the memory is never read for it (words do not decide it).
                return resolved(r, c.lastSent, AnswerSource.IMAGE, text).copy(
                    watch = if (wordsDecide) watchFor(q, r.answer) else null)
            }
        }
        read.candidate?.let { (id, cq) ->
            val check = CandidateCheck.judge(cq, imageReply.isAnswer, text)
            val now = System.currentTimeMillis()
            when (check.verdict) {
                CandidateCheck.Verdict.CONFIRMED -> TemplateStore.templates.recordSuccess(id, now)
                CandidateCheck.Verdict.REFUTED -> TemplateStore.templates.recordFailure(id, now)
                CandidateCheck.Verdict.UNKNOWN -> {}
            }
            // The answer came from the screenshot; the candidate only says where its button is.
            check.resolved?.let { r ->
                return Solved(r.answer, r.rect, c.lastSent, null, isAnswer = true, source = AnswerSource.IMAGE, rawReply = text)
            }
        }
        val source = if (imageReply.isAnswer) AnswerSource.IMAGE else AnswerSource.FAILED
        // The options were read on the device and this reply is none of them: say so on the panel
        // instead of showing it as if it were an option (the owner saw "words that are not among
        // the options", 26 Oct 2026). Nothing is tapped or remembered for it either way.
        val offOptions = q != null && !q.numeric && imageReply.isAnswer && !ScreenImage.isNoQuestion(text)
        if (offOptions) DiagLog.add("الجواب مش من الخيارات المقروءة: «${text.take(40)}» / " + q!!.options.joinToString("|").take(80))
        return Solved(
            if (offOptions) "غير مؤكد: $text" else text, null, c.lastSent, null, imageReply.isAnswer, source,
            learnFrameFor(read, bmp, masks, c, imageReply, text), rawReply = text,
        )
    }

    private fun legacyVisibleKind(bitmap: Bitmap): GameVisualLayout.Kind? = try {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val visual = GameVisualLayout.detect(pixels, w, h)
        if (visual != null && visual.kind != GameVisualLayout.Kind.CHOICE_GRID) visual.kind else {
            val choices = OptionWordDetector.detectChoices(pixels, w, h)
            if (choices != null && ChoiceTouchGuard.isReady(pixels, w, h, choices.buttons, choices.words))
                GameVisualLayout.Kind.CHOICE_GRID else null
        }
    } catch (_: Throwable) { null }

    /**
     * A copy of [bmp] to learn its layout from, or null. Only for a screen the templates were tried
     * on (not the original game, which [ColorLayout] reads), that none of them fits, that the model
     * answered (not an error or "no question"), and only when [learningGate] lets a request go.
     */
    private fun learnFrameFor(read: DeviceRead, bmp: Bitmap, masks: List<PxRect>, c: Answerer, reply: Reply, text: String): LearnFrame? {
        val fp = read.fingerprint ?: return null
        if (read.fits || read.question != null || !reply.isAnswer || ScreenImage.isNoQuestion(text)) return null
        // Several answers, "unsure" or a sentence: not a quiz screen a layout can be learned from.
        if (!ReplyShape.isSingle(text) || text.trim().length > MAX_LEARN_REPLY_CHARS) return null
        if (!learningGate.tryStart(fp, SystemClock.elapsedRealtime())) return null
        val copy = try {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            null
        }
        if (copy == null) {
            learningGate.finish(fp, learned = false, now = SystemClock.elapsedRealtime())
            return null
        }
        return LearnFrame(copy, masks, fp, c)
    }

    fun startLearning(f: LearnFrame) {
        scope.launch(Dispatchers.IO) {
            var learned = false
            try {
                learned = learnLayout(f)
            } catch (t: Throwable) {
                // Background work: a failure only means nothing was learned this time.
            } finally {
                f.bitmap.recycle()
                learningGate.finish(f.fingerprint, learned, SystemClock.elapsedRealtime())
            }
        }
    }

    fun dropLearning(f: LearnFrame) {
        f.bitmap.recycle()
        learningGate.finish(f.fingerprint, learned = false, now = SystemClock.elapsedRealtime())
    }

    /**
     * Asks the model where the question and options of [f] are, builds a CANDIDATE template from the
     * reply, and keeps it only when reading the same screen with it gives what the model saw
     * ([TemplateLearner.confirms]). Blocking: run on Dispatchers.IO. True when a template was kept.
     */
    private fun learnLayout(f: LearnFrame): Boolean {
        val answer = f.client.layout(f.bitmap) ?: return false
        val reply = LayoutReplyParser.parse(answer.text) ?: return false
        val w = f.bitmap.width
        val h = f.bitmap.height
        val pixels = IntArray(w * h)
        f.bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val now = System.currentTimeMillis()
        val t = TemplateLearner.build(reply, answer.geometry, pixels, w, h, f.masks, UUID.randomUUID().toString(), now)
            ?: return false
        val layout = TemplateReader.layoutFor(t, pixels, w, h) ?: return false
        val read = readWith(f.bitmap, pixels, w, h, ColorLayout.Result(layout, null), layout, f.masks, t.language)
            ?: return false
        if (!TemplateLearner.confirms(read, reply)) return false
        TemplateStore.templates.add(t)
        return true
    }

    /**
     * Use the existing offline Arabic reader first. If it cannot read this screen,
     * try the separately bundled offline Latin recognizer before sending a full
     * screenshot to the cloud. Each reader must fail closed, never guess text.
     *
     * The screenshot is copied to pixels and analysed ([ColorLayout]) once; both readers share that
     * layout, and a screen that is not the quiz is declined without running either of them. Native
     * failures (missing library, out of memory) are Errors, not Exceptions: they must lead to the
     * screenshot path, never crash the service, hence Throwable.
     */
    private fun readQuestion(bmp: Bitmap, masks: List<PxRect>): DeviceRead {
        // Hardware bitmaps cannot be read pixel by pixel.
        var copy: Bitmap? = null
        try {
            val src = if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { copy = it }
            } else bmp
            if (src == null) return DeviceRead(null)
            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            val inspected = colorLayout.inspect(pixels, w, h)
            val layout = inspected.layout
            if (layout == null) {
                val t = readWithTemplates(src, pixels, w, h, masks)
                DiagLog.add("قراءة: اللعبة الأصلية لا (${inspected.rejection})، القوالب: " + when {
                    t.question != null -> "قُرئ السؤال"
                    t.candidate != null -> "قالب مرشّح قرأه"
                    t.fits -> "قالب يطابق لكن ما انقرأ"
                    else -> "لا يوجد"
                })
                return t
            }
            val q = readWith(src, pixels, w, h, inspected, layout, masks, null)
            DiagLog.add("قراءة: اللعبة الأصلية، " + when {
                q == null -> "الشكل معروف لكن النص ما انقرأ"
                q.numeric -> "سؤال رقم"
                else -> "سؤال ${q.options.size} خيارات"
            })
            return DeviceRead(q, colorLayout = true)
        } catch (t: Throwable) {
            DiagLog.add("قراءة: خطأ ${t.javaClass.simpleName}")
            return DeviceRead(null)
        } finally {
            copy?.recycle()
        }
    }

    /**
     * Reads [language] ("ar": the Arabic reader only, "en": the Latin one only, null: Arabic and
     * then Latin) on a layout that [ColorLayout] or a template gave. Each reader runs in its own
     * try/catch under its own lock and a failure counts as "could not read".
     */
    private fun readWith(
        src: Bitmap,
        pixels: IntArray,
        w: Int,
        h: Int,
        inspected: ColorLayout.Result,
        layout: ScreenLayout,
        masks: List<PxRect>,
        language: String?,
    ): Question? {
        // Anything but "ar"/"en" (an old or damaged file) reads with both.
        val lang = language?.takeIf { it == "ar" || it == "en" }
        if (lang == null || lang == "ar") {
            val arabic = try {
                synchronized(ocrLock) { ocr?.read(pixels, w, h, inspected, masks) }
            } catch (t: Throwable) {
                null
            }
            if (arabic != null || lang == "ar") return arabic
        }
        return try {
            synchronized(latinOcrLock) { latinOcr?.read(src, layout, masks) }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Reads a screen that [ColorLayout] declined with a learned layout. Only TRUSTED templates are
     * used to read. A template that does not fit the screen is not a failure (another mode or screen
     * of the game), but a fitting template whose reading is declined is, and counts against it.
     *
     * When no trusted template reads it, the first fitting CANDIDATE that reads it is returned as
     * [DeviceRead.candidate]: it is checked against the model's answer ([CandidateCheck]), which is
     * how candidates earn their way to TRUSTED.
     */
    private fun readWithTemplates(src: Bitmap, pixels: IntArray, w: Int, h: Int, masks: List<PxRect>): DeviceRead {
        val fp = ScreenFingerprint.of(pixels, w, h, masks)
        val templates = TemplateStore.templates
        var fits = false
        for (t in templates.find(fp, null, trustedOnly = false)) {
            if (!TemplateReader.fits(t, pixels, w, h)) continue
            fits = true
            val now = System.currentTimeMillis()
            val tl = TemplateReader.layoutFor(t, pixels, w, h)
            if (tl == null) {
                templates.recordFailure(t.id, now)
                continue
            }
            val q = readWith(src, pixels, w, h, ColorLayout.Result(tl, null), tl, masks, t.language)
            if (q == null) {
                templates.recordFailure(t.id, now)
                continue
            }
            // A candidate's reading is judged later, against the answer; only a trusted one answers.
            if (t.state != TemplateState.TRUSTED) return DeviceRead(null, t.id to q, fp, fits = true)
            templates.recordSuccess(t.id, now)
            return DeviceRead(q, null, fp, fits = true, picture = shownPicture(t, pixels, w, h))
        }
        return DeviceRead(null, null, fp, fits)
    }

    /**
     * The picture the question on this screen shows, when [t] says where its game pins one
     * ([LayoutTemplate.picture]) and one is pinned now ([PictureRegion.isShown] on the right half of that
     * rect, which is plain background otherwise). Null for a template without a picture rect, which is
     * every template but the parchment grid, and for a question without a picture.
     */
    private fun shownPicture(t: LayoutTemplate, pixels: IntArray, w: Int, h: Int): ShownPicture? {
        val rect = t.picture?.toPx(w, h) ?: return null
        if (!PictureRegion.isShown(pixels, w, h, PictureRegion.detectArea(rect))) return null
        return ShownPicture(rect, PictureRegion.hash(pixels, w, h, rect))
    }

    /**
     * Load the bundled English OCR alongside Arabic without blocking service startup. It is warmed up
     * on the IO thread before it is published, so the first real question does not pay for model loading.
     */
    fun loadLatinOcr() {
        scope.launch(Dispatchers.IO) {
            val loaded = try {
                LatinOcr().also { it.warmUp() }
            } catch (t: Throwable) {
                null
            }
            synchronized(latinOcrLock) {
                if (destroyed) loaded?.close() else latinOcr = loaded
            }
        }
    }

    /** A smaller copy of [bmp] for "آخر صورة" (the caller recycles [bmp]). */
    private fun shownCopy(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= SHOWN_IMAGE_MAX_SIDE) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val s = SHOWN_IMAGE_MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
    }

    /**
     * Loads the on-device reader in the background. Without it (it cannot be loaded) the app still
     * works by sending the picture. The APK carries the 64-bit ARM libraries only, so a phone
     * without them cannot install it at all (documented in the README).
     */
    fun loadOcr() {
        scope.launch(Dispatchers.IO) {
            val loaded = try {
                ArabicOcr.create(context)
            } catch (t: Throwable) {
                null
            }
            synchronized(ocrLock) {
                if (destroyed) loaded?.close() else ocr = loaded
            }
        }
    }

    /** Closes the readers, off the main thread (the service's teardown). */
    fun closeReaders() {
        // A read in progress holds the lock until it is done (it cannot be interrupted), so taking it
        // here would freeze the main thread for as long as one read takes. Another thread waits for it
        // and closes the reader (native) once nothing uses it; a reader that finishes loading
        // meanwhile finds [destroyed] set and closes itself.
        Thread {
            val reader = synchronized(ocrLock) { ocr.also { ocr = null } }
            val latinReader = synchronized(latinOcrLock) { latinOcr.also { latinOcr = null } }
            reader?.close()
            latinReader?.close()
        }.start()
    }
}

/**
 * What one answer produced: the text for the bar, the option's button to box (screen pixels),
 * the picture "آخر صورة" shows, and the memory entry it came from (null if not from memory).
 * [isAnswer]: the text is an answer (from memory or the model), not an error or "no question";
 * only an answer gets a haptic tick (see [Reply.isAnswer]).
 * [source]: which step produced it, for the statistics; no default, so a new path cannot forget it.
 */
internal data class Solved(
    val text: String,
    val rect: PxRect?,
    val image: Bitmap?,
    val memoryKey: String?,
    val isAnswer: Boolean,
    val source: AnswerSource,
    /** A screen to learn the layout of once the answer is shown (see [learnLayout]), or null. */
    val learn: LearnFrame? = null,
    /** What to watch the game's result screen for after this answer (see [watchResult]), or null. */
    val watch: ResultWatch? = null,
    val request: AnswerInteraction.Request? = null,
    val rawReply: String = text,
    val pictureRect: PxRect? = null,
    val touches: List<PxRect>? = null,
    val guardRect: PxRect? = null,
    val guardStamp: IntArray? = null,
    val numericLayout: GameVisualLayout.Detected? = null,
    /** The pad the user drew, when no built-in adapter knows the game (see [calibratedTouches]). */
    val calibratedKeys: CalibratedKeypad.Keys? = null,
    val submitLast: Boolean = false,
    val choiceButtons: List<PxRect> = emptyList(),
    val choiceWords: List<PxRect> = emptyList(),
    val choiceStamps: List<IntArray> = emptyList(),
    val choiceFromLayout: Boolean = false,
    val ignoredRects: List<PxRect> = emptyList(),
    val pictureStamp: IntArray? = null,
    val fieldRect: PxRect? = null,
    val fieldStamp: IntArray? = null,
    val expectedNumber: String? = null,
    val touchFailure: String? = null,
)

/**
 * What a result watch needs: the multiple-choice [question] as read on the device (its option
 * texts and buttons) and the [answer], the option's text that was shown for it.
 */
internal class ResultWatch(val question: Question, val answer: String)

/**
 * A copy of a screen no reader or template could read, kept to ask [client] for its layout in
 * the background. [bitmap] is ours: [startLearning] or [dropLearning] recycles it.
 */
internal class LearnFrame(val bitmap: Bitmap, val masks: List<PxRect>, val fingerprint: String, val client: Answerer)

/**
 * What the device read: [question] (null when nothing could be read), a [candidate] template's
 * reading (only to be checked against the model's answer, never used to answer), the screen's
 * [fingerprint] when templates were tried (null when [ColorLayout] took the screen), and
 * whether some template [fits] the screen (then there is nothing new to learn). [picture] is the
 * picture the question shows, only for a question a TRUSTED template read on a screen where it
 * pins one (see [shownPicture]); null for everything else.
 */
internal class DeviceRead(
    val question: Question?,
    val candidate: Pair<String, Question>? = null,
    val fingerprint: String? = null,
    val fits: Boolean = false,
    val picture: ShownPicture? = null,
    /** True when [ColorLayout] read the screen (the original game). */
    val colorLayout: Boolean = false,
)

/** The picture a question shows: where it is in the capture (pixels) and its [PictureRegion.hash]. */
internal class ShownPicture(val rect: PxRect, val hash: String)
