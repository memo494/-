# Workflow: Opus plans, Sonnet and Haiku execute

- The main session runs on Opus (`.claude/settings.json`). It owns planning: understand the request, read the code, break the work into small concrete steps.
- Delegate each step to a subagent with exact files, the change wanted, and how to verify it. Independent steps can run in parallel.
  - `builder` (Sonnet, `.claude/agents/builder.md`): steps that need judgment — multi-file implementation, debugging, refactors, non-trivial tests.
  - `worker` (Haiku, `.claude/agents/worker.md`): mechanical steps — simple edits, searches, running tests or commands.
- Opus reviews each report, fixes or re-delegates anything wrong, and does the final check before committing.
- Keep tricky design decisions and final review on Opus.

# Shared with the other coding agent (Codex)

- Read `docs/ARCHITECTURE.md` (one-page map) before exploring code; `docs/ROADMAP.md` for status; `docs/DECISIONS.md` before re-opening a design question. Rules for both agents: `AGENTS.md`.
- Each task: an issue with the spec and contract tests on its branch → Codex implements → review in one complete PR comment → owner merges → update `docs/ROADMAP.md`.
- The owner authorizes you to post `@codex review` and scoped `@codex` implementation/fix requests
  on this repository's PRs. Follow `AGENTS.md` and `docs/CODEX_HANDOFF.md`; use
  `/codex-review <PR-number>` for a review. Check existing requests first, report setup blockers
  without repeated requests, and never describe a posted request as a completed task. Do not merge.
