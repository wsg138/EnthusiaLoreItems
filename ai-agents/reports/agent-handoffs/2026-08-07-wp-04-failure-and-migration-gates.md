# WP-04 coherent checkpoint — direct-delivery failure and migration gates

## Package
- WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Verified implementation head: `d42684074788ebfbf9dbfaef6111a03254f99bdd`

## Completed acceptance criteria in this checkpoint
### Direct/API delivery crash/restart group
- Added a reusable test-only SQLite runtime restart/failure-injection harness using real migrations and real durable transactions.
- Verified failure before durable intent commit rolls back instance/delivery/external-request creation and retry creates exactly one delivery.
- Verified restart after durable intent but before claim replays the idempotency key and claims only the original delivery.
- Verified restart after a claim expires cannot blindly replay an ambiguous delivery; it transitions to `REVIEW_REQUIRED` and projects unresolved state.
- Verified failure during the verification/terminal transaction rolls back the partial verification commit and restart recovery escalates the expired claim to review.
- Verified restart after terminal completion does not requeue or duplicate the completed delivery.
- Verified at the Paper boundary that a physical inventory insert followed by failed durable completion is escalated to review rather than blindly replayed.

### Migration/upgrade group
- Added production-runner support for constructing each committed historical schema version in tests without maintaining fake copied schemas.
- Verified populated upgrades from V1, V2, V3, V4, V5, V6, and V7 to current schema.
- Preserved durable definitions/revisions, instance identity, observations/current state, pending work, deleted markers, audit history, campaigns, recipients, and campaign revision snapshots.
- Verified SQLite `integrity_check`, `foreign_key_check`, WAL mode, busy timeout, and critical migration indexes.
- Forced an interrupted V7 migration after partial DDL/data-copy execution, verified rollback leaves V6/history/data intact, then removed the injected conflict and verified retry succeeds.

## Tests and exact-head verification
- Final implementation head for this checkpoint: `d42684074788ebfbf9dbfaef6111a03254f99bdd`.
- GitHub Actions CI run `31182331548`: `completed/success`.
- `verify` job passed Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- Combined commit status includes CodeRabbit `success`.
- No local build result is claimed because the runtime cannot resolve GitHub for a dependency-capable checkout.

## Harsh-review findings and fixes
1. Java compilation rejected a reassigned repository captured in assertion lambdas; split before/after-restart repository references.
2. New-code complexity rejected two failure-matrix test methods above the 50-NLOC gate; extracted focused helpers, no threshold suppression.
3. Codacy reported test-harness field/method naming collisions and nullable-runtime lifecycle smells; renamed fields/methods and made closed-state explicit.
4. Codacy reported repeated migration-fixture literals and an inline historical schema version; introduced named constants/helper logic, no broad suppression.

## Remaining acceptance criteria
- The completed failure group covers direct/API delivery only; all other durable state machines and reload/shutdown crash boundaries still require the full matrix plus fixed-seed randomized recovery.
- Saturation/backpressure verification for all required bounded work surfaces.
- Reload/shutdown lifecycle automation.
- Stable versioned Bukkit API contract tests/docs and source/binary compatibility.
- Fixed-scenario profiling/performance thresholds and evidence.
- RC packaging/version/SBOM/manifest/checksum/reproducibility/package smoke/release workflow/release notes.
- Full-package independent harsh review, exact-head green checks and review resolution.
- Normal merge, post-merge `main` verification, and `v1.0.0-rc.1` prerelease verification.

## Blocker
None.

## Exact next action
Inventory and test every bounded-work surface named by WP-04 for saturation, capacity, rejection/defer policy, bounded retries/results, and metrics. Commit that coherent section on the same branch/PR; do not start WP-05.
