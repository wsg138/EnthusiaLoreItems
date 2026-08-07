# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-04-production-hardening`
- Pull request: draft PR #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Durable claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Resumed from exact head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`
- Previous durable section handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-operator-docs-and-acceptance-matrix.md`
- Resume checkpoint handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-resumed-failure-matrix.md`
- WP-03 post-merge verification: merge SHA `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`; GitHub Actions check `verify` run `31174065679` completed successfully.

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | IN_PROGRESS | canonical draft PR #15 resumed at exact head `43b4092e…`; implementing automated hardening gates |
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
- Historical canonical branches WP-01 through WP-03 are confirmed contained in live `main`; WP-04 is the only unfinished canonical lock. WP-05/WP-06 LoreItems auxiliary branches and the EnthusiaTags WP-06 primary branch are absent.

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
- Exact resumed head `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`: GitHub Actions CI run `31179353021` completed `success`.
- That run's `verify` job completed Gradle verification, repository tooling, new-code complexity, and exact-head Codacy successfully.
- Combined commit status includes CodeRabbit `success`.
- PR #15 has no submitted reviews, no requested changes, and zero review threads at resume.
- No local Gradle/build/test result is claimed because this runtime cannot resolve GitHub for a local checkout.
- No live Paper/Leaf behavior is claimed; that remains WP-05.

## Known findings
- No competing unfinished canonical package lock was found.
- No production-code defect has yet been established during WP-04; automated hardening work remains incomplete.

## Blocker
None. WP-04 is actively resumed as `IN_PROGRESS`.

## Exact next action
Implement the reusable deterministic failure-injection/restart harness and the first complete durable state-machine failure group on draft PR #15, then commit the coherent section with exact test/check evidence. Do not start WP-05.
