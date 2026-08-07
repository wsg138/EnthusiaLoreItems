# Latest agent handoff

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Resumed from exact head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`
- Latest verified implementation head: `d42684074788ebfbf9dbfaef6111a03254f99bdd`
- Coherent-section checkpoint: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-failure-and-migration-gates.md`

## Reconciliation
- Live `main` remains the verified WP-03 merge commit at the package start.
- WP-04 draft PR #15 remains the only unfinished canonical package lock; no WP-05/WP-06 competing package branch exists.

## Completed coherent sections
- Operator installation/upgrade/configuration, backup/restore, degraded recovery, queue/review recovery, rollback, and incident guidance.
- Executable WP-05 live acceptance matrix with unique case IDs and exact evidence expectations.
- Test-only SQLite failure-injection/restart harness and first complete direct/API-delivery durability group: pre-intent rollback; durable-intent replay; expired-claim restart recovery to review; verification-commit rollback/review; post-terminal replay; Paper-side post-insert durable-completion failure to review.
- Every historical committed SQLite schema version V1–V7 now has a populated upgrade test through the production migration runner, preserving identity, observation/current-state, pending mutation, deleted marker, audit, campaign, and recipient data.
- Migration gate verifies `integrity_check`, `foreign_key_check`, WAL, busy timeout, required indexes, and an interrupted V7 partial-DDL/data migration rollback followed by a successful retry.

## Exact verification
- Exact implementation head `d42684074788ebfbf9dbfaef6111a03254f99bdd`: GitHub Actions CI run `31182331548` completed successfully.
- `verify` passed Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- CodeRabbit combined commit status is `success`.
- No local build result is claimed because the runtime cannot resolve GitHub for a local dependency-capable checkout.

## Harsh-review findings fixed
- New failure-matrix test initially failed Java compilation because a reassigned repository was captured by a lambda; fixed by stable before/after-restart references.
- Complexity gate rejected two >50-NLOC test methods; fixed by extracting focused verification/restart helpers without suppressions.
- Codacy found harness field/method name collisions and nullable-runtime lifecycle smells; fixed with distinct names and explicit closed-state lifecycle.
- Codacy found migration fixture repeated literals and an inline schema-version literal; fixed with named constants/helper logic without suppressions.

## Remaining
- Complete deterministic crash/restart matrix for all other durable state machines and reload/shutdown points; fixed-seed randomized recovery.
- Saturation/backpressure verification across all named bounded queues/executors/caches/retries/result surfaces.
- Reload/shutdown lifecycle automation.
- Stable versioned Bukkit API tests/docs plus source/binary compatibility verification.
- Reproducible fixed-scenario performance/profile harness and committed threshold evidence.
- Remaining static-analysis/complexity remediation as new work is added.
- RC version/artifact, CycloneDX JSON SBOM, dependency manifest, checksum, reproducibility/package smoke, release workflow, release notes.
- Full-package independent harsh review and all fixes; exact-head CI/Codacy and zero unresolved review state.
- Normal merge, post-merge `main` verification, and verified `v1.0.0-rc.1` prerelease/assets.

## Blocker
None. WP-04 remains `IN_PROGRESS`.

## Exact next action
Inventory every bounded-work surface named by WP-04 and add deterministic saturation/backpressure tests for capacities, rejection/defer behavior, bounded retries/results, and metrics. Commit that coherent section on PR #15 and continue WP-04 only.
