# Chat handoff system

This directory is the durable work log for ChatGPT development sessions on EnthusiaLoreItems.

The goal is to let a new chat resume from a focused report instead of repeatedly reading the entire repository. Handoffs are navigation and evidence aids, not a replacement for live GitHub state or the binding requirements.

## Files

- `CURRENT.md` is the mutable pointer for the active phase, pull request, latest report, relevant earlier reports, and exact next step.
- `INDEX.md` is the chronological report index.
- Numbered Markdown files are immutable session reports. Never rewrite an old report to make later work appear to have existed earlier.

## Report naming

Use this format:

```text
NNNN-YYYY-MM-DD-pr<NUMBER>-<short-scope>.md
```

Examples:

```text
0001-2026-08-02-pr2-foundation-start.md
0002-2026-08-03-pr2-storage-lifecycle.md
0003-2026-08-04-pr2-ci-review-fixes.md
```

Increment the four-digit sequence across the repository, including later implementation phases and the separate EnthusiaTags integration.

## Startup procedure for a new chat

1. Read `CHATGPT_START_HERE.md` from `main`.
2. Resolve the live `main` head and list open pull requests. This is a small state check, not a whole-repository audit.
3. When an active implementation PR exists, read `handoffs/CURRENT.md` from that PR's head branch.
4. Read the latest numbered report and every earlier report explicitly listed under `Required prior reports` in `CURRENT.md`.
5. Read the active PR description, checks, unresolved review threads, and changed-file list.
6. Inspect only the files and binding-document sections identified by the handoff or needed for the immediate task.
7. Broaden inspection only when the report is missing, contradicted by live state, a new phase is beginning, a security/data-loss risk is suspected, or the PR is being reviewed for completion.

Never trust a handoff blindly. Confirm the active branch, PR status, head SHA, checks, and any claim that materially affects the next write. Do not repeat a full repository review merely because a new chat started.

## Required end-of-session procedure

Before ending a development session that changed code, documentation, PR state, or the next-step decision:

1. Create a new immutable numbered report in this directory.
2. Update `CURRENT.md` to point to it and state the exact next step.
3. Append the report to `INDEX.md`.
4. Update the active PR body when scope, verification, risks, or completion status materially changed.
5. Commit the report and pointer updates to the same active branch. Do not create a separate handoff-only PR.
6. Include the report path in the final response.

A session that only answers a question and makes no repository or workflow change does not need a report.

## Required report structure

Every report must include:

```markdown
# Handoff NNNN — concise title

## Session metadata
- Date/time:
- Phase:
- Repository:
- Branch:
- Pull request:
- Reported implementation head:
- Session status: in progress | ready for review | blocked | merged

## Objective

## Work completed

## Important decisions and invariants

## Files or modules changed

## Persistence, state-machine, or API changes

## Verification actually performed

## Live automation observed

## Unresolved risks or missing evidence

## Exact next step

## Required prior reports
```

Use `Reported implementation head` for the code/documentation head being handed off before the handoff report itself is committed. The following chat must still obtain the current live PR head because adding the report changes the branch SHA.

## Report quality rules

- Separate facts from assumptions.
- List exact commands or checks and their actual outcomes.
- Do not claim CI, Codacy, CodeRabbit, Gradle, Paper, SQLite, or live-server validation without direct evidence.
- State deferred work and failed tool access plainly.
- Link or name the most relevant files instead of summarizing the entire repository.
- Keep the exact next step narrow enough that the next chat can begin immediately.
- List earlier reports only when they contain still-relevant decisions, unresolved findings, or evidence not repeated in the latest report.
