# Handoff 0003 — bounded storage runtime and durable delivery intent

## Session metadata
- Date/time: 2026-08-02 18:56 America/Indiana/Indianapolis
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `c6647529503be8e94dc26091370de0e0973b2e57`
- Session status: in progress

## Objective

Continue the exact unfinished foundation step from handoff 0002 without opening another branch or pull request: add immutable validated configuration and atomic reload boundaries, a bounded SQLite worker and lifecycle, degraded startup behavior, repository/CAS foundations, and focused restart/idempotency tests. Later gameplay phases remained out of scope.

## Work completed

- Added immutable `FoundationConfiguration` with explicit safe ranges and defaults.
- Added `AtomicConfiguration`, which validates a complete candidate before replacing the active snapshot. Database busy timeout, database queue capacity, and shutdown timeout are restart-only settings and are rejected during reload.
- Added a strict dependency-free configuration loader for the flat foundation `config.yml`. It creates packaged defaults off-thread and rejects unknown, duplicate, missing, malformed, and out-of-range settings.
- Added `MetricsPort`, `StorageState`, and a single-worker `BoundedDatabaseExecutor` backed by `ArrayBlockingQueue`. Saturation rejects work instead of growing memory without a bound.
- Added `SQLiteStorageRuntime` with off-thread open/migration, explicit `READ_WRITE` and `DEGRADED_READ_ONLY` states, startup/shutdown fencing, and bounded shutdown draining.
- Reworked plugin startup around a stable delegating Bukkit service. Calls remain unavailable until writable storage is active; storage failures do not publish a write-capable service.
- Added repository ports and initial SQLite implementations for active definition lookup and direct-delivery persistence.
- Added transactional external delivery acceptance. One transaction creates the lore instance, pending direct-delivery row, and external-operation result.
- Added durable external-operation idempotency. Replaying the same operation and arguments returns `ALREADY_ACCEPTED` without creating another instance or delivery. Reusing the operation ID with different arguments returns validation failure.
- Added bounded pending-delivery claims, claim tokens, lease expiry, and compare-and-set state transitions.
- Tightened claim fencing after review: transitions require both the correct token and an unexpired lease. Expired `RESERVED`, `APPLIED`, or `VERIFIED` claims move to `REVIEW_REQUIRED` instead of being silently retried after a possible side effect.
- Wired startup recovery before publishing the durable service.
- Kept physical inventory insertion explicitly inactive. `ACCEPTED_QUEUED` means durable intent exists only.
- Fixed the Gradle resource-processing configuration-cache failure without disabling configuration-cache verification.
- Added GitHub Actions concurrency so superseded PR runs cancel instead of consuming the queue.
- Updated operator/development documentation for configuration, storage lifecycle, idempotency, recovery, and current limitations.

## Important decisions and invariants

- No Bukkit inventory or item mutation occurs in this slice.
- SQLite work is serialized through one bounded executor; no common pool or unbounded queue is used.
- Storage startup failure is fail-closed for writes. The service reports unavailable rather than claiming durable acceptance.
- Reload replaces one immutable validated snapshot and never recreates executors or closes the database.
- Delivery acceptance and idempotency recording are one transaction.
- A claimed workflow step is fenced by expected state, claim token, and live lease.
- Expired claims after restart become `REVIEW_REQUIRED`, including claims that had reached `APPLIED` or `VERIFIED`.
- The existing branch and draft PR were retained. Nothing was merged.

## Files or modules changed

Primary application additions:

- `application/src/main/java/net/enthusia/loreitems/application/FoundationConfiguration.java`
- `application/src/main/java/net/enthusia/loreitems/application/AtomicConfiguration.java`
- `application/src/main/java/net/enthusia/loreitems/application/MetricsPort.java`
- `application/src/main/java/net/enthusia/loreitems/application/StorageState.java`
- `application/src/main/java/net/enthusia/loreitems/application/DefinitionRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/DirectDeliveryRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/PersistingExternalDeliveryUseCase.java`

