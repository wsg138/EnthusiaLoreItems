# Workspace state

## Snapshot warning

This file is a committed coordination snapshot. Live GitHub remains authoritative. Resolve conflicts using this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Publication state

- Repository: `wsg138/EnthusiaLoreItems`
- Verified starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Active package: WP-03 — one-use mass distributions
- Status: `IN_REVIEW`
- Canonical branch: `agent/wp-03-mass-distributions`
- Pull request: #14, `WP-03: complete one-use mass distributions`
- Latest fully verified implementation/restart-test head: `67f4d1cba9c0d0cf34c54827f7f106085394401c`
- Review-entry coordination head before this state commit: `649a52c79f8feb54d7fcbc1e74dbc94ce2e01b5c`
- Exact next package after authoritative WP-03 completion: WP-04 — automated production hardening and release candidate

## Live reconciliation

- Live `main` remains `d77ec61032e5583783694ae349f785495cbf8f31`, the normal merge of WP-02 PR #13.
- PR #14 and `agent/wp-03-mass-distributions` remain the single unfinished LoreItems package lock.
- WP-04 through WP-06 remain blocked and no next-package branch has been claimed.
- PR #14 had no requested changes or unresolved review threads before entering the review gate.

## Package status

| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| WP-02 | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| WP-03 | 20% | IN_REVIEW | Complete implementation and harsh-review fixes are on the fixed PR; substantive review/final verification remain |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

## Counts and weighted progress

- Fixed package count: 6
- Completed packages: 2 of 6
- Remaining packages: 4 of 6
- Weighted progress: `40 / 100 = 40%`
- Active incomplete work receives zero official weighted completion credit.

## WP-03 completed acceptance work

- Creates exactly the required `groups/`, `groups/completed/`, and `groups/cancelled/` directories and performs bounded, off-thread YAML discovery/validation.
- Supports Java names, leading-`*` Floodgate-style names, and UUIDs; preserves original values; rejects malformed/duplicate/path/symlink/unsupported input.
- Uses an immutable DB-authoritative campaign UUID, source fingerprint, selected definition revision, recipient snapshot, actor, and audit record before any filesystem move or physical delivery.
- Refuses replay of a previously committed source fingerprint and rejects source/revision drift before start.
- Resolves only cached names before start and durably binds unresolved names case-insensitively on later joins without network lookup correctness dependency.
- Uses a bounded persistent campaign queue with claim leases, prepared instance identity, verified main-thread insertion, exact completion, offline/full deferral, no overflow drops, join/inventory wakeups, and conservative `REVIEW_REQUIRED` recovery.
- Provides exact seven-state counts plus total/remaining equations, bounded campaign/recipient pagination, pause/resume/cancel, atomic control/audit persistence, and WP-02 recovery-view integration.
- Recovers active/completed/cancelled markers from durable DB state. If original/active files are gone after DB commit, recovery atomically synthesizes a non-reusable operator marker and can terminalize it later.
- Integrates permissions, messages, metrics-port instrumentation, audit, documentation, writable/degraded startup, and ordered shutdown.

## Harsh review and fixes

- Moved cached-name resolution off the server thread.
- Decomposed oversized SQLite/Paper delivery components and command routing to satisfy new-code complexity limits without weakening gates.
- Fixed exact-head Codacy findings directly, including concurrent preview storage, named command/time constants, repeated delimiters, and startup cleanup.
- Fixed atomic campaign control/audit persistence.
- Fixed missing campaign-review rows in the canonical `/loreitems recovery` surface.
- Fixed a partial-start Bukkit service leak.
- Fixed DB/filesystem split-brain marker recovery when the original source disappears after durable start.
- Added dedicated Paper campaign-delivery worker tests, multi-campaign exactly-once end-to-end coverage, and pause/resume/cancel restart persistence coverage.

## Tests and verification

- CI run #979 on exact head `67f4d1cba9c0d0cf34c54827f7f106085394401c` passed full Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- The test matrix includes parser/validator safety, immutable snapshots/replay/revision drift, cached/late identity binding, recipient state/count transitions, atomic control/audit, delivery claim/prepare/complete/review/cancel, full inventory/no-drop behavior, cancellation fencing, marker reconciliation, multiple independent campaigns, restart recovery, and foundation regressions run by the full repository suite.
- Documentation/review-state commits after that implementation head require the normal final exact-head CI refresh before merge.

## Remaining acceptance criteria

- Mark PR #14 ready and obtain substantive review.
- Resolve every requested change and unresolved review thread on this same package branch.
- Move to `VERIFYING` only after review is clean.
- Obtain final exact-head success for Gradle verification, repository tooling, complexity, and Codacy after all review/state changes.
- Publish final COMPLETE/READY queue and handoff state, normally merge PR #14, verify live `main`, and stop without starting WP-04.

## Blocker

None. The local container cannot resolve GitHub, but authenticated GitHub repository tooling remains sufficient for durable package work.

## Exact next action

Mark fixed PR #14 ready for review, reconcile substantive review findings, then perform the final exact-head verification gate on the reviewed head.
