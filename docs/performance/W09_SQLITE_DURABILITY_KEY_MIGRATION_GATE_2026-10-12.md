# Worker 09 — SQLite durability and legacy-key release gate (2026-10-12)

Status: **HOLD / DRAFT**. No production SQL migration, config change or server deployment authorized. Parent issues: #103 and #83; integration PR #102.

## Implemented in Worker 09

- PaperTrackingCoordinator distinguishes queue admission from database settlement. STALE, UNKNOWN_INSTANCE, IDENTITY_MISMATCH, INACTIVE_INSTANCE, BLOCKED_ANOMALY and SERVICE_UNAVAILABLE all leave in-process dirty obligations. Metrics errors cannot prevent acknowledgements and queue drain.
- PaperDurableTrackingRetryLedger is still PROCESS-LOCAL, not durable after crash. It must never replay stale authoritative transitions. Overflow remains sticky. A successful write does not prove source absence or uniqueness.
- SQLiteTrackingObservationStore fences an old namespace:world:x:y:z BLOCK_CONTAINER location (including nested root:BLOCK_CONTAINER: paths) if a new block-v2:UUID:x:y:z or double-chest:UUID location is observed. It transactionally preserves both observations, changes projection to CONFLICTING and emits tracking_legacy_world_key_review audit. This does not assert a genuine duplicate and does not manufacture a DUPLICATE_INSTANCE anomaly from key formatting alone. Failed audit means rolled-back transaction.
- SQLiteLegacyKeyDryRun.inspect(Connection, knownWorlds) is a READ-ONLY row-count inspection of current state and history. It classifies old keys by 0/1/multiple current UUIDs, last-confirmed legacy, UUID block, UUID double chest and unrecognized formats. A currently single world name does not prove historical UUID identity. It is not connected to live startup.

## Mandatory gate before a migration can be considered

1. Obtain a WAL-consistent SQLite backup through the SQLite backup API or VACUUM INTO, not a standalone .db copy while WAL writes are active. Keep original immutable, compute SHA-256, restore a disposable copy and verify PRAGMA integrity_check and foreign keys.
2. Run the read-only dry-run report against the disposable copy; save current/history counts for known-name, ambiguous-name, unmapped-name, LAST_CONFIRMED, v2, double chest, and unknown formats. Also record all known present world UUIDs. Do NOT map old records based solely on current world name.
3. Determine historical lineage from actual world UUID evidence and only reconcile when naturally loaded full post-event coverage can verify the exact instance, holder and slot. Split double chests across X/Z boundaries, negative coordinates and unloaded halves cannot be assumed complete. Never load a chunk for discovery.
4. Separately review a schema-versioned, idempotent, auditable alias table and reversible transactional migration. Keep original instance_observations immutable. Require compare-and-swap on state_revision AND last_observation_id; append new provenance/evidence and an audit event in the same transaction. Include stale-generation and duplicate-copy fencing. Ambiguous rows stay unresolved for manual review.
5. In disposable WAL databases inject SQLITE_BUSY, disk-full, rejected execution, late ACK, rollback, shutdown, mid-migration process crash and post-checkpoint restore. Compare observation history and state revisions; verify no invented source absence or partial projection.
6. Connect natural player/chunk re-access and W08 bounded continuation to fair reobservation. Process-local dirty obligations disappear on restart, so startup recovery cannot treat all missing ledger entries as confirmed. Require persisted ACK before promoting LAST_CONFIRMED to CONFIRMED_NOW.
7. Obtain clean exact-head CI, zero new valid Codacy findings, real disposable Paper 26.2/Java 25 chest/slot/nested tests, migration rehearsal and explicit owner approval before LIVE migration, deployment or main merge.

## Explicit limitations

No schema migration, live DB update, automatic alias rewrite, or post-restart durable obligation replay was introduced. No loaded-half proof, W07/W08 bridge, or deployed runtime acceptance exists here. Release remains BLOCKED. Current repository Gradle targets Java 21 and README names Paper/Leaf 1.21.11; a mocked test does not establish compatibility with a separately requested Paper 26.2/Java 25 target.
