# Architecture (one page; read this instead of scanning the code)

Clu shows the answer to the quiz question on screen in an overlay. Everything is decided per
screenshot, cheapest step first; every step **fails closed** (declines and lets a later, more
expensive step answer) rather than guessing.

## The answer flow (`OverlayService.solve`)

```
capture (MediaProjection) ─► our own windows painted black = masks
  │
  ├─ readQuestion: on-device OCR, no network
  │    1. ColorLayout.inspect  → the original game's look (cream banner, white vertical buttons)
  │    2. else layout templates → ScreenFingerprint.of → LayoutTemplates.find (TRUSTED)
  │                               → TemplateReader.fits → TemplateReader.layoutFor
  │    then ScreenReader (Arabic, ONNX) and/or LatinOcr (English, ML Kit) on that ScreenLayout
  │    → Question(text, options, rects, numeric) or null
  │
  ├─ question read: AnswerMemory.lookup → hit: answer at once ("⚡", no tokens)
  │                 miss: QuestionPrompt → Answerer.answerText (short text request) → remember
  │    a question that SHOWS A PICTURE (template with `picture`, PictureRegion.isShown) is looked up and
  │    remembered per picture (PictureRegion.hash), and asked with the picture only:
  │    QuestionPrompt.buildPicture → Answerer.answerPicture (512 px crop, one request)
  │
  └─ not read (or text reply unusable): Answerer.answer(screenshot) (image request)
```

The answer is shown in a panel; for a read multiple-choice question a green box marks the option.

On the automatic-touch feature branch, one preparation step after `solve` binds every answer source
to pure `interaction/AnswerInteraction` pattern planners. Reader/template geometry supplies choices;
verified keypad adapters supply numeric controls. Live question/picture/option guards precede each
stroke, and `TapSequence.beforeSubmit` waits for `NumericFieldReader`/`NumericEntryCheck` off the main
thread before numeric confirmation. See `INTERACTIONS.md` for supported patterns and limits.

## Learning (in progress, see ROADMAP)

- **Layout templates**: a game's question/option/number-field boxes as fractions of the screen,
  plus each region's background signature. Learned from the cloud model's boxes
  (`ocr/LayoutLearning.kt`: `LayoutPrompt` → `LayoutReplyParser` → `TemplateLearner`), kept as
  CANDIDATE, confirmed by later answers, TRUSTED after 3 confirmations, demoted after 5 failures.
- **Answer memory**: question + option set (order-free) → answer text; negations, numbers and
  arithmetic signs must match too, so "is" never reuses the answer of "is not". A question with a
  picture also needs the same picture (16x16 average hash, within 24 of 256 bits).

## Module map (`app/src/main/java/com/clu/quickanswer`)

| Area | Files |
|---|---|
| Service, capture, overlay windows, answer flow | `OverlayService.kt` (large; split planned) |
| Settings UI / prefs | `MainActivity.kt`, `Prefs.kt`, `res/layout/activity_main.xml` |
| Volume-key trigger (accessibility) | `VolumeKeyService.kt` |
| Cloud models | `Answerer.kt` (interface, `Reply`), `GeminiClient.kt`, `GeminiModels.kt`, `GeminiQuota.kt`, `GeminiRuntime.kt`, `ClaudeClient.kt`, `ClaudeApi.kt` (pure, tested) |
| Prompts and replies | `QuestionPrompt.kt`, `ScreenImage.kt` (image prompt, crop/scale), `AnswerParser.kt` |
| Memory | `AnswerMemory.kt` (pure), `AnswerStore.kt` (file) |
| Templates | `ocr/ScreenFingerprint.kt`, `ocr/TextLines.kt`, `ocr/LayoutTemplate.kt`, `ocr/TemplateReader.kt`, `ocr/LayoutTemplates.kt`, `TemplateStore.kt` (file), `ocr/LayoutLearning.kt`, `ocr/PictureRegion.kt` (is a picture pinned, its hash) |
| Arabic OCR | `ocr/ColorLayout.kt`, `ocr/ScreenReader.kt`, `ocr/LineRecognizer.kt`, `ocr/Ctc.kt`, `ocr/OrtCtcModel.kt`, `ocr/ArabicOcr.kt`, `ocr/ArabicText.kt`, `ocr/ImageOps.kt`, `ocr/OverlayCheck.kt`, assets `ocr/inference.onnx` + `dict.txt` |
| English OCR | `ocr/LatinOcr.kt` (ML Kit, bundled), `ocr/LatinQuestionAssembler.kt` (pure) |
| Small helpers | `FrameSignature.kt` (auto mode: did the screen change), `HiddenAnswer.kt`, `PanelPlacement.kt`, `Haptics.kt` |

Pure files (no `android.*`) carry the logic and are unit-tested on the JVM; Android files stay thin.

## Threads and safety

- UI and windows on the main thread; capture reading, OCR and network on `Dispatchers.IO`.
- Each native reader is used under its own lock and closed on `onDestroy`.
- Reader failures, including `Error`s (OOM, missing native library), return null: never crash.
- Stores (`AnswerStore`, `TemplateStore`) load once on their own thread and save atomically
  (temp file + rename), coalescing bursts of changes.

## Tests

`./gradlew testDebugUnitTest`: pure logic, synthetic images (`ocr/SyntheticScreens.kt`) and the
original game's screenshots (`app/src/test/resources`, OCR model run by `OcrPipelineTest` in CI).
Real screenshots of other games are not in this public repository (they show other players).
