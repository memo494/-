---
name: codex-handoff
description: Turns a planned Clu task into a GitHub issue plus contract tests on its own branch, ready for Codex to implement. Use when the owner says to hand a task to Codex, write the issue for Codex, or prepare the next roadmap item.
disable-model-invocation: true
---

# Codex Handoff (Clu)

Claude plans, Codex implements (see `AGENTS.md` and `CLAUDE.md`). This skill produces everything
Codex needs so it can work without asking questions: one issue, one branch, and tests that define
"done".

## Before writing anything

1. Read `docs/ARCHITECTURE.md`, `docs/ROADMAP.md` and `docs/DECISIONS.md`. Don't reopen a decision
   that `DECISIONS.md` already settled.
2. Read the files the task touches and one existing test in the same area, so the new tests follow
   the house style.
3. If the scope or the expected behaviour is unclear, ask the owner first. Don't guess.

## 1. Branch

Create one branch per task from the latest `main`, named `task/<short-kebab-name>`.

## 2. Contract tests (pushed to the branch before the issue)

- JUnit 4 in `app/src/test/java/com/clu/quickanswer/...`, beside the existing tests of that area.
- They must run on the JVM: no device, no network, no API keys. Images are synthetic
  (`ocr/SyntheticScreens.kt`); never add real screenshots of players.
- Test behaviour, not internals. Cover the happy path, the fail-closed path (the code declines
  instead of guessing) and at least one edge case.
- If the classes don't exist yet, add skeleton files whose bodies are `TODO()` so the tests compile
  and fail.
- Confirm they compile and fail for the right reason:
  `./gradlew testDebugUnitTest --tests '<TestClass>'`

## 3. The issue

Open one issue with these sections, written for Codex:

```markdown
## What and why
<the user-visible result and the reason, 2-4 sentences>

## Branch
`task/<name>`. Work only here; open the PR to `main` with `Closes #<this issue>`.

## Files
- Change: <paths>
- Create: <paths>
- Don't touch: `OverlayService.kt` (unless listed above), the tests on the branch

## Classes and functions
<exact Kotlin signatures and what each must do>

## Acceptance criteria
- [ ] The tests on the branch pass unchanged: <test class names>
- [ ] `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass
- [ ] Fails closed: <what must happen when reading fails>
- [ ] User-facing strings in Arabic
- [ ] No new dependencies

## Out of scope
<what Codex must not do in this task>
```

## 4. Hand over

Give the owner the issue link. If the owner has agreed to it, also comment `@codex` on the issue
with a one-line pointer to the branch.

## After Codex opens the PR

Review it in one complete PR comment (correctness, tests untouched, scope, fail-closed, main thread
never blocked). After the owner merges, update `docs/ROADMAP.md`.

## Checklist

- [ ] Docs read; no settled decision reopened
- [ ] Branch created from latest `main`
- [ ] Contract tests pushed, compiling and failing for the right reason
- [ ] Issue has every section above, with exact signatures
- [ ] Owner has the issue link
