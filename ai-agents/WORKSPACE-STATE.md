# Workspace state

## Snapshot warning

This file is a committed coordination snapshot. Live GitHub remains authoritative. Resolve conflicts using this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Publication state

- Repository: `wsg138/EnthusiaLoreItems`
- Verified live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Active unfinished package lock: WP-03 — one-use mass distributions
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-03-mass-distributions`
- Pull request: #14, `WP-03: complete one-use mass distributions` (draft during resumed work)
- Resume base head: `10cb131e93c4758cfe9f1e174e1400cb8d0b5ffc`
- Exact implementation/review-ready head before coordination commits: `895f0e9f9e3160db1dde255c997cebf3cf19090e`
- Latest exact runtime head with complete successful CI/Codacy: `45e0ea43cf0034ce87098ae0945a319149929a48`
- Resume checkpoint report: `ai-agents/reports/agent-handoffs/2026-08-07-wp-03-review-capacity-resume.md`
- Exact next package after authoritative WP-03 completion: WP-04 — automated production hardening and release candidate

## Live reconciliation

- Live `main` is the verified normal merge of WP-02 PR #13.
- PR #14 and `agent/wp-03-mass-distributions` are the single unfinished package lock.
- WP-01 and WP-02 canonical branches are historical merged branches; no WP-04, WP-05, WP-06 LoreItems finalization/API-blocker branch, or EnthusiaTags WP-06 integration branch is active.
- PR #14 had no submitted reviews, no requested changes, and zero unresolved review threads at resume observation.
- The prior CodeRabbit capacity blocker is stale: its visible comment was last updated at `2026-08-07T08:12:33Z` and reported `Next review available in: 46 minutes`; this worker began after that window had elapsed.
- PR #14 was converted back to draft before the resume checkpoint so branch + draft PR again form the durable active claim.

## Package status

| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| WP-02 | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| WP-03 | 20% | IN_PROGRESS | Existing canonical PR resumed after the temporary review-capacity window elapsed |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

## Counts and weighted progress

- Fixed package count: 6
- Completed packages: 2 of 6
- Remaining packages: 4 of 6
- Weighted progress: `40 / 100 = 40%`
- WP-03 receives no official weighted completion credit while incomplete.

## Completed acceptance criteria

- Required groups/completed/cancelled directories and bounded off-thread YAML discovery/validation are implemented with path/symlink safety, deterministic fingerprints, strict diagnostics, and bounded file/recipient work.
- Java names, leading-`*` Floodgate-style names, and UUID recipients are supported with preserved audit forms and normalized duplicate rejection.
- Campaign start is DB-authoritative and atomic for campaign UUID, source identity/fingerprint, pinned definition revision, immutable recipients, actor, and audit before marker movement or delivery.
- Source replay, copied markers, restart, and filesystem uncertainty cannot create a second logical campaign.
- Cached-name resolution is off-thread and non-network-dependent; unresolved names bind case-insensitively on future joins and UUID then becomes authoritative.
- Exactly-once delivery reserves durable instance identity before Paper mutation, verifies insertion, persists offline/full inventory, creates no overflow drop, and fences ambiguous crash outcomes for review.
- Exact seven-state counts/equations, pagination, pause/resume/cancel, atomic control/audit persistence, restart survival, WP-02 recovery integration, metrics, permissions, messages, audit, degraded startup, reload, ordered shutdown, and DB-authoritative marker recovery are implemented.
- Dedicated Paper delivery-worker, multi-campaign exactly-once, marker-loss, cancellation-failure, and real SQLite restart/control regressions are committed.
- Full-package harsh review was completed and every confirmed internal finding was fixed on this branch.

## Tests and verification

- GitHub Actions run `31159954396` on exact runtime head `45e0ea43cf0034ce87098ae0945a319149929a48`: success for full Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- `895f0e9f9e3160db1dde255c997cebf3cf19090e` is documentation-only for already-implemented recipient-health metrics.
- Later coordination/blocker/resume commits make that evidence stale for merge; final exact-head verification remains required after review and final state changes.
- A dependency-capable local checkout is unavailable in this runtime because GitHub DNS resolution fails; no local test result is claimed.

## Harsh-review findings and fixes

Confirmed internal findings already fixed include cached-name threading, oversized persistence/delivery/command complexity, Codacy findings, non-atomic campaign control/audit persistence, missing campaign recovery rows, partial-start cleanup, stale terminal markers, DB/filesystem marker-loss split brain, missing recipient-health metrics, cancellation-failure fencing, and missing focused Paper/end-to-end/restart regressions.

No unresolved author-side harsh-review finding is known at this resume checkpoint.

## Remaining acceptance criteria

1. Obtain the required substantive independent review covering source identity, immutable snapshots, exactly-once delivery, Floodgate handling, DB/filesystem ordering, restart recovery, cancellation, counts, threading, and bounds.
2. Resolve every requested change and actionable review thread on this same branch.
3. Move WP-03 to `VERIFYING` only after independent review is complete and review state is clean.
4. Obtain final exact-head Actions/Codacy success after all review and workflow-state changes.
5. Commit prospective completion state: WP-03 `COMPLETE`, only WP-04 `READY`, 3/6 complete, 3 remaining, weighted progress 60%, with exact final evidence.
6. Normally merge PR #14, verify live `main` and required post-merge checks, and stop without beginning WP-04.

## Blocker

None at resume time. The previous review-capacity blocker aged out according to its own GitHub-visible countdown. If a fresh review request is explicitly rejected by the external service, WP-03 may return to `BLOCKED` with that new evidence.

## Exact next action

Re-fetch the resumed head to prove the claim, mark PR #14 ready for review, trigger `@coderabbitai review`, and reconcile the resulting independent review. Do not begin WP-04.