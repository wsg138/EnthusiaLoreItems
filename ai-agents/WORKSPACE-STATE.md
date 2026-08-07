# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-04-production-hardening`
- Pull request: draft PR #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Resumed from exact head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`
- Latest verified implementation head: `d42684074788ebfbf9dbfaef6111a03254f99bdd`
- Latest coherent-section handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-failure-and-migration-gates.md`

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | IN_PROGRESS | draft PR #15; direct-delivery crash matrix and V1–V7 migration matrix verified; remaining hardening gates in progress |
| WP-05 | 15% | BLOCKED | WP-04 release candidate not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release not verified |

## Progress
- Fixed packages: 6
- Completed: 3 of 6
- Remaining: 3 of 6
- Weighted progress: `60 / 100 = 60%`

## Completed acceptance criteria / coherent sections
- Startup/live-GitHub reconciliation and durable WP-04 resume on the canonical draft PR.
- Operator/recovery/backup/restore/rollback/incident documentation at `docs/operator-guide.md`.
- Executable WP-05 manual acceptance matrix at `docs/wp-05-manual-acceptance-matrix.md`.
- Reusable test-only SQLite failure-injection/restart harness plus direct/API-delivery crash-boundary coverage: pre-intent rollback, durable-intent replay, expired claimed work after restart, verification-commit rollback/review recovery, terminal replay, and Paper-side physical-insert/durable-completion ambiguity escalation to review.
- Every committed SQLite schema V1 through V7 is constructed through the production migration runner, populated with durable identity/audit/pending/deleted-marker/campaign state, upgraded to current schema, and checked for preservation, integrity, foreign keys, WAL, busy timeout, and required indexes.
- Interrupted V7 migration is forced after partial DDL/data-copy work, verified to roll back, then retried successfully.

## Remaining acceptance criteria
- Complete deterministic crash/restart matrix for every other durable state machine and reload/shutdown boundaries, plus fixed-seed randomized recovery.
- Saturation/backpressure tests for every required queue/executor/cache/retry/result surface.
- Reload/shutdown lifecycle automation.
- Stable versioned Bukkit API finalization/tests/docs and source/binary compatibility verification.
- Fixed-scenario performance/profile harness and committed threshold evidence.
- Static-analysis/complexity remediation across all newly added work without broad suppressions.
- RC version/artifact/SBOM/dependency manifest/checksum/reproducibility/package smoke/release workflow/release notes.
- Full-package harsh and independent review, all confirmed fixes, exact-head CI/Codacy, zero unresolved review state.
- Normal merge, post-merge `main` verification, and `v1.0.0-rc.1` prerelease/assets verified against merge SHA.

## Tests and verification
- Exact head `d42684074788ebfbf9dbfaef6111a03254f99bdd`: GitHub Actions CI run `31182331548` completed `success`.
- Its `verify` job passed Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- Combined commit status includes CodeRabbit `success`.
- Earlier exact-head CI exposed and the branch fixed: Java lambda capture compilation, two >50-NLOC test methods, four Codacy harness naming/nullability findings, and three migration-fixture duplicate-literal/version findings. No broad suppression was added.
- No local build result is claimed because this runtime cannot resolve GitHub for a dependency-capable checkout.
- No live Paper/Leaf acceptance is claimed; WP-05 remains responsible for live acceptance.

## Known findings
- No competing unfinished canonical package lock exists.
- No production item-loss/duplication defect has been confirmed in the completed direct-delivery failure group; ambiguous post-physical-apply persistence failure is explicitly escalated to review.

## Blocker
None. WP-04 remains `IN_PROGRESS`.

## Exact next action
Inventory and verify every bounded-work surface named by WP-04, add deterministic saturation/backpressure tests for capacity, rejection/defer behavior, bounded retries/results/metrics, and commit that coherent section on draft PR #15. Do not start WP-05.
