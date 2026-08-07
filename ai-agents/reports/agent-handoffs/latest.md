# Latest agent handoff

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `PARTIAL`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Documentation/matrix implementation head: `c9784b744271384aac32ea7e145f38df1476cd49`
- Durable section handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-04-operator-docs-and-acceptance-matrix.md`

## Completed this section
- Added `docs/operator-guide.md` with installation/upgrade/configuration/permissions, WAL-aware backups, restore, degraded/read-only recovery, queue/review recovery, deleted-marker handling, campaign-marker repair, staged deployment, rollback, and incident collection.
- Added `docs/wp-05-manual-acceptance-matrix.md` with unique live case IDs, prerequisites, exact steps, expected physical and durable/database outcomes, evidence requirements, cleanup, rollback, defect-regression subset, and exact-final-jar release gate.
- Corrected stale routing metadata that still said the already-open draft PR needed to be created.

## Verification
- PR #15 is the canonical durable claim and remained draft/open.
- GitHub Actions CI run `31179217336` was triggered on exact documentation/matrix head `c9784b744271384aac32ea7e145f38df1476cd49`; it was still in progress when the section checkpoint was written.
- No local Gradle/build/test result is claimed because this runtime has no mounted dependency-capable checkout.
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
- Stale package metadata about PR creation was corrected.
- No production-code defect was established in the documentation/manual-matrix section.

## Blocker
None. `PARTIAL` records useful committed work while preserving the same package/branch/PR for the next worker.

## Exact next action
Resume draft PR #15, refresh live exact head/check/review state, then implement the reusable deterministic failure-injection/restart harness and the first complete durable state-machine test group. Keep all work in WP-04 and do not start WP-05.
