# Workspace state

## Snapshot warning

This file is a committed coordination snapshot, not an authority over live GitHub. Every agent must refresh live state before routing or relying on it. Canonical branch and PR presence outranks stale queue text.

## Active package claim

- Repository: `wsg138/EnthusiaLoreItems`
- Verified starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Active package: WP-02 — destructive administration
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-02-destructive-administration`
- Draft pull request: #13, `WP-02: complete destructive administration`
- Initial claim checkpoint: `2612d40607916414f06d4d6a46aef3d887bafc89`
- Current implementation head: `2e6bedfb8bcbc949739ea2ff7b514edbc1b1bf34`
- Dependency verification: WP-01 is normally merged through live `main` merge commit `50ac248b1583739c57b7dcb25b4e949436b736ce`; historical WP-01 head `08f92b8b185e4022154bc34a01d30f3123c6043b` is contained in `main`

## Package status

| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| WP-02 | 20% | IN_PROGRESS | Durable destructive-operation core is green; Paper execution and operator administration remain |
| WP-03 | 20% | BLOCKED | WP-02 is not COMPLETE |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

## Counts and weighted progress

- Fixed package count: 6
- Completed packages: 1 of 6
- Remaining packages: 5 of 6
- Active package: WP-02
- Weighted progress: `20 / 100 = 20%`

No weighted credit is assigned to WP-02 while it is `IN_PROGRESS`.

## Completed acceptance criteria in this checkpoint

- Established the exact WP-02 branch and draft PR as the durable claim from verified live `main`.
- Added explicit destructive operation, target, and physical-effect state models.
- Added a forward-only V5 SQLite migration with parent operations, immutable target snapshots, active-target uniqueness, claim leases, evidence fields, and database-enforced immutable identity fields.
- Added idempotent preview/confirmation and atomic acceptance for exact removal, purge, and full-definition deletion.
- Full deletion atomically excludes the definition and creates the durable deleted-definition marker.
- Added paginated operation and target inspection, parent pause/resume fencing, queue metrics, evidence-gated review resolution, bounded expired-claim recovery, verified instance removal, and late-copy reopen/create behavior.
- Added integration tests for idempotent full deletion, pause fencing, verified completion, exact-target movement review, ambiguous claim expiry, and late-copy deletion handling.
- Repaired all compile, migration-regression, complexity, static-analysis, and Codacy findings found during this section.

## Tests and verification

- Exact head: `2e6bedfb8bcbc949739ea2ff7b514edbc1b1bf34`
- GitHub Actions run: `31077567338`
- Gradle verification: passed
- Repository tooling: passed
- Differential complexity gate: passed
- New-code coverage gate: passed
- Exact-head Codacy CLI: passed
- PR review threads at checkpoint: none
- Live Paper/Leaf server behavior: not yet claimed

## Harsh-review findings fixed

- A `ResultSet.wasNull()` ordering defect could have treated active definitions as deleted; the deleted flag is now captured before reading later columns.
- Runtime-built SQL variants caused static-analysis ambiguity; destructive queries now use fixed prepared statements.
- Oversized persistence classes and methods exceeded repository complexity limits; responsibilities were split into query, acceptance, control, claim, and completion stores.
- Expired physical claims are moved to `REVIEW_REQUIRED` with `AMBIGUOUS` evidence instead of being blindly retried.
- Java Codacy findings were removed without excluding Java production code; V5 joined the repository's established SQLite-migration analyzer exclusion while retaining its database triggers.

## Remaining acceptance criteria

- Integrate destructive preparation and verified physical removal into the existing natural-access Paper scanner across every required inventory/entity/nested scope.
- Preserve duplicate and malformed evidence while ensuring destructive work precedes template updates.
- Wire bounded recovery into lifecycle, reload, and shutdown.
- Add privileged command and GUI actions, operation-specific confirmations, permissions, messages, paginated administration, metrics display, pause/resume, and evidence review resolution.
- Add Paper, command, GUI, reload/restart, and migration/recovery tests; update documentation; perform full-package harsh review; resolve exact-head CI/Codacy/review findings; normally merge and verify live `main`.

## Exact next action

Extend the existing reload-safe Paper references with location evidence and verified removal, then add a destructive-first coordinator that routes non-target candidates into the unchanged template-update pipeline.