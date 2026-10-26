---
name: gradle-focused-test
description: Runs only the Clu unit tests related to the files just changed, instead of the whole suite. Use after editing Kotlin code in app/src, when checking a fix, or when asked to run the related tests.
---

# Focused Gradle Tests (Clu)

The full suite (`./gradlew testDebugUnitTest`) includes OCR tests that run the ONNX model and take
a while. While working, run only the tests related to the change; run the full suite once at the
end, before committing or opening a PR.

## 1. Find the changed files

```bash
git status --short -- app/src
git diff --name-only main...HEAD -- app/src
```

## 2. Map each file to its tests

Tests live in `app/src/test/java/com/clu/quickanswer/`, mirroring `app/src/main/java/...` and named
`<Class>Test.kt`. Some classes have more than one test file (for example `AnswerMemoryTest` and
`AnswerMemoryPictureTest`), so search instead of guessing:

```bash
# test files named after the class
find app/src/test -name '<Class>*Test.kt'
# test files that use the class
grep -rl '\b<Class>\b' app/src/test --include='*Test.kt'
```

A changed test file is itself a test to run. If a change touches `OverlayService.kt` or another
file with no test of its own, run the tests of the classes it calls.

## 3. Run them

Use the fully qualified class names, one `--tests` per class:

```bash
./gradlew testDebugUnitTest \
  --tests 'com.clu.quickanswer.AnswerMemoryTest' \
  --tests 'com.clu.quickanswer.ocr.TemplateReaderTest'
```

Wildcards work for a whole area: `--tests 'com.clu.quickanswer.ocr.*'`.

## 4. Before committing

Run the full suite and the debug build once:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Notes

- If Gradle can't run here (for example no Android SDK in the environment), say so plainly and
  name the tests that should be run; never claim they passed.
- Don't run the same command again on unchanged code; a second green run adds nothing.
- Never edit, skip or delete a test to make it pass.
