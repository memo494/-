# Working on Clu (rules for coding agents)

Clu is an Android app (Kotlin, minSdk 26, arm64 only) that reads a quiz question off the screen and
shows the answer in an overlay. It reads on the device first (Arabic ONNX recogniser, ML Kit for
English), answers from a local memory of past questions, and only then asks a cloud model (Gemini or
Claude) with text or the screenshot.

Before starting a task, read `docs/ARCHITECTURE.md` for the application map,
`docs/ROADMAP.md` for status, and `docs/DECISIONS.md` for settled decisions. Read only the code
needed for the task. Update these documents when the task changes architecture, status or decisions.

Two agents work on this repository, using GitHub issues and pull requests for handoffs:

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

## Claude Code / Codex handoff

- The owner authorizes Claude Code to request Codex reviews and scoped fixes on this repository's
  PRs through GitHub comments. This does not authorize merging or unrelated changes.
- Use `@codex review` for a review. For implementation or fixes, include the issue, named branch,
  exact allowed files, contract tests and acceptance commands in one `@codex` PR comment.
- Read existing comments and reactions before sending a request; do not duplicate an active request.
  A posted comment or an eyes reaction is not proof of completion. Check the resulting review,
  commit and CI before reporting success. Report a setup error once instead of retrying in a loop.
- See `docs/CODEX_HANDOFF.md` for setup and `.claude/commands/codex-review.md` for Claude Code's
  `/codex-review <PR-number>` command. GitHub tasks are separate from the owner's ChatGPT chat.

## Code Review Rules

- Flag readings that silently drop question text or options, accept obscured/clipped text, or
  reuse a cached answer after negation, numbers or the option set change. Uncertain reads must
  fall back; shuffled choices must resolve by answer text to the current option.
- Flag UI-thread OCR/network work and reader failures that can crash the overlay service.
- Keep real user screenshots, API keys and signing keys out of this public repository.

## Layout (main parts)

- `OverlayService.kt`: capture, the answer flow (`solve`), the overlay windows.
- `ocr/`: `ColorLayout` (the original game), `ScreenReader` (Arabic), `LatinOcr` (English),
  layout templates (`ScreenFingerprint`, `TextLines`, `LayoutTemplate`, `TemplateReader`,
  `LayoutTemplates`), and their tests.
- `AnswerMemory.kt` / `AnswerStore.kt`, `TemplateStore.kt`: what is remembered on the device.
- `GeminiClient.kt`, `ClaudeClient.kt` / `ClaudeApi.kt`: the cloud models.
