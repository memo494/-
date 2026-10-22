# Claude Code → Codex through GitHub

Claude Code can request reviews or scoped implementation work using the owner's authenticated
GitHub account. These comments appear as the owner and use the connected account's Codex usage.
They start separate Codex work, not a continuation of a ChatGPT conversation.

## One-time account setup

1. Connect `memo494/-` in [Codex settings](https://chatgpt.com/codex/settings).
   Enable code review for the repository; enable automatic reviews if desired.
2. For implementation/fix requests, create a repository environment in
   [Codex cloud environments](https://chatgpt.com/codex/cloud/settings/environments).
   PR #8 received this exact setup requirement from `chatgpt-codex-connector[bot]`.
   GitHub access alone does not complete this step. Repository files cannot create an account environment.
3. In Claude Code's own terminal, `gh auth status` must succeed with access to this repository.
   Authentication in another machine or ChatGPT session does not authenticate Claude Code's machine.

The Android environment needs a full JDK (17 matches CI), Android SDK platform 35 and build tools
34.0.0/35.0.0. Set `ANDROID_HOME` and write `sdk.dir` into ignored `local.properties`.
Allow setup-time dependency downloads. Validate with
`./gradlew --no-daemon testDebugUnitTest assembleDebug`; preserve its exit status (no `|| true`).
Do not put API keys or signing credentials in setup scripts. Unit tests need neither.

## Review

In Claude Code, run `/codex-review <PR-number>`. It inspects the PR before posting
`@codex review` once. Use a top-level PR conversation comment, not an issue-only request.
Automatic review is optional; manual requests still require code review to be enabled.

## Implementation and fixes

Prepare an issue with exact files, signatures, contract tests and acceptance criteria. Push its
tests/skeleton to the issue's named branch and open a draft PR to `main`. Then post one comment:

```text
@codex Implement issue #<issue> on this PR's branch, following AGENTS.md.
Allowed files: <paths>. Contract tests: <paths>; do not change them.
Acceptance: ./gradlew testDebugUnitTest and ./gradlew assembleDebug.
Push the fix to this same branch. Do not merge.
```

Inspect the response and resulting commits. If Codex requests an environment or account setup,
report that blocker with its link. Do not repeatedly mention the bot. Review the final diff and CI;
the owner decides when to merge. Batch follow-up findings in a single scoped request.

Official behavior and setup: [Codex on GitHub](https://learn.chatgpt.com/docs/third-party/github).
