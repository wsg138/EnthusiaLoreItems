# Latest agent handoff

## Purpose

This is the current GitHub-backed handoff for the fixed remaining-work program. Live GitHub always outranks this snapshot.

## Active package claim

- Package: WP-02 — destructive administration
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-02-destructive-administration`
- Draft pull request: #13, `WP-02: complete destructive administration`
- Verified starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Initial claim checkpoint: `2612d40607916414f06d4d6a46aef3d887bafc89`
- Dependency: WP-01 is `COMPLETE`; PR #11 normally merged and historical head `08f92b8b185e4022154bc34a01d30f3123c6043b` is contained in live `main`

## Reconciliation and claim completed

- Live LoreItems `main`, recent merges, open/draft PRs, commit checks/workflow visibility, and every canonical LoreItems package branch were inspected.
- The canonical WP-06 branch in EnthusiaTags and the contract-defined LoreItems WP-06 branches were inspected; none creates an unfinished fixed-package lock.
- All six package contracts, the dispatcher, agent rules, workspace state, queue, prior handoff, requirements, architecture, and implementation plan were read from exact live `main`.
- No unfinished canonical package branch or PR existed. WP-02 was the first eligible package.
- The exact WP-02 branch was created atomically from the verified live `main` SHA.
- The durable claim checkpoint was committed and exact draft PR #13 was opened from that head.

## Completed acceptance criteria in this checkpoint

Only routing, dependency verification, required-document reading, canonical branch creation, durable `IN_PROGRESS` state recording, and exact draft PR creation are complete.

## Remaining acceptance criteria

The full WP-02 contract remains, including exact removal, purge, full delete and late-copy behavior, confirmations, durable idempotent intent, bounded physical execution, queue inspection, pause/resume, evidence-gated review resolution, duplicate/malformed preservation, metrics, permissions, messages, history, recovery, lifecycle behavior, tests, documentation, harsh review, exact-head CI/Codacy, review resolution, normal merge, and live-main verification.

## Tests, findings, and limitations

- Tests run: none at claim time.
- Known implementation findings: none established yet.
- External blocker: none.
- Local environment: GitHub DNS resolution is unavailable, so a dependency-capable local checkout could not be created. GitHub connector state and repository-native CI remain the durable evidence path.

## Program counts

- Completed packages: 1 of 6
- Remaining packages: 5 of 6
- Weighted progress: 20%
- WP-02: IN_PROGRESS
- WP-03 through WP-06: BLOCKED

## Exact next action

Inspect existing destructive mutation, deleted-marker, queue-control, Paper execution, GUI/command, persistence, and test code; identify the precise delta to the complete WP-02 contract; then implement the first coherent section on PR #13.