# Latest agent handoff

## Purpose

This is the current GitHub-backed handoff for the fixed remaining-work program after reconciling the automatic-routing amendment. Live GitHub always outranks this snapshot.

## Reconciled live state

- LoreItems live `main` at reconciliation: `2b6e20d0bf1d66f9efa092455d26f269f0107405`
- PR #10: confirmed merged by that normal merge commit
- `main` exact-head `verify`: successful
- Fixed packages: 6
- Complete: 0
- Remaining: 6
- Weighted remaining-program progress: 0%
- Active implementation package: WP-01
- Canonical branch: `agent/wp-01-editor-template-management`
- Draft PR: #11, `WP-01: complete editor and template management`
- Reconciled WP-01 head: `e8a1f4f3f0588f138bf2484adcda816a275a3030`
- Reviews: none submitted
- Unresolved review threads: zero
- Exact-head checks observed: `export` success; `toolchain` success; `verify` failure; external Codacy action required

The previous committed state that called WP-01 `READY` is stale. The canonical branch and draft PR are live unfinished work, so WP-01 is `IN_PROGRESS` and must be resumed before any new package is selected.

## Workflow amendment

The same universal dispatcher prompt now works for every future worker. It automatically:

- reconciles live GitHub before routing;
- searches all package branch names and all open/draft PRs;
- resumes the single unfinished package before new selection;
- prioritizes `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING`;
- selects only the lowest-numbered dependency-verified `READY` package when no unfinished package exists;
- stops on conflicting active package locks;
- uses the canonical branch/PR plus expected-head checkpointing as the concurrency lock;
- requires committed checkpoints after coherent sections and before intentional stops;
- preserves interrupted work as `PARTIAL` on the same branch;
- awards progress only after normal merge and verified authoritative `COMPLETE` state;
- stops after completing a package instead of beginning the newly unlocked package.

The valid status set is limited to `BLOCKED`, `READY`, `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, `VERIFYING`, and `COMPLETE`.

## Race and loophole review result

- Simultaneous claim attempts cannot create alternate package branches; canonical ref creation and expected-head checkpointing determine the winner, and head movement forces the other worker to stop.
- Abrupt interruption remains resumable because the canonical branch/PR survives even without a final checkpoint.
- Stale `READY` text cannot override an active canonical branch or PR.
- A `PARTIAL` package is resumed before any new `READY` package.
- Later `BLOCKED` packages cannot be selected.
- Partial implementation, local tests, review approval, or an unverified merge cannot be called complete or receive weighted credit.
- Local-only handoffs are invalid.
- Ad hoc subpackages and substitute branches remain prohibited.
- A completing worker must stop after normal merge and live verification.

## Exact next action

A future implementation worker uses `ai-agents/UNIVERSAL-AGENT-PROMPT.md` without inserting any package ID. It re-fetches live state and, while PR #11 remains unfinished, resumes WP-01 on `agent/wp-01-editor-template-management`, records a fast-forward resume checkpoint, completes the remaining WP-01 contract, and does not begin WP-02.

The planning branch `docs/automatic-work-package-routing` is documentation-only and must not modify WP-01 implementation.
