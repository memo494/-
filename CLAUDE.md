# Clu: rules for Claude

Same rules as `AGENTS.md` (shared with Codex); change both together. Below: how Claude works here,
then the shared rules.

## How Claude works on this repository

- Read `docs/ARCHITECTURE.md` (one-page map) before exploring code, `docs/ROADMAP.md` for status and
  `docs/DECISIONS.md` before re-opening a design question.
- The main session (Opus, `.claude/settings.json`) plans, makes design decisions and does the final
  review. Steps go to subagents with exact files, the change and how to verify it:
  `builder` (Sonnet) for judgment work, `worker` (Haiku) for mechanical edits and test runs.
- Each problem: its own branch from `main` → fix + regression test → `./gradlew testDebugUnitTest
  assembleDebug` → pull request → `@codex review` comment → fix confirmed findings only → merge on a
  green CI (after 20 minutes without a Codex reply, merge and say so on the PR) → update
  `docs/ROADMAP.md`.
- Ask the owner before large structural changes or anything unclear; never guess.

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
