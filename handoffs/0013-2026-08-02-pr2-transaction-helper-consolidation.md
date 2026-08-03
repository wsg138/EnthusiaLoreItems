# Handoff 0013 — direct-delivery transaction helper consolidation

## Session metadata
- Date/time: 2026-08-02 22:52 EDT
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `aa4bf4ed9c06e5c731ff3d4b883cfbae2b36c524`
- Session status: in progress

## Objective

Complete only the exact next step from handoff 0012: remove the private transaction-helper duplication in `SQLiteDirectDeliveryRepository` and route its transactional operations through the shared `SQLiteTransactions.inTransaction` helper. Preserve external-delivery idempotency, claim fencing, rollback behavior, bounded executor/connection ownership, and the current PR 1 phase boundary.

## Work completed

- Replaced the repository-local transaction wrapper used by `acceptExternal` and `claimPending` with `SQLiteTransactions.inTransaction`.
- Removed the duplicate private `inTransaction` method and private `TransactionWork` functional interface from `SQLiteDirectDeliveryRepository`.
- Left all direct-delivery SQL, state transitions, claim-token predicates, lease fencing, paging, and runtime execution boundaries unchanged.
- Performed a focused harsh review of the consolidation and existing regression coverage.
- Found that the direct-delivery tests did not explicitly prove rollback of the instance and delivery rows when the final external-request insert fails.
- Added a failure-injection integration test using a temporary SQLite trigger that aborts the final insert and verifies that no partial rows survive in `lore_instances`, `direct_deliveries`, or `external_delivery_requests`.

## Important decisions and invariants

- `SQLiteStorageRuntime.execute` still owns bounded off-thread execution and opens one short-lived connection for each repository operation.
- The shared helper starts and owns a transaction only when the connection is in autocommit mode. It preserves an existing callback-scoped transaction when one is already active.
- The shared helper retains stronger failure behavior than the removed duplicate: it rolls back on checked exceptions and errors, suppresses rollback failures onto the original failure, and restores autocommit without replacing an earlier failure.
- External operation replay still creates at most one lore instance and one direct-delivery row.
- Claim transitions remain fenced by expected state, claim token, and a live lease.
- No Paper item serialization, physical inventory insertion, command, listener, group-file, campaign execution, tracking execution, GUI, editing, or physical deletion behavior was added.

## Files or modules changed

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepositoryTest.java`

## Persistence, state-machine, or API changes

- No schema or migration changed.
- No repository, application, domain, or Bukkit service API changed.
- No direct-delivery state or transition changed.
- Transaction implementation is now centralized in the existing shared SQLite helper.
- Test coverage now explicitly verifies atomic rollback across the three rows created during successful external-delivery acceptance.

## Verification actually performed

GitHub Actions CI run 147 (`30780109546`), job `91582969701`, checked out PR merge commit `8504a2b7460baa4315fb17fa03a72c687a9ed22b` containing exact implementation head `aa4bf4ed9c06e5c731ff3d4b883cfbae2b36c524`.

The workflow ran:

```text
gradle --no-daemon clean check
```

Actual result:

- `BUILD SUCCESSFUL in 1m 6s`;
- 31 actionable tasks: 23 executed, 8 up-to-date;
- domain, application, SQLite adapter, plugin, and architecture tests passed;
- the new forced-final-insert-failure rollback test passed;
- the configuration-cache entry was stored.

No live Paper/Leaf server was started.

## Live automation observed

At implementation head `aa4bf4ed9c06e5c731ff3d4b883cfbae2b36c524`:

- PR #2 remained open, mergeable, unmerged, and draft.
- Exact-head GitHub Actions CI run 147 passed.
- CodeRabbit commit status was success. Its PR comment refreshed at `2026-08-03T02:46:37Z` and again skipped substantive review because the PR is draft.
- No submitted pull-request reviews were present.
- No unresolved review threads were present.
- Codacy refreshed at `2026-08-03T02:47:31Z` with the same transient repository-wide aggregate seen during earlier in-progress analyses: `Not up to standards`, 100 new issues (`32 high`, `68 medium`). It had not produced a later stable result before this report was committed, so Codacy is not reported as passed.

## Unresolved risks or missing evidence

- Codacy's current aggregate is unresolved. The connector exposes the summary but not the individual issue list, and this session did not classify the 100 entries as real regressions, stale/incomplete analysis, or false positives.
- PR 1 is not complete. The `adapters-paper` module still has no production source, and the implementation-plan requirement for versioned item-template and hidden-identity codec interfaces plus focused Paper implementations remains unfinished.
- No live Paper/Leaf server evidence exists for PDC identity, item-template serialization, reload/shutdown, inventory behavior, backup/rollback, or corrupt-database recovery.
- The full-PR harsh review required when the phase appears complete was not performed because the phase is demonstrably incomplete. The focused review of this logical work item found and fixed the missing rollback regression test.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`.

First, verify exact-head GitHub Actions, Codacy, CodeRabbit, submitted reviews, and unresolved review threads after the handoff commits. If Codacy remains red, obtain stable detailed findings and fix only validated issues attributable to this transaction-helper slice; do not suppress or guess.

Once exact-head automation is stable, implement only the remaining PR 1 codec foundation required by `docs/implementation-plan.md`: platform-free versioned item-template and hidden-identity codec contracts plus focused Paper 1.21.11 implementations and round-trip tests. Preserve hidden definition ID, instance UUID, applied revision, forced unstackability, arbitrary held-item components, codec-version failure safety, and thread ownership. Do not begin item creation/adoption, physical delivery, commands, protection listeners, tracking/reconciliation execution, GUIs, editing execution, group-file/campaign execution, or physical deletion.

## Required prior reports

- [`0012-2026-08-02-pr2-deleted-marker-verification.md`](0012-2026-08-02-pr2-deleted-marker-verification.md) — preceding exact-head green baseline, completed persistence families, and remaining PR 1 scope.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded executor, short-lived connection ownership, external-delivery idempotency, claim fencing, and recovery rules.
