# Universal work-package agent prompt

Use this prompt only after an operator assigns exactly one package ID from `ai-agents/WORK-QUEUE.md`.

## Assignment

- Repository program: EnthusiaLoreItems remaining-work program
- Assigned package: `<WP-01|WP-02|WP-03|WP-04|WP-05|WP-06>`
- Package contract: the matching file in `ai-agents/work-packages/`

The package file is the complete work assignment. Complete the entire package. Do not select a smaller task, create an `a`, `b`, or `c` package, rename the package, defer listed acceptance criteria, or begin the next package.

## Required startup reconciliation

Before changing anything:

1. Query live GitHub for the current `main` SHA, open and draft pull requests, active branches, recent merges, requested changes, unresolved review threads, commit checks, workflow runs, and commit statuses.
2. Read `REQUIREMENTS.md`, `docs/architecture.md`, `docs/implementation-plan.md`, `ai-agents/AGENTS.md`, `ai-agents/WORKSPACE-STATE.md`, `ai-agents/WORK-QUEUE.md`, the assigned package file, and `ai-agents/reports/agent-handoffs/latest.md` from live `main`.
3. Reconcile those files with live GitHub. Live commits, pull requests, reviews, and checks outrank snapshots and handoffs.
4. Search for an existing branch or pull request for the assigned package. Resume it when it exists. Do not create a replacement PR merely because the previous worker stopped or failed.
5. If the assigned package is already merged, verify `main`, update no code, report the verified state, and stop. Do not advance to the next package.
6. If a dependency is not complete, record the blocker in the assigned package's existing GitHub PR or in `ai-agents/reports/agent-handoffs/latest.md` through a documentation PR, leave the package `BLOCKED`, and stop. Do not work around the dependency by starting another package.

## Execution contract

- Work only on the assigned package and fixes, tests, documentation, migrations, and review remediation necessary to satisfy every criterion in its package file.
- Preserve the architecture, durability, idempotency, bounded-work, threading, recovery, and no-force-load requirements.
- A package may have many commits, but it remains one indivisible package. Commit boundaries are not subpackages.
- Failure, interruption, CI failure, review feedback, or a stale branch returns work to the same package and branch. It never authorizes a smaller replacement package.
- Do not claim completion from code presence, a successful local build, or partial tests. Completion requires every package gate and a normal merge commit followed by live `main` verification.
- Do not begin work that belongs to the exact next package, even when it appears convenient.

## Git and pull-request rules

Use the exact branch and PR title in the assigned package file. Create the branch from the live `main` SHA only when no relevant unfinished branch or PR exists.

The PR body must contain:

- assigned package ID and a link to its contract;
- starting `main` SHA;
- complete scope checklist matching every required-scope and acceptance item;
- migrations and compatibility impact;
- item-loss, duplicate-creation, main-thread blocking, unbounded-work, reload/shutdown, and architecture risk review;
- automated test commands and exact results;
- untested live behavior, if any;
- rollback and recovery notes;
- exact-head evidence table with workflow/check name, result, run or check URL, and tested commit SHA.

Do not merge with squash or rebase. Merge only with GitHub's normal merge-commit method.

## Evidence rules

Evidence is valid only when it is permanently visible in GitHub and identifies the exact PR head SHA.

Before merge:

1. Every applicable GitHub Actions job for the exact head is `completed/success`.
2. The repository's `Verify exact-head Codacy` step passed for the exact head, and any external Codacy status/check required by the repository is successful.
3. The full Gradle verification, repository-tool tests, architecture checks, static analysis, and new-code complexity checks required by CI passed.
4. No review is in `CHANGES_REQUESTED` state.
5. Every actionable review comment is resolved and the unresolved review-thread count is zero.
6. An independent review covers the package's required risk list and records either approval or no remaining blockers on GitHub.
7. The PR is mergeable, the base is current, and evidence has been re-run after the last code or documentation change.

Never report a check as passed from memory, a branch name, a screenshot without a commit, a local command, or a check from an older SHA. When evidence is unavailable, say it is unavailable and keep the package incomplete.

## Durable state and handoff

GitHub is the only durable source of truth. Before merge, update in the same PR:

- `ai-agents/WORKSPACE-STATE.md` with the package's exact status and evidence snapshot;
- `ai-agents/WORK-QUEUE.md` only for the package status/count/progress fields allowed by its fixed contract, never to change package scope or weights;
- `ai-agents/reports/agent-handoffs/latest.md` with exact branch, PR, head SHA, checks, reviews, blockers, and next action.

A local file, downloadable report, chat response, or uncommitted note is not a handoff and cannot substitute for committed GitHub state.

## Completion and stopping rule

After all gates pass, merge the assigned package with a normal merge commit. Verify that live `main` points to the resulting merge commit and contains the package changes. Perform only post-merge verification or release publication explicitly required by that package, update GitHub evidence where the package requires it, then stop. Do not create a branch, commit, issue, or PR for the next package.
