# Latest agent handoff

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Resumed from exact head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`
- Previous durable section handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-operator-docs-and-acceptance-matrix.md`
- Resume checkpoint: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-resumed-failure-matrix.md`

## Reconciliation
- Live `main` is `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`, the normal WP-03 merge commit.
- Canonical WP-01, WP-02, and WP-03 branches are historical and fully contained in live `main`.
- Draft PR #15 on `agent/wp-04-production-hardening` is the only unfinished canonical package lock.
- No WP-05 LoreItems branch, WP-06 LoreItems auxiliary branch, or EnthusiaTags WP-06 primary branch exists.
- PR #15 remained open, draft, mergeable, and exactly at `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34` immediately before resume.

## Completed before this resume
- Added `docs/operator-guide.md` with installation/upgrade/configuration/permissions, WAL-aware backups, restore, degraded/read-only recovery, queue/review recovery, deleted-marker handling, campaign-marker repair, staged deployment, rollback, and incident collection.
- Added `docs/wp-05-manual-acceptance-matrix.md` with unique live case IDs, prerequisites, exact steps, expected physical and durable/database outcomes, evidence requirements, cleanup, rollback, defect-regression subset, and exact-final-jar release gate.

## Verification at resume
- Exact head `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`: GitHub Actions CI run `31179353021` completed successfully.
- The `verify` job passed Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- Combined commit status includes CodeRabbit `success`.
- PR #15 has no submitted reviews, no requested changes, and zero review threads.
- No local Gradle/build result is claimed because this runtime cannot resolve GitHub for a local checkout.
- No live Paper/Leaf acceptance is claimed; that is WP-05.

## Remaining
- deterministic failure-injection/restart harness and complete durable state-machine matrix;
- saturation/backpressure automation for every required queue/executor/cache/retry/result surface;
- all historical schema migration/upgrade/interruption/integrity/WAL/index checks;
- reload/shutdown automated lifecycle coverage;
- stable versioned Bukkit API finalization, replay/reload/restart tests/docs, binary/source compatibility;
- fixed-scenario performance/profile harness and committed threshold evidence;
- static-analysis/complexity remediation;
- RC version/artifact/reproducibility/package smoke/release workflow/release notes;
- full-package harsh and independent review, all fixes, exact-head CI/Codacy and review resolution;
- normal merge, post-merge `main` verification, and verified `v1.0.0-rc.1` prerelease/assets.

## Findings
No competing worker or production defect was established during reconciliation.

## Blocker
None. WP-04 is actively resumed as `IN_PROGRESS`.

## Exact next action
Implement the reusable deterministic failure-injection/restart harness and first complete durable state-machine test group. Keep all work on draft PR #15 and do not start WP-05.
