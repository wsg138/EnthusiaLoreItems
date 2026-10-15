# Worker 05 — physical-proof reconciliation preflight (2026-10-15)

**Status: DRAFT / ISSUE #103 OPEN / RELEASE HOLD.** This is a **pure, read-only planning contract**, **not** an approved SQLite schema change, transaction writer, offline migration, world-name alias policy, startup recovery algorithm, or authorization to touch SMP/TEST. Base: draft PR #153 at `5a73e9d1a5d2890a986a96c3bbbadcf609ee8487`, unmerged stack. No live databases, server JARs, plugin configuration, player/item identities, or raw chat were used.

## Durable read/write and ambiguity inventory

Read the complete parent issue [#103](https://github.com/wsg138/EnthusiaLoreItems/issues/103), dependent [#95](https://github.com/wsg138/EnthusiaLoreItems/issues/95), [W09 durability gate](W09_SQLITE_DURABILITY_KEY_MIGRATION_GATE_2026-10-12.md), `SQLiteTrackingObservationStore`, `SQLiteTrackingLegacyKeyFence`, `SQLiteCurrentStateRepository`, `SQLiteLegacyKeyDryRun`, `PaperInventoryReference`, `PaperDoubleChestIdentity`, `PaperPhysicalInventorySnapshot`, and [the offline audit guide](../../tools/README-legacy-block-keys.md).

- Old block-container: `namespace:world-name:x:y:z`. Persisted as `instance_observations.location_key` (immutable history) and `instance_current_state.location_key` (current projection), with `location_type`, `container_path` and `last_observation_id` tying evidence to state. Negative X/Z and name collision/recreation are not evidence of historical UUID lineage.
- New ordinary block/hopper/single-chest: `block-v2:<world UUID>:x:y:z`. New double chest: `double-chest:<world UUID>:x:y:z|x:y:z:left=<0|1>` with sorted physical blocks and **verified Paper GUI left-side orientation**, not Chest.Type or DoubleChest composite location. Position plus root key alone does not establish per-half slot identity.
- Nested shulker: `root:BLOCK_CONTAINER:<root>` and exact `container_path`; source half `slot:N` and nested suffix must be reconciled together. Inventory slot paths, player cursor transfers, hoppers, split/rejoin and event-generation transitions must not silently alias.
- Entity-held BLOCK_CONTAINER inventory keys can still use `namespace:world-name:entity:<entity UUID>` via `PaperPhysicalInventorySnapshot` and other scanners. They are **not** legacy block XYZ keys and are explicitly excluded from this planner; require a separate world-ID policy.
- The historical names also reach block place/break/chunk events, scanner/continuation queues, tracking coordinator, SQLite observation/current repositories, REST/UI/history views. The old→new safety fence is symmetric and produces conflict-review evidence without inventing a duplicate-item anomaly.
- **Only historical** legacy observations are not a current alias to rewrite: preserve all immutable receipts, timestamps and source lineage exactly. Even mixed historical UUID/name evidence or one currently known UUID cannot justify a world-name mapping.
- `LAST_CONFIRMED` denotes last evidence, not current presence; `CONFLICTING` prohibits blind reconciliation. The audit checks same-instance projection/receipt compatibility, including `LAST_CONFIRMED` referencing a prior `CONFIRMED_NOW` receipt. Neither result gives physical proof.

## Immutable proposed input API (not wired to runtime)

`SQLitePhysicalProofReconciliationPlanner` consumes **caller-supplied** `CurrentEvidence` and `PhysicalProof`. These are **future contract requirements**, not facts available from historical SQLite:

| Evidence | Required trust boundary |
|---|---|
| PDC instance UUID and a count of actual matching items | From fresh, complete loaded-only Paper observation of same real instance; two matching items is ambiguous |
| Actual world UUID and world lineage | Independently corroborated for the historical source receipt; not inferred by a same-name world lookup, even if one world currently exists |
| Ordered chest physical block coordinates and Paper GUI left half | Both actual halves checked naturally loaded, same world, adjacent and facing/canonical slot geometry verified across all 54 slots, no force load |
| Local old slot and newly verified holder slot/nested suffix | Source `slot:0..26` is translated by observed physical half + Paper left side; source slot/path and measured canonical slot/path must agree |
| Revision, prior last-observation receipt and event generation | Consistent same-instance current state; future writer must CAS the exact saved values and check new/old keys atomically |
| Mutation/dirty state, durable ACK, and post-state capture time | No pending unsafe mutation, no stale/dirty generation, persisted durable settlement, freshly captured evidence; process-local retry ledger does not meet durability requirement |
| Current projection state | Existing `CONFLICTING`, `TERMINAL_VOID` or changed projection cannot be auto-resolved; no overwrite of immutable history |

Outcome is exactly one of **ABSENT**, **AMBIGUOUS**, **STALE**, **CONFLICT**, **ELIGIBLE** with structured reason. `ELIGIBLE` carries a *proposed* UUID location and canonical combined slot path **only**; it never returns an SQL mutation, creates an alias or asserts `CONFIRMED_NOW`. This planner is not called from Paper, SQLite startup or any live endpoint. Values such as `lineageVerified` and `durableAcknowledgement` must be established by an independently reviewed trusted producer in a **future** PR; forged flags in a synthetic test do not prove runtime provenance.

## Required future transactional writer (NOT IMPLEMENTED)

1. Obtain a WAL-consistent immutable backup via SQLite backup API or `VACUUM INTO`, verify original/restore hashes, `PRAGMA integrity_check`, foreign keys, and private offline preflight report. A standalone `.db` copy during active WAL is invalid.
2. Run an independent naturally loaded Paper observation after event settlement, checking every relevant physical half and actual PDC identity without forced chunk loading. Prove same-instance historical receipt lineage, world UUID, slot and nested suffix; unresolved name groups remain manual review.
3. Persist a generation+dirty+ACK fence; the currently **process-local** retry ledger cannot supply crash-safe generations. An offline world-name-only report cannot supply the proof. Require a consistent source receipt and projection snapshot.
4. In **one** bounded transaction, CAS `state_revision`, `last_observation_id`, old key/path, generation and identity; append new observed provenance + audit while retaining all historical rows and times; reject conflicts and genuinely duplicated copies. Be idempotent under replay and roll back completely on any failure, including audit failure and `SQLITE_BUSY`. Do **not** set `CONFIRMED_NOW` merely because a new key appears.
5. Inject failure in disposable WAL SQLite: busy, disk full, connection closed, injected abort, partial process crash, checkpoint+restore, repeated identical request, rejected/non-authoritative late ACK, and projection changed since proof. Confirm zero duplicate receipts and no advancement on rejection/rollback; verify exact immutable state after close/reopen.
6. Rehearse rollback of a **stopped** deployment using a consistent restore and independent operator approval; run all #95 48 native Paper 26.2/Java25 cross-chunk slot/hopper/split/rejoin/PDC cases and match AI-OFF performance. Full clean CI, zero new valid hosted Codacy findings, owner approval remain required before any production action.

## Scope and independently executable evidence

- **Pure new class:** `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLitePhysicalProofReconciliationPlanner.java` (no JDBC/Bukkit imports or persistence calls).
- **Synthetic tests:** `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLitePhysicalProofReconciliationPlannerTest.java` validate supplied hypothetical proof: legacy/current-only vs historical-only, reversed arrival, two same-name worlds, rename/recreation, null/duplicate physical item, PDC mismatch, unproven lineage, stale CAS receipt/generation/ACK/capture, mutation dirty, LAST_CONFIRMED/CONFLICTING, unloaded half and split/rejoin, real half-local→canonical slot 0/26/27/53, signed X/Z and nested path, separate hopper and single chest, entity exclusions.
- **No schema, no new production reader/writer, no simulated successful migration.** Existing #153 WAL lock rollback/restart tests are inherited, not new proof of a legacy migration. Pure planner tests cannot demonstrate real physical Paper identity or WAL crash safety.
- **Explicit hold:** release approval remains `NOT_APPROVED`; no main merge, live database edit, SFTP, server restart, test JAR or deployment.

### Verification

Run on exact worker branch and record real results before PR review:

```powershell
# Use local cached Gradle 8.13 distribution if no gradlew wrapper exists.
gradle :adapters-sqlite:test --tests net.enthusia.loreitems.sqlite.SQLitePhysicalProofReconciliationPlannerTest
gradle :adapters-sqlite:test
python -m unittest discover -s tools -p 'test_legacy_block_key_audit.py' -v
```

Distinguish pure synthetic eligibility cases from actual transaction migration, production code, or Paper native tests. All later CI/Codacy findings and exact SHAs belong in the draft PR, not a passing blanket release claim.
