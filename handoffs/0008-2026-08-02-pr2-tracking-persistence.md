# Handoff 0008 — observation, current-state, and anomaly persistence

## Session metadata
- Date/time: 2026-08-02 21:08 America/Indiana/Indianapolis
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `d60c93c185d88a9d47fe65a6f61387dbf0f6de7d`
- Session status: in progress

## Objective

Continue only the exact next PR 1 repository family recorded in handoff 0007: add bounded, platform-free observation, current-state, and anomaly models and ports; implement their SQLite persistence; strengthen the still-unmerged V1 constraints; and prove restart, uniqueness, compare-and-set, lifecycle, and paging behavior without beginning tracking execution or later player-facing phases.

## Work completed

- Added a platform-free `LocationDescriptor` with bounded location keys, bounded optional nested-container paths, and explicit storage/location categories.
- Added immutable `InstanceObservation` evidence records with generated persistent identifiers, instance/definition identity, confidence, source, timestamp, nested path support, and terminal-void consistency rules.
- Added `InstanceCurrentState` as a revisioned projection that distinguishes confirmed-now, last-confirmed, conflicting, terminal-void, and initially missing/unresolved states.
- Added `InstanceAnomaly` with bounded detail, explicit anomaly types, `OPEN -> ACKNOWLEDGED -> RESOLVED` lifecycle metadata, and a monotonic state revision.
- Added bounded application ports: `ObservationRepository`, `CurrentStateRepository`, and `AnomalyRepository`. Collection reads use `PageRequest`/`Page`; no unbounded list-returning method was introduced.
- Added `SQLiteObservationRepository` with append-only generated IDs and deterministic newest-first pages by instance and location.
- Added `SQLiteCurrentStateRepository` with transactional validation that the selected observation belongs to the same instance, has the same location, has compatible confidence, and does not postdate the projection. Updates compare-and-set the expected projection revision and require a newer observation ID and nondecreasing timestamp.
- Added `SQLiteAnomalyRepository` with active-identity uniqueness, refresh compare-and-set, acknowledgement, resolution, bounded active pages, and bounded instance history.
- Strengthened V1 with composite instance/definition and observation/instance foreign keys, checked observation location/confidence values, terminal-void consistency, revisioned current state, checked anomaly types/statuses, lifecycle metadata, and indexed active anomaly uniqueness/history.
- Added focused SQLite integration tests for observation paging, location lookup, instance/definition integrity, current-state uniqueness and stale compare-and-set rejection, anomaly active uniqueness, refresh/acknowledge/resolve lifecycle, bounded history, and restart restoration.
- Did not add Paper listeners, scope scanning, reconciliation execution, commands, GUIs, item creation/adoption, physical delivery, protection behavior, editing, deletion execution, or campaign execution.

## Important decisions and invariants

- Observations are immutable evidence and do not claim omniscient current location.
- A current-state projection references durable observation evidence; adapter writes fail closed when identity, location, confidence, or time does not match.
- Current-state changes use an explicit monotonic projection revision so stale writers return `false` rather than overwriting newer evidence.
- A projection that already has durable observed evidence cannot be changed through this port into an evidence-free missing state. Later reconciliation policy must preserve last-confirmed evidence rather than erase it.
- Only one active anomaly may exist for the same anomaly type, instance identity, and definition. A resolved anomaly remains historical, and a later recurrence may create a new anomaly identity.
- Acknowledged anomalies remain active and refreshable until explicitly resolved.
- Domain/application code remains free of Bukkit, JDBC, YAML, GUI, and filesystem dependencies.
- SQLite work continues through the existing single bounded database executor. Current-state validation and mutation use one connection and the shared transaction helper.
- No authoritative gameplay side effect or audited application workflow was added, so the `UnitOfWork` transaction context did not need expansion in this slice.

## Files or modules changed

Domain:

- `domain/src/main/java/net/enthusia/loreitems/domain/LocationDescriptor.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/InstanceObservation.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/InstanceCurrentState.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/InstanceAnomaly.java`

Application:

- `application/src/main/java/net/enthusia/loreitems/application/ObservationRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/CurrentStateRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/AnomalyRepository.java`

SQLite:

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteObservationRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteCurrentStateRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteAnomalyRepository.java`
- `adapters-sqlite/src/main/resources/db/migration/V1__foundation.sql`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteTrackingRepositoriesTest.java`

## Persistence, state-machine, or API changes

