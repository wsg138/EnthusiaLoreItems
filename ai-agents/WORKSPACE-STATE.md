# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `PARTIAL`
- Canonical branch: `agent/wp-04-production-hardening`
- Pull request: draft PR #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Durable claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Checkpointed implementation/evidence head: `6b7c50d9cc1e7d5b5c3db8b41966b806595838d6`
- WP-03 post-merge verification: merge SHA `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`; GitHub Actions check `verify` run `31174065679` completed successfully.

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge SHA and post-merge Actions verified |
| WP-04 | 15% | PARTIAL | operator/recovery docs and executable WP-05 matrix committed on canonical PR; automated hardening/release gates remain |
| WP-05 | 15% | BLOCKED | WP-04 release candidate not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release not verified |

## Progress
- Fixed packages: 6
- Completed: 3 of 6
- Remaining: 3 of 6
- Weighted progress: `60 / 100 = 60%`

## Completed acceptance criteria / coherent sections
- Startup reconciliation completed against live GitHub and WP-04 was durably claimed on draft PR #15.
- WP-04 dependency on WP-03 is verified complete on live GitHub.
- Operator documentation is committed at `docs/operator-guide.md`, covering installation/upgrade, configuration, permissions/commands, metrics/backlog interpretation, backups, WAL-aware online backup constraints, restore, integrity/degraded recovery, queue/review recovery, deleted markers, campaign marker repair, staged deployment, rollback, and incident collection.
- The executable WP-05 manual acceptance specification is committed at `docs/wp-05-manual-acceptance-matrix.md` with unique case IDs, prerequisites, exact steps, expected durable/database/physical results, evidence, cleanup, rollback, regression subset, and exact-final-jar gate.
- Durable checkpoint handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-operator-docs-and-acceptance-matrix.md`.

## Remaining acceptance criteria
- Deterministic failure-injection/restart harness and complete state-machine failure matrix.
- Queue saturation/backpressure tests for all required queues/executors/caches/retries and bounded results.
- Every historical schema migration/upgrade path, interrupted migration rollback, integrity/WAL/index verification.
- Reload/shutdown lifecycle automation.
- Stable versioned Bukkit API finalization/tests/docs plus source/binary compatibility verification.
- Fixed-scenario performance/profile harness and committed threshold evidence.
- Static-analysis/complexity remediation without broad suppressions.
- RC version/artifact/reproducibility/package smoke/release workflow and release notes.
- Full-package harsh and independent review, confirmed fixes, exact-head CI/Codacy, zero unresolved review state.
- Normal merge, post-merge `main` verification, and `v1.0.0-rc.1` prerelease/assets verified against the merge SHA.

## Tests and verification
- No local build result is claimed; this runtime has no mounted dependency-capable checkout.
- GitHub Actions CI run `31179217336` was triggered on exact documentation/matrix head `c9784b744271384aac32ea7e145f38df1476cd49` and was still in progress when the durable handoff was written.
- WP-03 dependency remains verified by Actions run `31174065679` on merge SHA `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`.
- No live Paper/Leaf behavior is claimed; that remains WP-05.

## Known findings
- Stale package metadata said PR creation was still pending after PR #15 already existed; corrected in this checkpoint.
- No production-code defect was established during the operator-documentation/manual-matrix section.

## Blocker
None. WP-04 is intentionally `PARTIAL`, not `BLOCKED`.

## Exact next action
Resume draft PR #15 on `agent/wp-04-production-hardening`, re-fetch exact head/check/review state, then implement the reusable deterministic failure-injection/restart harness and the first complete durable state-machine test group. Do not start WP-05.
