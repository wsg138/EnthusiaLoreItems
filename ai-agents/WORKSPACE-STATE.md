# Workspace state

## Snapshot warning

This file is a committed coordination snapshot. Live GitHub remains authoritative. Resolve conflicts using this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Publication state

- Repository: `wsg138/EnthusiaLoreItems`
- Verified starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Active package: WP-03 — one-use mass distributions
- Status: `PARTIAL`
- Canonical branch: `agent/wp-03-mass-distributions`
- Draft pull request: #14, `WP-03: complete one-use mass distributions`
- Initial claim commit: `b544ddb3bdb24c6ca95cdd2867af6b90d987ee46`
- Latest verified implementation head: `759896e5da61c46079a5e7c98154aa1852bc0f39`
- Exact next package after authoritative WP-03 completion: WP-04 — automated production hardening and release candidate

## Live reconciliation at claim

- WP-01 and WP-02 canonical branches are historical and contained in live `main`.
- WP-02 PR #13 was normally merged at live-main commit `d77ec61032e5583783694ae349f785495cbf8f31`.
- No unfinished WP-03/WP-04/WP-05/LoreItems-WP-06 canonical lock existed before this claim.
- PR #14 and `agent/wp-03-mass-distributions` remain the single unfinished package lock.
- No requested changes or unresolved PR review threads were present at the latest review-state check.

## Package status

| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| WP-02 | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| WP-03 | 20% | PARTIAL | Useful implementation is committed and verified on canonical PR #14; package contract is not complete |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

## Counts and weighted progress

- Fixed package count: 6
- Completed packages: 2 of 6
- Remaining packages: 4 of 6
- Weighted progress: `40 / 100 = 40%`
- Active incomplete work receives zero official weighted completion credit.

## WP-03 completed criteria

- Exact seven-state recipient domain/persistence model implemented with V6 upgrade migration.
- Safe group directory initialization and strict immediate-directory YAML discovery/validation implemented.
- Java names, leading-`*` Floodgate-style names, canonical UUIDs, original values, normalized duplicate rejection, bounded file/list limits, symlink/traversal rejection, deterministic source fingerprinting, and marker primitives implemented.
- Durable campaign snapshot now pins the exact selected definition revision through immutable V7 revision-snapshot persistence.
- Application start request validates contiguous immutable recipients and actor metadata.
- SQLite campaign start atomically verifies the selected active definition revision, fences prior source fingerprints, inserts campaign/revision/recipient snapshots, persists the start audit event, and activates the campaign before returning success.
- Replay of the same normalized source fingerprint returns the original campaign rather than creating a second durable campaign.
- Definition revision drift between preview and start rejects the whole transaction without a partial campaign.

## Tests and verification

- Filesystem/state implementation head `bfe248c70c1cdbee4f88b62eb073445e745b8785` passed CI run #863.
- Latest implementation head `759896e5da61c46079a5e7c98154aa1852bc0f39` passed CI run #868: Gradle verification, repository tooling, new-code complexity, and exact-head Codacy all succeeded.
- Focused tests cover group parsing/identity/duplicates/malformed input/traversal/symlinks/fingerprints/markers and atomic campaign start/revision pinning/audit/source replay/rollback on definition revision drift.

## Findings fixed in this session

- Stale post-WP-02 coordination state was reconciled against live GitHub.
- Foundation recipient-state names differed from the WP-03 contract; migrated safely.
- Campaign cancellation still targeted old recipient-state names; corrected.
- Migration test schema-version expectations were stale after V6/V7; corrected.
- Malformed UUID-shaped recipients could be accepted as names; corrected.
- Initial and follow-up Codacy maintainability findings were fixed; SQLite migrations use only the repository's pre-existing exact-path analyzer exclusion pattern.

## Remaining acceptance criteria

- Build the paginated Paper operator flow: reload/validate, inspect, select source plus active definition, immutable preview, explicit confirm, and permissions/messages.
- Perform cached/known-name resolution off the server thread without Mojang-network correctness dependency; persist unresolved names and implement atomic case-insensitive late-join binding including `*BedrockPlayer` semantics.
- Integrate campaign recipients with the existing hardened direct-delivery subsystem using the pinned revision and durable idempotency before physical insertion; synchronize recipient states without introducing a second competing physical-delivery protocol.
- Complete exactly-once delivery, offline/full-inventory persistence, bounded retry/backpressure, join/inventory wakeups, crash/review-required recovery, and instance linkage.
- Implement persisted status/pagination, pause/resume/cancel/completion, active/completed/cancelled marker reconciliation, startup resume, reload/degraded/shutdown behavior, metrics, audit coverage, and WP-02 queue/review integration.
- Add remaining application/SQLite/Paper/end-to-end/restart/regression tests and documentation.
- Run the required full-package harsh review, fix all confirmed findings, obtain final exact-head Actions/Codacy, resolve any later review feedback, normally merge, verify live `main`, then mark WP-03 COMPLETE and only WP-04 READY.

## Blocker

None. This is `PARTIAL`, not `BLOCKED`. A local checkout was unavailable because the container could not resolve GitHub, but authenticated repository tooling remained fully usable and did not prevent useful work.

## Exact next action

Resume PR #14 at its current head. Implement the Paper campaign coordinator/operator flow that converts a validated `GroupFileDefinition` plus explicitly selected active lore definition/revision into `DistributionCampaignStartRequest`, commits the DB-authoritative start first, then moves/repairs the active filesystem marker from durable campaign state. Do not start WP-04.
