# Latest agent handoff

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Resumed from exact head: `28227f3b8b79bea86d0fc44be312a05a561e3263`
- Latest verified implementation head before resume: `d42684074788ebfbf9dbfaef6111a03254f99bdd`
- Previous coherent-section checkpoint: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-failure-and-migration-gates.md`

## Reconciliation
- Live `main` is `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`, the verified WP-03 merge commit.
- WP-04 draft PR #15 is the only unfinished canonical package lock.
- Canonical WP-01 through WP-03 branches are historical; WP-05 and WP-06 primary LoreItems branches are absent; no WP-06 LoreItems auxiliary branch is present.
- Exact observed PR head at resume: `28227f3b8b79bea86d0fc44be312a05a561e3263`.

## Completed coherent sections
- Operator installation/upgrade/configuration, backup/restore, degraded recovery, queue/review recovery, rollback, and incident guidance.
- Executable WP-05 live acceptance matrix with unique case IDs and exact evidence expectations.
- Test-only SQLite failure-injection/restart harness and first complete direct/API-delivery durability group: pre-intent rollback; durable-intent replay; expired-claim restart recovery to review; verification-commit rollback/review; post-terminal replay; Paper-side post-insert durable-completion failure to review.
- Every historical committed SQLite schema version V1–V7 has a populated upgrade test through the production migration runner, preserving identity, observation/current-state, pending mutation, deleted marker, audit, campaign, and recipient data.
- Migration gate verifies `integrity_check`, `foreign_key_check`, WAL, busy timeout, required indexes, and interrupted V7 partial-DDL/data migration rollback followed by successful retry.

## Resume verification
- Exact pre-resume checkpoint head `28227f3b8b79bea86d0fc44be312a05a561e3263`: GitHub Actions CI run `31182619090` completed `success`.
- Combined commit status includes CodeRabbit `success`.
- PR #15 has no submitted reviews and zero review threads; no requested changes exist.
- PR #15 is open, draft, and mergeable.
- No local Gradle/build result is claimed because this runtime cannot resolve GitHub for a dependency-capable checkout.

## Remaining acceptance criteria
- Complete deterministic crash/restart matrix for all other durable state machines and reload/shutdown points; fixed-seed randomized recovery.
- Saturation/backpressure verification across database executor, delivery, update, destructive, campaign, notification, reconciliation, GUI query, debounce, cache, retry, and bounded result surfaces.
- Reload/shutdown lifecycle automation.
- Stable versioned Bukkit API tests/docs plus source/binary compatibility verification.
- Reproducible fixed-scenario performance/profile harness and committed threshold evidence.
- Remaining static-analysis/complexity remediation as new work is added.
- RC version/artifact, CycloneDX JSON SBOM, dependency manifest, checksum, reproducibility/package smoke, release workflow, release notes.
- Full-package independent harsh review and all fixes; exact-head CI/Codacy and zero unresolved review state.
- Normal merge, post-merge `main` verification, and verified `v1.0.0-rc.1` prerelease/assets.

## Known findings
- No competing unfinished package lock is present.
- Existing pre-resume exact-head verification is green, but any new commit makes that evidence stale and requires exact-head re-verification.

## Blocker
None. WP-04 remains `IN_PROGRESS`.

## Exact next action
Inventory every bounded-work surface named by WP-04, implement deterministic saturation/backpressure tests for configured capacity, rejection/defer behavior, bounded retries/results/metrics, commit that coherent section on PR #15, and continue WP-04 only. Do not start WP-05.