Primary SQLite additions:

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/BoundedDatabaseExecutor.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteStorageRuntime.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java`

Primary plugin changes:

- `plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java`
- `plugin/src/main/java/net/enthusia/loreitems/plugin/FoundationConfigurationLoader.java`
- `plugin/src/main/java/net/enthusia/loreitems/plugin/FoundationLoreItemsService.java`
- `plugin/src/main/resources/config.yml`
- `plugin/build.gradle.kts`

Tests and support:

- `application/src/test/java/net/enthusia/loreitems/application/AtomicConfigurationTest.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepositoryTest.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteStorageRuntimeTest.java`
- `plugin/src/test/java/net/enthusia/loreitems/plugin/FoundationConfigurationLoaderTest.java`
- `.github/workflows/ci.yml`
- `docs/development.md`

## Persistence, state-machine, or API changes

- The V1 schema was reused; no migration version was added.
- External delivery requests now have a production transaction path using existing `lore_instances`, `direct_deliveries`, and `external_delivery_requests` tables.
- Direct deliveries can be claimed from `PENDING` to `RESERVED` in bounded pages.
- Claimed transitions use expected state, claim token, and `claim_expires_at > now` as the compare-and-set predicate.
- Expired claimed states `RESERVED`, `APPLIED`, and `VERIFIED` are recovered to `REVIEW_REQUIRED` with claim fields cleared.
- The public Bukkit service can now return `ACCEPTED_QUEUED`, `ALREADY_ACCEPTED`, `UNKNOWN_DEFINITION`, `VALIDATION_FAILURE`, or `SERVICE_UNAVAILABLE` based on durable storage results. It still never inserts an item.

## Verification actually performed

Live GitHub Actions run 45, workflow run `30771136092`, job `91558378495`, checked out PR merge commit `a765811ca2b0159149ec549a8a8c39ddd31ea3eb` containing implementation head `c6647529503be8e94dc26091370de0e0973b2e57` and ran:

```text
gradle --no-daemon clean check
```

Actual result:

- `BUILD SUCCESSFUL in 57s`
- 31 actionable tasks: 23 executed, 8 up-to-date.
- `domain:test` passed.
- `application:test` passed.
- `adapters-sqlite:test` passed.
- `plugin:test` passed.
- `architecture-tests:test` passed.
- Configuration cache entry stored successfully.

The immediately preceding run 44 compiled and passed all tests but failed only while storing the configuration cache because `:plugin:processResources` serialized a Gradle script reference. The resource expansion was changed to remove that closure, and run 45 verified the correction.

Focused tests now directly cover:

- immutable configuration bounds and reload-only versus restart-only changes;
- strict configuration loading and unknown-key rejection;
- external-operation idempotency and one-instance/one-delivery persistence;
- claim-token and live-lease fencing;
- restart recovery of an expired claim to `REVIEW_REQUIRED`;
- degraded read-only startup when SQLite cannot open.

No live Paper/Leaf server was started, and no inventory behavior was exercised.

## Live automation observed

At the implementation head before this report:

- PR #2 was open, mergeable, and still a draft.
- GitHub Actions CI run 45 passed.
- CodeRabbit continued to skip substantive review because the PR is a draft.
- There were no submitted pull-request reviews and no unresolved review threads.
- Codacy's latest visible bot summary was **Not up to standards** with 61 new issues: 5 critical, 21 high, and 35 medium. The summary was updated at 2026-08-02T22:52:27Z, before implementation head `c664752...`; therefore a fresh Codacy result for the latest head was not yet visible when this report was written.

## Unresolved risks or missing evidence

- Codacy is currently red. The exact issue list was not exposed by the GitHub comment/connector, so the critical and high findings have not been classified as real findings, stale analysis, tool incompatibilities, or false positives.
- The remaining PR 1 repository families are not implemented: full definition/revision persistence, observations/current state/anomalies, pending mutations, campaigns/recipients, deleted markers, and audit-event repositories.
- Paper item-template serialization and PDC round-trip tests remain absent.
- The no-op metrics port establishes instrumentation boundaries but does not yet export operator-visible metrics.
- There is no live-server startup, reload, shutdown, corrupt-database, or Paper item round-trip evidence.
- The PR remains intentionally incomplete and must not be merged.

## Exact next step

Stay on PR #2 and `agent/loreitems-pr1-foundation`. First obtain the current Codacy result and exact issue details for the latest head, then classify and fix every real critical/high finding that applies to the PR 1 foundation. Do not suppress findings merely to turn the gate green, and do not begin creation/adoption, physical delivery, protection, tracking, GUIs, editing, deletion execution, or campaign execution.

After the Codacy blocker is understood, continue the unfinished PR 1 persistence scope with bounded repository ports and SQLite implementations, beginning with pending mutations and audit events because those establish the generic claim/CAS and durable audit patterns needed by the remaining workflows.

## Required prior reports

- [`0001-2026-08-02-pr2-foundation-start.md`](0001-2026-08-02-pr2-foundation-start.md) — original PR 1 scope, migration design, architecture boundaries, and deferred foundation work.
