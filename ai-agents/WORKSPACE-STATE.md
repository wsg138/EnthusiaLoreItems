# Workspace state

## Snapshot warning

This file is a committed coordination snapshot, not an authority over live GitHub. Every agent must refresh live state before routing or relying on it. Canonical branch and PR presence outranks stale queue text.

## Reconciled live baseline

- Repository: `wsg138/EnthusiaLoreItems`
- Live `main` at reconciliation: `2b6e20d0bf1d66f9efa092455d26f269f0107405`
- PR #10: merged through normal merge commit `2b6e20d0bf1d66f9efa092455d26f269f0107405`
- Exact-head `main` check at that SHA: `verify` completed successfully
- Open/draft package PRs: one
- Active package branch: `agent/wp-01-editor-template-management`
- Active draft PR: #11, `WP-01: complete editor and template management`
- Active package head at reconciliation: `e8a1f4f3f0588f138bf2484adcda816a275a3030`
- PR #11 reviews: none submitted
- PR #11 unresolved review threads: zero
- PR #11 checks at that head: `export` success, `toolchain` success, `verify` failure, external `Codacy Static Code Analysis` action required
- Other remaining-package canonical branches: not present in LoreItems at reconciliation

The old `agent/loreitems-pr4c2-natural-entity-template-updates` branch and `docs/fixed-remaining-work-system` branch are historical; their relevant heads are already represented by merged `main` history and they are not active package locks.

## Reconciled package status

| Package | Weight | Status | Live reason |
|---|---:|---|---|
| WP-01 | 20% | IN_PROGRESS | Canonical branch and draft PR #11 are active |
| WP-02 | 20% | BLOCKED | WP-01 is not COMPLETE |
| WP-03 | 20% | BLOCKED | WP-02 is not COMPLETE |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

WP-01 is not credited as complete. Its current failures and unfinished acceptance criteria remain on its canonical branch and PR. This workflow amendment does not implement or modify WP-01 functionality.

## Counts and progress

- Fixed package count: 6
- Completed packages: 0 of 6
- Remaining packages: 6 of 6
- Active implementation package: WP-01
- Ready unclaimed package: none
- Weighted remaining-program progress: `0%`

Progress is the sum of fixed weights whose `COMPLETE` state is authoritative on verified live `main`. `PARTIAL`, `IN_PROGRESS`, `IN_REVIEW`, `VERIFYING`, branch-local prospective state, open PRs, and passing local tests contribute zero.

## Automatic routing state

The universal prompt no longer requires an operator-supplied package ID. A future worker must:

1. reconcile all canonical package branches and every open/draft PR;
2. detect WP-01 PR #11 as unfinished even if a stale snapshot says `READY`;
3. resume WP-01 before considering any other package;
4. stop with an inconsistency report if another conflicting package PR appears;
5. never begin WP-02 until WP-01 is normally merged and verified `COMPLETE`.

## Exact next action

The next implementation worker uses `UNIVERSAL-AGENT-PROMPT.md` unchanged. It re-fetches live GitHub, resumes canonical WP-01 branch/PR #11 if still unfinished, creates a fast-forward resume checkpoint, and continues the remaining WP-01 checklist. It does not create a duplicate PR or select WP-02.

The documentation-only branch `docs/automatic-work-package-routing` exists solely to install this routing amendment and does not claim or begin WP-01.
