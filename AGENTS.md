# Working on Clu (rules for every coding agent)

`CLAUDE.md` and this file carry the same rules. Change both together.

Clu is an Android app (Kotlin, minSdk 26, arm64 only) that reads a quiz question off the screen and
shows the answer in an overlay. It reads on the device first (Arabic ONNX recogniser, ML Kit for
English), answers from a local memory of past questions, and only then asks a cloud model (Gemini or
Claude) with text or the screenshot. Optionally it taps the answer through an accessibility service.

Read `docs/ARCHITECTURE.md` (one-page map) before exploring code, `docs/ROADMAP.md` for status and
`docs/DECISIONS.md` before re-opening a design question.

## Who does what

- **Claude** plans and implements: one problem per branch, one small pull request to `main`, with a
  test that fails without the fix.
- **Codex** reviews: every pull request gets an `@codex review` comment. Only confirmed findings
  (a real input or path that fails) are fixed; guesses and style preferences are not.
- A pull request is merged only when CI is green and the review is answered. If Codex has not
  replied within 20 minutes, it may be merged on a green CI, saying so on the PR.
- Codex implements only when Claude hands it a task (an issue, a task branch or a review on it,
  authorized by the owner): it follows that scope, works on the task's branch, never pushes to
  `main`, and reports on the task.
- The owner tests the release APK on the phone. Large changes to the app's structure are asked first.

## Layout

All sources are under `app/src/main/java/com/clu/quickanswer/`.

| Area | Files |
|---|---|
| Service lifecycle, capture, overlay windows, answer flow, auto mode | `OverlayService.kt` (being split) |
| Automatic touch (planning, guards, numeric entry) | `interaction/`, `TapSequence.kt`, `TouchPlanner.kt`, `VolumeKeyService.kt` |
| Settings UI and prefs | `MainActivity.kt`, `Prefs.kt`, `res/layout/activity_main.xml`, `CalibrationActivity.kt` |
| Cloud models | `Answerer.kt`, `GeminiClient.kt`, `GeminiModels.kt`, `GeminiQuota.kt`, `ClaudeClient.kt`, `ClaudeApi.kt` |
| Prompts and replies | `QuestionPrompt.kt`, `ScreenImage.kt`, `AnswerParser.kt`, `Calculator.kt` |
| Memory and stores | `AnswerMemory.kt`, `AnswerStore.kt`, `TemplateStore.kt`, `StatsStore.kt` |
| On-device reading | `ocr/` (`ColorLayout`, `ScreenReader`, `LatinOcr`, layout templates, numeric field and keypad) |
| Tests | `app/src/test/java/...` (JUnit 4, JVM only); synthetic images in `ocr/SyntheticScreens.kt` |

Pure files (no `android.*`) carry the logic and are unit-tested; Android files stay thin.

## Build and test

The Android SDK path goes in `local.properties` (`sdk.dir=...`, never committed).

```
./gradlew testDebugUnitTest      # all unit tests (also runs the OCR model on the sample screenshots)
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest --tests 'com.clu.quickanswer.ocr.ColorLayoutTest'   # one class
```

CI (`.github/workflows/android.yml`) runs the unit tests and builds the APK on every pull request.
A release (`Clu.apk` on `releases/latest`) is published only by running the workflow by hand on `main`.

## Must not break

1. **Fail closed.** A reader, template or guard that is not sure declines and lets a later, more
   expensive step answer. Never guess text, positions or numbers. A touch happens only on a verified
   reading of the current screen; a numeric field is never cleared or backspaced, and an occupied or
   unknown field stops the touch.
2. **Never block the main thread** (no full-screen copies, OCR, file or network work on it), and never
   let a reader failure, including `Error`s such as `OutOfMemoryError`, crash the service.
3. **An answer never hangs.** The whole answer (reading, memory, network) has a time budget (10 s);
   after it the bubble is free again and the panel says so.
4. **Answer memory stays exact.** Negations, numbers and arithmetic signs must match, so "is" never
   reuses the answer of "is not"; a question with a picture also needs the same picture.
5. **Installs over the previous version.** `applicationId` `com.clu.quickanswer`, the signing setup
   and the ever-growing `versionCode` stay; beta builds keep their `.beta` suffix.
6. **arm64 only; onnxruntime pinned.** `onnxruntime-android` and the desktop `onnxruntime` used by
   tests stay on the same version.
7. **Privacy.** Never commit screenshots or photos of real players or users (the repository is
   public), API keys or keystores. The app never writes captures to disk on its own.
8. **User-facing text is Arabic** and every layout works right-to-left.

## Rules for changes

1. One problem per branch and pull request; never push to `main`.
2. Every fix comes with a JUnit 4 test that fails without it. Tests must not need a device, the
   network or API keys. Never weaken, skip or delete existing tests to get green.
3. Before pushing: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass.
4. No new dependencies without saying why in the pull request.
5. Style: follow the surrounding code. Kotlin, short KDoc that explains *why*, named constants that
   say where their values come from.
