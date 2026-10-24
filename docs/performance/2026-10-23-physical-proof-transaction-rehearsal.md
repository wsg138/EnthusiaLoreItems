# Worker 2: physical-proof CAS transaction rehearsal (2026-10-23)

**DRAFT / NOT DEPLOYABLE / issue #103 and #95 OPEN.** Branch `workers/lore-physical-proof-writer-20261023-w2a` is stacked on the exact unmerged draft #155 head `ea5c540c56f372e32b29b3d5503c1a2df5397574`, which itself stacks on #153. Nothing is merged to main or deployed.

## Scope and non-goals

This PR adds an **inactive** `SQLitePhysicalProofReconciliationWriter.rehearse` entry point and disposable-JUnit SQLite transactions. No runtime path invokes it; no production migration or schema files are changed. It is deliberately gated by three fixture-only tables absent from the real schema: `reconciliation_rehearsal_guard`, `reconciliation_rehearsal_fence`, and `reconciliation_rehearsal_receipts`. Tests create all three on fresh temp databases; no database copy, server connection, SFTP, Paper tick, chunk force-load or player inventory change exists. A marker is an accidental-use fence, **not** authorization to use the class against other databases. Never call the class on a server DB, even a backup.

The role of this code is to expose SQL transaction failure modes using real V1–V11 migration tables, not to assert that a fake `lineageVerified` flag or test-held `dirty=0` proves a physical witness. Its test data are invented instance IDs and world IDs.

## Exact synthetic transaction contract

1. Require explicit test tables and marker before even opening an owned transaction; refuse caller-owned transactions. Rehearsal fence supplies persisted fixture `generation`, `dirty`, `ack_durable`, original `source_observation_id` and source snapshot timestamp. No actual production persisted generation source exists.
2. Read current state by instance, join the immutable same-instance `instance_observations` source receipt, validate state/confidence/path/time equality, reject unresolved anomalies and require the caller's full expected current snapshot to match it exactly. This read + subsequent writes share one transaction.
3. Run the separate pure `SQLitePhysicalProofReconciliationPlanner.plan` and require `ELIGIBLE`; this checks unique PDC instance, trusted world lineage flags, no name collision, naturally loaded chest geometry and proven canonical 0–53 slot/nested suffix, expected revision/receipt, generation, durable ACK, no dirty mutation and fresh physical capture. These flags remain **hypothetical inputs**.
4. Append one new observation with new AUTOINCREMENT ID and source `physical_proof_DISPOSABLE_REHEARSAL`, **confidence LAST_CONFIRMED only**. Do not rewrite the source observation or its timestamps, do not invent `CONFIRMED_NOW`.
5. CAS the exact source projection: same instance, state, revision, observation ID, updated time, old location type/key/path, settled generation/receipt/dirty/ACK fixture row and no open anomaly. Append a unique fixture receipt keyed by (instance UUID, source observation ID, generation) storing the new observation and original projection metadata; append an audit event. Commit all four changes together. On SQL error or injected failure, rollback everything.
6. Replay first checks the keyed immutable fixture receipt; it returns `ALREADY_APPLIED` only for a matching original observation, proof payload, new receipt and key/path. This acknowledges a historical committed operation, **never promises that the item is still located there**. Divergent payload or stale current projection is refused, not aliased. SQL lock/busy and unknown commit outcome are surfaced as errors; the caller must reopen and inspect the receipt before retrying.

All queries use bound parameters; only fixed SQL fixture table names are used. SQL constraints/foreign keys are exercised in real SQLite WAL files. History can be verified unchanged by row content plus same original ID, and restored file checked with `PRAGMA integrity_check` and `pragma_foreign_key_check`.

## Reasons a production implementation is still blocked

- The actual V11 `instance_current_state` stores revision, last observation and updated timestamp, **not a durable generation, dirty status, trusted proof receipt or completed ACK**. The Paper retry ledger is not crash-safe evidence of all naturally loaded halves.
- Actual `instance_observations` has AUTOINCREMENT observation ID but **no unique reconciliation operation/receipt identity**. An unmodified runtime cannot safely distinguish a crash immediately before COMMIT from a crash immediately after COMMIT and blindly reapply.
- No trusted runtime proof producer has been reviewed end to end (PDC UUID, true world UUID including rename/recreation lineage, both natural half inventories with Paper left/right mapping, nested shulker chain, empty/partial coverage, event generation, crash durable ACK). Old world-name history alone cannot furnish these.
- Immutable historical observations remain audit records. The rehearsal changes the synthetic *projection* only and has no actual world alias registry or human collision-disposition workflow. LAST_CONFIRMED is not current physical position.
- Physical #95 Paper 26.2 Java 25 48-case matrix, rollback rehearsal on an authorized stopped **copy**, and release/owner approval remain pending. No test fixture under this task should be represented as fulfillment of these gates.

## Required future production changes (separately reviewed PRs, NOT in this branch)

A durable, versioned Paper proof envelope persisted from the authoritative naturally loaded observation; a generation/dirty/ACK ledger transactionally coupled with state; immutable world-UUID history/lineage and explicit collision review; a unique operation receipt and replay protocol; proof expiration and same-instance cross-source CAS; backup/restore procedure with integrity checks and external operator approval. Integration must prove behavior when DB writer races chunk-unload or delayed ACK and must not reset active conflict state. Make a new migration only after design/security review; do not backfill hypothetical generation values.

## Test matrix and limits

`SQLitePhysicalProofReconciliationWriterTest` seeds newly created temporary WAL SQLite databases via `MigrationRunner` with real foreign keys and fixture-only supplemental tables. It checks exact successful single chest, audited append and same-ID replay after close/reopen, changed replay rejection, stale revision/history-only, duplicate or unloaded physical item, dirty/ACK/generation, open anomaly, failure injected after each of four write stages, DB trigger-induced audit abort/rollback, WAL competing-writer lock, and 54-slot half-to-combined mapping. No production database or path is referenced. Tests are synthetic and do not constitute physical/Paper evidence.

For testing run `gradle :adapters-sqlite:test --tests net.enthusia.loreitems.sqlite.SQLitePhysicalProofReconciliationWriterTest --no-daemon --offline`, followed by `gradle :adapters-sqlite:test :adapters-sqlite:check build --no-daemon --offline`, Linux Python audit tooling and hosted exact-head CI. Record results and failures against the **final SHA**; no inherited success can be applied to new work.

**No owner action is needed for this rehearsal.** Do not deploy, merge, restart or authorize access to any live SQLite file.
