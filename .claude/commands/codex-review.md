---
description: Request one Codex review on a Clu pull request
argument-hint: <PR-number>
---

Request a Codex review of PR $ARGUMENTS in `memo494/-`.

1. Read `AGENTS.md` and `docs/CODEX_HANDOFF.md`. Validate the argument is one positive PR number;
   do not interpolate arbitrary argument text into a shell command.
2. Run `gh auth status`. Inspect the PR with `gh pr view <number> --repo memo494/-
   --json state,headRefName,url,comments,reviews`. Continue only for an open PR.
3. Inspect existing requests, reviews and issue-comment reactions using GitHub. If a request for
   the current head is pending or already completed, report its status instead of posting again.
   If the bot reports missing account/environment setup, report that blocker without retrying.
4. Otherwise write `@codex review` to a temporary UTF-8 file, then use
   `gh pr comment <number> --repo memo494/- --body-file <path>` once.
5. Report the PR/comment link and that the review was requested. Do not claim it succeeded until
   a bot review arrives. On the next follow-up, read the response and CI before deciding what to fix.
   Do not merge or enable automatic merge.
