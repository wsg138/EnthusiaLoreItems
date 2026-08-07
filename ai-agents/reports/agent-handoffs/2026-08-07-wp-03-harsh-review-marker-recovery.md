# WP-03 harsh-review and marker-recovery checkpoint

## Package

- Package: WP-03 — one-use mass distributions
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-03-mass-distributions`
- Draft PR: #14, `WP-03: complete one-use mass distributions`
- Verified live `main` at this checkpoint: `d77ec61032e5583783694ae349f785495cbf8f31`
- Implementation head before this checkpoint record: `3e3093457edd39ccfb094dc03d3723f5a0eb6d5f`

## Completed acceptance work in this section

- Reconciled the earlier campaign-control audit-order failure with the repository's newest-first audit-history contract and restored green Gradle verification before later changes.
- Reduced `DistributionCampaignCommandExecutor.route` below the repository Lizard complexity gate without changing command behavior.
- Resolved all six exact-head Codacy findings observed on the command/control integration head without suppressing or weakening analysis:
  - bounded preview storage now uses a concurrent map;
  - tab-completion argument counts use named constants;
  - repeated command delimiter uses one named constant;
  - cancellable-delivery time argument uses a named constant;
  - failed distribution startup closes the runtime instead of assigning its lifecycle field to null.
- Added dedicated Paper campaign-delivery worker tests covering offline deferral, full-inventory deferral/no mutation, online prepare/insert/complete, bounded wakeups, and non-overlapping polling while recovery is in flight.
- Added a SQLite multi-campaign end-to-end test proving the same player receives distinct instances from independent campaigns and each campaign recipient completes at most once.
- Completed harsh-review traces for cancellation, source replay, cached/unresolved/Floodgate identity binding, writable/degraded startup, and shutdown ordering.
- Fixed a DB/filesystem split-brain defect: when the durable campaign exists but the original/active source marker is missing, marker recovery now atomically synthesizes a non-reusable operator marker from durable campaign metadata. Terminal reconciliation can move that reconstructed marker into `completed/` or `cancelled/`.
- Added marker-reconciliation tests for missing active-source recovery and missing terminal-source recovery.

## Harsh-review findings and fixes

1. **Command routing exceeded the new-code complexity gate.** Fixed by splitting control routing from inspection/start routing.
2. **Exact-head Codacy reported six localized findings.** All six were fixed directly; no analyzer configuration or threshold was weakened.
3. **Distribution startup failure could retain a closed runtime reference.** Fixed by closing the failed runtime and allowing normal plugin disable/shutdown ownership to clean up the lifecycle field.
4. **Campaign marker recovery could remain `MISSING_SOURCE` forever after a DB-commit/filesystem-loss crash window.** Fixed by reconstructing the marker from DB-authoritative campaign metadata rather than requiring the original input file to still exist.
5. **Dedicated Paper campaign-delivery worker coverage was missing.** Added focused MockBukkit tests.
6. **Explicit multi-campaign exactly-once end-to-end coverage was missing.** Added SQLite integration coverage.

## Verification

- `7f807affa544fa36168aa9b03e25831c1386bd85`: Gradle verification and repository tooling passed; complexity failed only on command routing.
- `52f9005cf7c0f1e6ec367f6e274f6f7a2d4e2a0e`: Gradle verification, repository tooling, and complexity passed; exact-head Codacy surfaced six concrete findings.
- Those six findings were remediated through `2dea90e010006569e6a46f78eedb29f5f12837eb`.
- Paper worker and SQLite end-to-end tests were then added through `bc233799d6d9216d791017d2a22dc45e7553a22a`.
- A compatible metrics-only branch commit advanced the head to `fe4c27d4057f42d85a8adad9ddff5fa03f21a530`.
- Marker-recovery implementation and tests advanced the implementation head to `3e3093457edd39ccfb094dc03d3723f5a0eb6d5f`.
- GitHub Actions CI run `31159229408` is in progress for exact head `3e3093457edd39ccfb094dc03d3723f5a0eb6d5f`; no result is claimed yet.

## Remaining acceptance criteria

- Resolve any failures from exact-head CI/Codacy after the marker-recovery change.
- Finish the remaining harsh-review passes for recipient-state equations, pause/resume/cancel restart semantics, metrics/permissions/messages/audit, reload behavior, queue/page/per-tick bounds, and package-required failure/restart regressions.
- Reconcile substantive PR review state, requested changes, and unresolved threads.
- Obtain exact-head success for full Gradle verification, repository tooling, new-code complexity, and Codacy after the final implementation/review change.
- Update `WORK-QUEUE.md`, `WORKSPACE-STATE.md`, package status, and `latest.md` only when the corresponding verification state is true.
- When every gate passes, mark WP-03 `COMPLETE`, unlock only WP-04 as `READY`, update package counts/weighted progress, normally merge PR #14, verify live `main`, and stop.

## Blocker

None. CI/static-analysis failures and review findings remain same-package WP-03 work.

## Exact next action

Read the exact-head CI/Codacy result for `3e3093457edd39ccfb094dc03d3723f5a0eb6d5f`; fix any concrete issue on this same branch, then finish the remaining full-package harsh-review checklist before moving WP-03 to review/verification state.
