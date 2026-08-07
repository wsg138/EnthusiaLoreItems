# WP-04 checkpoint — operator documentation and WP-05 matrix

## Package
- Package: WP-04 — automated production hardening and release candidate
- Status after this checkpoint: `PARTIAL`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Operator-guide commit: `5801d935ae10bc19472ea2818ed9c4cec5c747d7`
- WP-05 matrix commit: `c9784b744271384aac32ea7e145f38df1476cd49`

## Completed criteria in this checkpoint

### Operator/recovery documentation
Committed `docs/operator-guide.md` covering:

- installation and supported runtime;
- actual `config.yml` settings, startup-only versus reloadable behavior;
- actual permission nodes and command surfaces;
- operational safety invariants and backlog interpretation;
- offline backup and WAL-aware online-backup constraints;
- upgrade, restore, and rollback procedures;
- degraded/read-only recovery;
- queue and `REVIEW_REQUIRED` recovery;
- duplicate/malformed-stack review;
- deleted-definition marker/tombstone handling;
- database-authoritative campaign marker repair;
- staged deployment;
- incident evidence collection;
- GitHub-backed release/acceptance evidence rule.

The guide explicitly does not claim live Paper/Leaf acceptance.

### WP-05 executable manual acceptance matrix
Committed `docs/wp-05-manual-acceptance-matrix.md` with unique case IDs, shared evidence contract, prerequisites, exact operator/test steps, expected physical and durable/database results, evidence requirements, cleanup, and rollback actions.

The matrix covers the complete minimum WP-05 scope: Java/Floodgate identity; create/adopt/give/offline/full/restart; editor field matrix and replace-from-held; rollout across accessible/inaccessible/nested/entity/display holders; tracking categories; protection and void loss; duplicate/malformed/ambiguous review; exact removal/purge/delete/pause/resume/restart/late copy; distribution validation/identity/exactly-once/pause/cancel/marker repair; stable Bukkit API/idempotency; reload/shutdown; degraded startup; backup/restore/rollback; saturation metrics; and 100-player-equivalent staged load.

It also defines the mandatory regression subset after a WP-05 confirmed defect and the final all-PASS exact-jar gate.

## Verification performed
- Live GitHub branch/PR remained the durable package claim.
- PR #15 was open and draft before this section started.
- GitHub Actions CI run `31179217336` was triggered on exact code/documentation head `c9784b744271384aac32ea7e145f38df1476cd49` and was `in_progress` when this checkpoint was prepared.
- No local Gradle/build/test result is claimed; this runtime does not have a mounted dependency-capable checkout.
- No live Paper/Leaf behavior is claimed.

## Findings
- The previously committed package state/handoff still said the draft PR needed to be created even though PR #15 already existed; this checkpoint updates that stale routing metadata.
- No production-code defect was established in this documentation/matrix section.

## Remaining WP-04 criteria

1. Deterministic failure-injection/restart harness for every durable state machine and required interruption point.
2. Queue saturation/backpressure automation for all named queues/executors/caches/retries and bounded-result behavior.
3. Migration/upgrade tests from every committed schema version, including interrupted rollback/integrity/WAL/index checks.
4. Reload/shutdown automated lifecycle coverage.
5. Stable versioned Bukkit API contract finalization, replay/reload/restart tests, documentation, and binary/source compatibility check.
6. Fixed-scenario performance/profile harness plus committed raw/results meeting every threshold.
7. Static-analysis/complexity remediation and narrow-suppression review.
8. RC version/artifact work: `1.0.0-rc.1`, shaded jar, CycloneDX JSON SBOM, dependency manifest, checksum, test reports, release notes, reproducibility/package smoke checks, release workflow.
9. Full-package harsh/independent review and all confirmed fixes.
10. Exact-final-head GitHub Actions and Codacy success, zero requested changes/unresolved threads.
11. Normal merge, live `main` verification, and `v1.0.0-rc.1` prerelease/assets verified against merge SHA.

## Recommended next coherent section
Implement the automated persistence-hardening section first: inventory the durable state machines and existing test seams, add a reusable deterministic failure-injection/restart harness, then cover direct delivery/adoption/template rollout/destructive/campaign state machines in matrix order. Keep each state-machine group as a checkpoint and do not start WP-05.

## Exact next action
Resume PR #15 on `agent/wp-04-production-hardening`, re-fetch exact head/check/review state, then implement the deterministic failure-injection/restart harness and its first complete state-machine test group. Preserve `PARTIAL` until the entire WP-04 contract is complete.
