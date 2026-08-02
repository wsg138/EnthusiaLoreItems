# Handoff 0005 — definition, revision, and instance persistence

## Session metadata
- Date/time: 2026-08-02 19:52 America/Indiana/Indianapolis
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `820218129c32f775957680e880fbcad7b8676260`
- Session status: in progress

## Objective

Continue the exact next PR 1 persistence slice recorded in handoff 0004 without opening another branch or pull request: complete immutable definition/revision and lore-instance domain models, application repository ports, SQLite implementations, transactional definition creation and revision promotion, active/deleted lookup behavior, bounded reads, and focused uniqueness/rollback/restart tests.

## Work completed

- Added immutable `LoreDefinition`, `LoreDefinitionRevision`, `LoreInstance`, and `LoreInstanceLifecycle` domain types.
- Defensively copied serialized template bytes at construction and access boundaries and bounded one serialized template to 4 MiB.
- Expanded `DefinitionRepository` from active-key ID lookup into a bounded definition/revision persistence port.
- Added `InstanceRepository` for immutable instance creation, bounded definition-scoped reads, revision compare-and-set, and lifecycle compare-and-set.
- Replaced the lookup-only SQLite definition adapter with full transactional definition and revision persistence.
- Definition creation now inserts the definition and initial revision in one SQLite transaction and requires both to begin at revision 1.
- Revision append now requires the exact next revision, compare-and-sets `current_revision`, inserts the immutable revision in the same transaction, and rolls the promotion back if revision insertion fails.
- Active-key lookup excludes deleted definitions; by-ID lookup retains deleted definitions for historical and recovery use.
- Deletion marking is compare-and-set against the expected current revision and does not execute physical deletion.
- Added bounded, deterministic pages for active definitions, definition revisions, and definition-scoped instances.
- Added `SQLiteInstanceRepository` with unique instance identity, revision integrity, monotonic revision compare-and-set, and one-way active-to-terminal lifecycle transitions.
- Strengthened the V1 `lore_instances` schema with checked lifecycle values, terminal timestamp consistency, and composite foreign keys tying applied and desired revisions to the same definition's immutable revisions.
- Added a package-private reusable SQLite transaction helper. It participates in an existing transaction when one is supplied, but no application `UnitOfWork` port or atomic audited application workflow is claimed yet.
- Did not add commands, held-item creation/adoption, physical inventory delivery, protection listeners, tracking/reconciliation execution, GUIs, editing execution, deletion execution, or campaign execution.

## Important decisions and invariants

- A definition and its initial revision either commit together or neither exists.
- A definition revision is immutable and can advance only from expected revision `N` to exactly `N + 1`.
- Concurrent or stale revision promotion returns `false`; it does not overwrite a newer revision.
- Deleted definitions remain available by internal ID but disappear from active-key and active-page reads. The partial unique active-key index allows a replacement active definition to reuse a deleted key.
- Instance `applied_revision` and `desired_revision` must refer to revisions belonging to the same definition.
- Instance revisions never move backward, and desired revision never precedes applied revision.
- Instance lifecycle is one-way from `ACTIVE` to `VOID_DESTROYED` or `REMOVED`; terminal records require a terminal timestamp.
- All collection reads added in this slice use `PageRequest`/`Page` and a `limit + 1` query rather than returning unbounded lists.
- The SQLite transaction helper is an adapter building block only. It is not evidence that future business-state changes and audit events are atomic.

## Files or modules changed

Domain:

- `domain/src/main/java/net/enthusia/loreitems/domain/LoreDefinition.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/LoreDefinitionRevision.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/LoreInstance.java`
- `domain/src/main/java/net/enthusia/loreitems/domain/LoreInstanceLifecycle.java`
- `domain/src/test/java/net/enthusia/loreitems/domain/LorePersistenceModelTest.java`

Application:

- `application/src/main/java/net/enthusia/loreitems/application/DefinitionRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/InstanceRepository.java`

SQLite:

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteInstanceRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteTransactions.java`
- `adapters-sqlite/src/main/resources/db/migration/V1__foundation.sql`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepositoryTest.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteInstanceRepositoryTest.java`

## Persistence, state-machine, or API changes

