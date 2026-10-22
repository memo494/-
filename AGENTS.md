# Working on Clu (rules for coding agents)

Clu is an Android app (Kotlin, minSdk 26, arm64 only) that reads a quiz question off the screen and
shows the answer in an overlay. It reads on the device first (Arabic ONNX recogniser, ML Kit for
English), answers from a local memory of past questions, and only then asks a cloud model (Gemini or
Claude) with text or the screenshot.

Two agents work on this repository, and the owner passes links between them:

- **Planner (Claude)** writes each task as a GitHub issue: what and why, the files to touch, the
  classes and functions with their signatures, and acceptance criteria. It usually pushes the tests
  (and sometimes skeleton files with `TODO()` bodies) to the task's branch before you start.
- **Implementer (you)** implements the issue on the branch the issue names, until every test passes,
  then opens a pull request to `main` that says `Closes #<issue>`. The planner reviews the PR on
  GitHub; fix the review comments on the same branch.

## Rules

1. Work only on the branch named in the issue. Never push to `main`. One task per branch.
2. The tests in the issue are the contract. Do not edit, weaken or delete them. If a test looks
   wrong, leave it, and explain why in the PR description.
3. Stay inside the issue's scope and files. If the design looks wrong or something is missing, say so
   in the PR description instead of redesigning. `OverlayService.kt` is shared by many features:
   change it only when the issue asks for it.
4. Every change comes with unit tests (JUnit 4, `app/src/test/...`). Tests must not need a device,
   the network or API keys. Images in tests are synthetic (see `ocr/SyntheticScreens.kt`).
5. Never commit screenshots or photos of real users or players (the repository is public). Never
   commit API keys or keystores.
6. Before opening the PR, run and pass:
   - `./gradlew testDebugUnitTest`
   - `./gradlew assembleDebug`
7. No new dependencies unless the issue allows them.
8. Code style: follow the surrounding code. Kotlin, concise KDoc that explains *why*, constants
   named and commented with where their values come from, fail closed (decline and fall back to the
   screenshot path rather than guess). Never block the main thread; never let a reader failure
   (including `Error`s such as `OutOfMemoryError`) crash the service.
9. User-facing strings are Arabic, as in the existing code.

## Layout (main parts)

- `OverlayService.kt`: capture, the answer flow (`solve`), the overlay windows.
- `ocr/`: `ColorLayout` (the original game), `ScreenReader` (Arabic), `LatinOcr` (English),
  layout templates (`ScreenFingerprint`, `TextLines`, `LayoutTemplate`, `TemplateReader`,
  `LayoutTemplates`), and their tests.
- `AnswerMemory.kt` / `AnswerStore.kt`, `TemplateStore.kt`: what is remembered on the device.
- `GeminiClient.kt`, `ClaudeClient.kt` / `ClaudeApi.kt`: the cloud models.
