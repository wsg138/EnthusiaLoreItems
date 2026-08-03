# Handoff 0004 — pending mutation and audit persistence

## Session metadata
- Date/time: 2026-08-02 19:12 America/Indiana/Indianapolis
- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr1-foundation`
- Pull request: #2 — Foundation and durable core
- Reported implementation head: `9ef083dc618714ab846cadf90e42c590176900e7`
- Session status: in progress

## Objective

Continue the exact next step from handoff 0003 on the existing branch and draft PR. First verify the current Codacy result and classify any real critical/high findings. After confirming that the previously reported 61-issue result was stale and the latest result had zero new issues, implement only the next unfinished PR 1 persistence slice: bounded pending-mutation and audit-event ports, SQLite repositories, state-machine rules, and focused tests.

## Work completed

- Verified PR #2 live state before writing: open, mergeable, draft, branch `agent/loreitems-pr1-foundation`, implementation head `9ef083dc618714ab846cadf90e42c590176900e7`.
- Verified that Codacy's current bot summary is **Up to standards** with `0` new issues. No suppressions or code changes were made solely to alter the gate.
- Added `PendingMutationState` with the durable path `PENDING -> CLAIMED -> APPLIED -> VERIFIED -> COMPLETED` and explicit transition to `REVIEW_REQUIRED` from every nonterminal state.
- Added immutable application records and repository ports for pending mutations and audit events.
- Added bounded SQLite pending-mutation persistence using the existing V1 table and queue index.
- Added transactional bounded claims, claim tokens, lease expiry, attempt counting, compare-and-set transitions, paged non-completed reads, and restart recovery of expired claimed work to `REVIEW_REQUIRED`.
- Added append-only SQLite audit persistence using generated monotonically increasing audit IDs.
- Added bounded aggregate-history queries ordered newest first.
- Bounded individual audit JSON payloads to 65,536 characters and bounded all audit reads through `PageRequest`/`Page`.
- Added focused domain and SQLite integration tests for mutation transitions, due-work filtering, duplicate claim prevention, token/lease fencing, restart recovery, append-only audit IDs, aggregate isolation, ordering, and pagination.
- Did not add gameplay, inventory access, Paper listeners, creation/adoption, physical delivery, protection, tracking, GUIs, editing execution, deletion execution, or campaign execution.

## Important decisions and invariants

- Pending mutations use the same single-server claim-token and live-lease fencing model as direct deliveries.
- A mutation whose claim expires after it may have crossed an external side-effect boundary is not automatically retried; `CLAIMED`, `APPLIED`, and `VERIFIED` recover to `REVIEW_REQUIRED`.
- Only `PENDING` unclaimed records with zero attempts may be inserted through the new repository.
- Claim selection is bounded, ordered, and respects `next_attempt_at`.
- Audit history is append-only. Repository reads are aggregate-scoped and bounded.
- The existing V1 migration was sufficient; no migration version or schema change was added.
- The existing branch and draft PR were retained. Nothing was merged.

## Files or modules changed

Domain:

- `domain/src/main/java/net/enthusia/loreitems/domain/PendingMutationState.java`
- `domain/src/test/java/net/enthusia/loreitems/domain/PendingMutationStateTest.java`

Application:

- `application/src/main/java/net/enthusia/loreitems/application/PendingMutationRecord.java`
- `application/src/main/java/net/enthusia/loreitems/application/PendingMutationRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/AuditEventRecord.java`
- `application/src/main/java/net/enthusia/loreitems/application/AuditRepository.java`

SQLite:

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLitePendingMutationRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteAuditRepository.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLitePendingMutationRepositoryTest.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteAuditRepositoryTest.java`

## Persistence, state-machine, or API changes

- Added the pending-mutation state machine matching the existing V1 checked states.
- Added repository insertion for new `PENDING` mutation records.
- Added bounded claim from `PENDING` to `CLAIMED` with a token, expiry, and incremented attempt count.
- Added compare-and-set claimed transitions fenced by mutation ID, expected state, claim token, and `claim_expires_at > now`.
- Added expiry recovery from `CLAIMED`, `APPLIED`, or `VERIFIED` to `REVIEW_REQUIRED`, clearing claim metadata.
- Added bounded non-completed mutation listing, including `REVIEW_REQUIRED` records for operator recovery.
- Added append-only audit insertion and bounded newest-first aggregate history.
- No public Bukkit service API changed.

## Verification actually performed

GitHub Actions run 58 (`30771651314`), job `91559726753`, checked out PR merge commit `ff20a5f13eec5ade800fcf0591fc8892db42dbf3` containing implementation head `9ef083dc618714ab846cadf90e42c590176900e7` and ran:

```text
gradle --no-daemon clean check
```

Actual result:

- `BUILD SUCCESSFUL in 1m 7s`
- 31 actionable tasks: 23 executed, 8 up-to-date.
- `domain:test` passed.
- `application:test` passed.
- `adapters-sqlite:test` passed.
- `plugin:test` passed.
- `architecture-tests:test` passed.
- Configuration cache entry stored successfully.

No local dependency-backed build was available. The GitHub Actions log is the direct build/test evidence.

No live Paper/Leaf server was started, and no inventory behavior was exercised.

## Live automation observed

At implementation head `9ef083dc618714ab846cadf90e42c590176900e7`:

- PR #2 was open, mergeable, and still a draft.
- GitHub Actions CI run 58 passed.
- Codacy's bot summary was **Up to standards** with `0` new issues and was updated after the implementation commits.
- CodeRabbit skipped substantive review because the PR remains a draft.
- There were no submitted pull-request reviews and no unresolved review threads.

## Unresolved risks or missing evidence

- The audit repository is durable and append-only, but no application `UnitOfWork` yet guarantees that a future business-state transition and its audit event commit in the same SQLite transaction. Future use cases must not perform a state change and audit append as two independently successful operations when atomicity is required.
- Full definition/revision persistence is still incomplete; the current definition repository only performs active-key lookup.
- Instance, observation/current-state/anomaly, campaign/recipient, and deleted-marker repositories remain incomplete.
- Paper item-template serialization and hidden-PDC round-trip tests remain absent.
- Operator-visible metrics remain a no-op boundary.
- There is no live-server startup, reload, shutdown, corrupt-database, or Paper item round-trip evidence.
- The PR remains intentionally incomplete and must not be merged.

## Exact next step

Stay on PR #2 and `agent/loreitems-pr1-foundation`. Implement the next PR 1 persistence slice only: complete immutable definition/revision and lore-instance repository ports and SQLite implementations, including transactional definition creation, monotonic revision append with compare-and-set current revision, active/deleted lookup behavior, bounded paged reads, and focused uniqueness/rollback/restart tests.

Design the transaction boundary so later application use cases can commit authoritative state changes and audit events atomically; do not claim atomic audited workflows until that unit-of-work path is implemented and tested.

Do not begin item creation/adoption commands, physical inventory delivery, protection listeners, tracking/reconciliation, GUIs, editing execution, deletion execution, or campaign execution.

## Required prior reports

- [`0001-2026-08-02-pr2-foundation-start.md`](0001-2026-08-02-pr2-foundation-start.md) — original PR 1 scope, migration design, architecture boundaries, and deferred foundation work.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded storage lifecycle, direct-delivery idempotency, claim fencing, and startup recovery.
