# Handoff 0011 — deleted-definition marker persistence

## Session metadata
- Date/time: 2026-08-02 22:15 EDT
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `9bbe434232a0f28231660860bf776a21dbc091c0`
- Session status: in progress

## Objective

Continue the exact next step from handoff 0010: implement only deleted-definition marker domain/application persistence, the SQLite adapter, unit-of-work composition, and focused tests. Preserve immutable deleted-definition history, bounded reads, existing soft-delete behavior, and bounded SQLite connection ownership. Do not begin a later implementation phase.

## Work completed

- Added the platform-free `DeletedDefinitionMarker` record containing only the definition ID, historical normalized lookup key, and deletion timestamp.
- Added `DeletedDefinitionMarkerRepository` with:
  - idempotent marker creation;
  - lookup by definition ID;
  - deterministic bounded recent-history paging;
  - deterministic bounded lookup-key history paging.
- Added `SQLiteDeletedDefinitionMarkerRepository` using the existing bounded `SQLiteStorageRuntime` connection/executor boundary.
- Marker creation uses `INSERT OR IGNORE ... SELECT` from the matching soft-deleted `lore_definitions` row. An exact existing marker is accepted as an idempotent replay; a missing or conflicting marker fails closed.
- Extended the application `UnitOfWork` and `SQLiteUnitOfWork` so definition soft-delete, audit append, and marker persistence can share one transaction and connection.
- Hardened the unreleased V1 schema with deleted-marker key/time checks, recent/history indexes, and triggers that reject marker updates or deletes.
- Added focused domain, repository, restart, paging, immutability, idempotent replay, active-definition rejection, and transaction-rollback tests.

## Important decisions and invariants

- A marker is historical deletion evidence, not an active definition. It is keyed by immutable definition ID; lookup-key reuse by a later definition does not collapse earlier history.
- Marker persistence is allowed only when definition ID, lookup key, and deletion timestamp exactly match a soft-deleted definition row.
- Replaying the exact marker is safe and idempotent. A conflicting replay is rejected rather than overwritten.
- Marker records cannot be updated or deleted through SQLite.
- All marker list operations remain explicitly paged and deterministically ordered.
- Repository methods do not own long-lived connections. The storage runtime opens and closes normal repository connections; the unit-of-work adapter reuses only the callback-scoped transaction connection.
- No physical deletion, late-returning item removal, command, GUI, listener, scheduler, item codec, group-file, or campaign execution behavior was added.

## Files or modules changed

- `domain/src/main/java/net/enthusia/loreitems/domain/DeletedDefinitionMarker.java`
- `domain/src/test/java/net/enthusia/loreitems/domain/DeletedDefinitionMarkerTest.java`
- `application/src/main/java/net/enthusia/loreitems/application/DeletedDefinitionMarkerRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/UnitOfWork.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDeletedDefinitionMarkerRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteUnitOfWork.java`
- `adapters-sqlite/src/main/resources/db/migration/V1__foundation.sql`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDeletedDefinitionMarkerRepositoryTest.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDeletedDefinitionMarkerUnitOfWorkTest.java`

## Persistence, state-machine, or API changes

- Added a new application persistence port for deleted-definition markers.
- Extended the unit-of-work context with callback-scoped deleted-marker mutations.
- The V1 `deleted_definition_markers` table remains minimal and now has bounded-query indexes plus database-enforced update/delete immutability.
- Definition soft-delete state behavior is unchanged. The new unit-of-work path composes the existing compare-and-set soft-delete with audit and marker persistence.
- No public Bukkit service API was changed.

## Verification actually performed

- A focused local Java 21 compilation of the new model/port/adapter path used `--release 21 -Xlint:all -Werror` and passed.
- A focused local SQLite check exercised marker insertion plus the new update/delete triggers; the immutable mutations were rejected.
- GitHub Actions CI run 139 (`30778569619`), job `91578659780`, checked out PR merge commit `dc59c05554aa424b99e324489811952785f899ba` containing exact implementation head `9bbe434232a0f28231660860bf776a21dbc091c0`.
- The workflow ran `gradle --no-daemon clean check` and passed:
  - `BUILD SUCCESSFUL in 1m 7s`;
  - 31 actionable tasks: 23 executed, 8 up-to-date;
  - domain, application, SQLite adapter, plugin, and architecture tests passed;
  - compilation remained under the repository's `-Xlint:all -Werror` configuration.

## Live automation observed

At implementation head `9bbe434232a0f28231660860bf776a21dbc091c0`:

- PR #2 remained open, mergeable, unmerged, and draft.
- GitHub Actions CI run 139 passed.
- CodeRabbit commit status was success. Its PR comment remained `Review skipped` because the PR is draft and was last updated at `2026-08-03T02:08:43Z`.
- Codacy's PR comment was last updated at `2026-08-03T02:10:12Z` and still displayed `Not up to standards` with a 100-issue aggregate (`22 high`, `78 medium`). This is the same unstable aggregate pattern observed during earlier in-progress analyses, but no later green refresh was visible before this report. It must not be reported as passed.
- No submitted pull-request reviews were visible.
- No unresolved review threads were visible.

## Unresolved risks or missing evidence

- Codacy has not yet produced a stable final result for the implementation head. The connector exposes the aggregate comment but not the detailed issue list, so no claim is made that the 100 issues are real regressions or merely an incomplete analysis.
- No live Paper/Leaf server was started.
- No late-returning deleted physical item, full deletion workflow, hidden-PDC codec, reload, shutdown, backup/rollback, or corrupt-database scenario was validated on a real server.
- This slice provides durable marker persistence only. Physical deletion execution remains a later implementation-plan phase.

## Exact next step

Before changing another repository family, re-read live PR #2 state and recheck Codacy for the current exact branch head after the handoff commits. If Codacy remains red, obtain the stable detailed findings and fix only validated issues attributable to this marker slice. If Codacy has returned to `Up to standards` with zero new issues, record that evidence and then select the next still-in-scope PR 1 foundation task. Do not begin physical deletion, item creation/adoption, protection, tracking execution, GUI, group-file, campaign execution, or another implementation phase while Codacy status is unresolved.

## Required prior reports

- [`0010-2026-08-02-pr2-distribution-verification.md`](0010-2026-08-02-pr2-distribution-verification.md) — preceding exact-head green baseline and remaining PR 1 scope.
- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified transaction-context lifetime and rollback boundary.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition soft-delete and active-key/history behavior.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded executor, connection ownership, and recovery rules.