- `DefinitionRepository` now supports transactional creation, internal and active lookup, immutable revision lookup/history, bounded active listing, monotonic revision append, and deletion marking.
- Added the `InstanceRepository` persistence port.
- Added checked instance lifecycle values: `ACTIVE`, `VOID_DESTROYED`, and `REMOVED`.
- Added terminal timestamp consistency and definition/revision composite foreign keys to `lore_instances` in the still-unmerged V1 migration.
- No public Bukkit service API changed.
- No migration version was added because V1 has not been released or merged.

## Verification actually performed

GitHub Actions CI run 75 (`30773028612`), job `91563532948`, checked out PR merge commit `95b5a575c1ecb3924a71734bde5847f748f250c7` containing implementation head `820218129c32f775957680e880fbcad7b8676260` and ran:

```text
gradle --no-daemon clean check
```

Actual result:

- `BUILD SUCCESSFUL in 1m 6s`
- 31 actionable tasks: 23 executed, 8 up-to-date.
- `domain:compileJava` and `domain:test` passed with `-Xlint:all -Werror` configured.
- `application:compileJava` and `application:test` passed.
- `adapters-sqlite:compileJava` and `adapters-sqlite:test` passed.
- `plugin:test` passed.
- `architecture-tests:test` passed.
- Configuration cache entry stored successfully.

Focused tests directly cover:

- immutable template-byte defensive copying and domain invariants;
- duplicate active-key rejection and key reuse after deletion;
- initial definition/revision transaction rollback through forced SQLite failure;
- revision-promotion rollback through forced SQLite failure;
- stale revision compare-and-set rejection;
- active versus deleted lookup behavior;
- bounded definition, revision, and instance pages;
- instance UUID uniqueness and definition/revision foreign-key integrity;
- instance revision and lifecycle compare-and-set behavior;
- definition, revision, and terminal instance state after storage restart.

No live Paper/Leaf server was started, and no Bukkit item serialization, PDC, inventory, or player behavior was exercised.

## Live automation observed

At implementation head `820218129c32f775957680e880fbcad7b8676260`:

- PR #2 was open, mergeable, and still a draft.
- GitHub Actions CI run 75 passed.
- CodeRabbit reported success while skipping substantive review because the PR remains a draft.
- There were no submitted pull-request reviews and no unresolved review threads.
- Codacy's latest visible bot summary remained **Up to standards** with `0` new issues, but its timestamp preceded this implementation slice. A current Codacy result for implementation head `8202181...` was not yet visible when this report was created, so the previous green summary is not claimed as validation of these new changes.

## Unresolved risks or missing evidence

- No application `UnitOfWork` path yet proves that an authoritative state transition and its audit event commit or roll back together. The new adapter transaction helper does not by itself solve this application-level requirement.
- Codacy had not refreshed for the reported implementation head when this report was created.
- Observation/current-state/anomaly, campaign/recipient, and deleted-marker repositories remain incomplete.
- The direct-delivery repository still contains its own private transaction helper and direct instance insert path; transaction infrastructure has not yet been consolidated behind a tested application unit of work.
- Paper item-template serialization and hidden-PDC round-trip tests remain absent.
- Operator-visible metrics remain a no-op boundary.
- There is no live-server startup, reload, shutdown, corrupt-database, or Paper item round-trip evidence.
- The PR remains intentionally incomplete and must not be merged.

## Exact next step

Stay on PR #2 and `agent/loreitems-pr1-foundation`. Implement only a tested application `UnitOfWork` boundary and SQLite adapter that can atomically compose authoritative repository changes with audit-event persistence. Prove successful commit and full rollback with one focused in-scope workflow or transaction composition test, and consolidate transaction helpers where practical without broad unrelated refactoring.

Do not begin observation/current-state/anomaly persistence until the unit-of-work path is verified. Do not begin commands, item creation/adoption, physical inventory delivery, protection listeners, tracking/reconciliation execution, GUIs, editing execution, deletion execution, or campaign execution.

## Required prior reports

- [`0004-2026-08-02-pr2-mutation-audit.md`](0004-2026-08-02-pr2-mutation-audit.md) — pending-mutation and append-only audit persistence, claim fencing, and the unresolved atomic state-plus-audit requirement.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded SQLite runtime, external-delivery transaction behavior, startup recovery, and storage lifecycle decisions.