- Added append-only observation persistence with generated IDs and bounded instance/location reads.
- Added one revisioned current-state row per instance, linked to an observation belonging to that instance.
- Added anomaly lifecycle values `OPEN`, `ACKNOWLEDGED`, and `RESOLVED`, including acknowledgement and resolution metadata.
- Added active anomaly uniqueness across anomaly type, optional instance, and definition while retaining resolved history.
- Added checked location, observation confidence, current-state, and anomaly values to the still-unmerged V1 migration.
- Added composite foreign keys preventing an observation or anomaly from pairing an instance with the wrong definition.
- No public Bukkit service, command, listener, or player-facing API changed.

## Verification actually performed

GitHub Actions CI run 102 (`30776049224`), job `91571678414`, checked out PR merge commit `2ba578f273b86038c7e45ddda30895cb32d4c76d` containing implementation head `d60c93c185d88a9d47fe65a6f61387dbf0f6de7d` and ran:

```text
gradle --no-daemon clean check
```

Actual result:

- `BUILD SUCCESSFUL in 1m 2s`
- 31 actionable tasks: 23 executed, 8 up-to-date.
- `domain:compileJava` and `domain:test` passed under `-Xlint:all -Werror`.
- `application:compileJava` and `application:test` passed.
- `adapters-sqlite:compileJava` and `adapters-sqlite:test` passed, including `SQLiteTrackingRepositoriesTest`.
- `plugin:test` passed.
- `architecture-tests:test` passed.
- Configuration cache entry stored successfully.

Before repository writes, the proposed main sources were also compiled locally with Java 21 `javac -Xlint:all -Werror` against focused stubs, and the revised V1 schema plus active-anomaly uniqueness were exercised with Python's SQLite driver. GitHub Actions remains the authoritative whole-project build/test evidence.

No live Paper/Leaf server was started. No Bukkit item serialization, PDC, player, inventory, natural chunk, reload, shutdown, or corrupt-database behavior was exercised.

## Live automation observed

At implementation head `d60c93c185d88a9d47fe65a6f61387dbf0f6de7d`:

- PR #2 was open, mergeable, and remained a draft.
- GitHub Actions CI run 102 passed the complete Gradle check.
- CodeRabbit reported success while skipping substantive review because the PR remains a draft.
- There were no submitted reviews and no unresolved review threads.
- The latest visible Codacy comment still reported **Up to standards** with `0` new issues, but its update timestamp preceded this implementation slice. A refreshed Codacy result for implementation head `d60c93c...` was not visible when this report was created, so the earlier green summary is not claimed as final-head validation.

## Unresolved risks or missing evidence

- Codacy had not refreshed for the implementation head when this report was created.
- Campaign/recipient and deleted-marker persistence remain incomplete.
- The current persistence layer records evidence and projections but does not yet execute Paper tracking/reconciliation; that belongs to later implementation phases.
- Explicit anomaly resolution commands and audit composition are not implemented. When those workflows are added, authoritative state and audit persistence must use the verified `UnitOfWork` path.
- `SQLiteDirectDeliveryRepository` still has its older private transaction helper; future consolidation must preserve existing idempotency and claim fencing.
- Paper item-template serialization and hidden-PDC round-trip tests remain absent.
- Operator-visible metrics remain a no-op boundary.
- There is no live-server startup, reload, shutdown, corrupt-database, Paper item round-trip, or natural-access reconciliation evidence.
- CodeRabbit has not performed substantive review because the PR remains draft.
- PR #2 remains intentionally incomplete and must not be merged.

## Exact next step

Stay on PR #2 and `agent/loreitems-pr1-foundation`. Implement only the next PR 1 repository family: distribution campaign and recipient domain/application persistence plus SQLite adapters and focused tests for source-fingerprint uniqueness, immutable recipient snapshots, campaign state transitions, recipient claim fencing, cancellation semantics, unresolved-name binding, bounded paging/counts, and restart recovery.

Do not begin group-file parsing or moves, physical campaign delivery, commands, GUIs, item creation/adoption, protection listeners, tracking/reconciliation execution, editing, deletion execution, or later phases. Leave deleted-definition marker persistence for the following focused repository slice unless campaign work exposes a direct prerequisite.

## Required prior reports

- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified unit-of-work boundary, final prior CI/Codacy state, and PR 1 limitations.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition/revision/instance persistence and identity constraints used by observations and anomalies.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, storage lifecycle, connection ownership, and recovery rules.
