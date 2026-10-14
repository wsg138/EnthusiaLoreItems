# Historical block-key audit — read-only preflight

This is **phase 1 of release blocker #103**, not a world-key migration or
authorization to ship the UUID key format to production.

Run **only on a consistent offline SQLite snapshot**. Stop the Minecraft
server before creating that snapshot, or use the SQLite backup API to produce
a transactionally consistent separate database. **Never copy only
`loreitems.db` while the live SQLite database has active WAL files**.
Do not run against the active plugin data directory.

```bash
python3 tools/legacy_block_key_audit.py \
  /path/to/offline-loreitems-snapshot.sqlite \
  --confirm-offline-copy \
  --output /tmp/loreitems-world-key-audit.json
```

For a **fresh, empty tracking database on a separate TEST server only**, an
optional `--require-empty-tracking` flag refuses to report success unless
both durable tracking tables contain **zero rows** and the projection schema
is compatible. A populated or schema-incompatible snapshot returns exit 3,
without exporting any individual item ID. The JSON
`empty_tracking_snapshot.eligible_for_empty_database_path` field is merely
a conditional migration-scope diagnostic: it is **not** authorization to
deploy a JAR, bypass CI, or treat an existing SMP's history as empty. The
actual target server's files and data still need to be verified.

The script opens the supplied database with SQLite `mode=ro` and
`PRAGMA query_only=ON`, validates its tracking tables and quick integrity,
and streams the two tracking tables with bounded memory. The output gives
counts for modern `block-v2:<UUID>`, canonical `double-chest:<UUID>`,
old `namespace:world-name:x:y:z`, nested block roots, malformed keys,
and unrecognized roots. The audit also counts `world_named_entity_inventory`
keys such as `minecraft:overworld:entity:<UUID>` separately: the production
entity-holder snapshot code still embeds a world *name* in this key format.
This extra category is **not** a block migration candidate; its count is
diagnostic only, and no entity identifier or UUID is emitted. It separately summarizes legacy current-state counts
(`CONFIRMED_NOW`, `LAST_CONFIRMED`, `CONFLICTING`, and other states)
without exporting player or item instance identifiers. A
`LAST_CONFIRMED` key is not proof that the item still exists at that
physical location, and a `CONFLICTING` key must never be auto-resolved.
Legacy world-name groups are marked
**UNRESOLVED**, even when the name is unique in this report. A world-name
group can correspond to more than one actual world UUID.

The `historical_instance_overlap` aggregate groups immutable observation
histories by instance without revealing any instance IDs. It distinguishes
legacy-only histories, legacy histories with one observed modern UUID, and
legacy histories with multiple distinct observed modern UUIDs. **None of
these categories proves a safe world-name-to-UUID mapping**: tracked items
can move between worlds, and repeated observations do not imply copies.
Every category remains `REVIEW_ONLY_NO_WORLD_MAPPING` until independent
physical evidence and migration review exist. The accompanying
`mixed_key_arrival_order_counts` field separates mixed histories whose
**first durable observation** used the legacy world name from those whose
first observation already used a versioned UUID key. The audit orders each
instance's records by immutable `observation_id` to make this deterministic;
neither arrival direction proves location equivalence or physical duplication.
Only aggregate counts appear, never individual instance IDs.
The `projection_link_consistency` section checks that each current SQLite
projection points to an observation for **the same instance**, that the
observation's confidence is allowed by the current state (including a
`LAST_CONFIRMED` state referencing a prior `CONFIRMED_NOW` receipt), that
its observation timestamp does not postdate the projection, and that its exact
location type/key/nested path agree. It counts dangling links, links to
another instance, mismatched states/locations, and malformed unresolved
rows **without exporting item/player IDs**. If the supplied database lacks
required columns, it reports `REVIEW_REQUIRED_SCHEMA_INCOMPLETE` rather
than silently declaring consistency. Even a `CONSISTENT_LINKS_ONLY` result
does **not** prove that the physical item currently exists or authorize
migration; reports remain `safe_to_deploy: false`.
The audit **does not** inspect physical container contents, map world names
to UUIDs, infer unloaded chest halves, rewrite `instance_current_state`,
change `instance_observations`, resolve duplicate-item anomalies, run a
migration, or approve a rollout. An offline audit report is only a count of
records, not evidence of unique physical items. Treat reports as private
operational data.

To test without any real server data:

```bash
python3 -m unittest discover -s tools -p 'test_legacy_block_key_audit.py' -v
```

The tests use only synthetic temporary SQLite databases. A separate exact-head
GitHub Actions workflow runs them when these files are changed. One test
creates a committed WAL-mode transaction, captures a complete separate
snapshot through SQLite's `Connection.backup()` API, and demonstrates that
later source writes do not change the snapshot. This is a **backup protocol
rehearsal**, not a proof that any live server files are safely backed up or
restorable; actual stopped-server backup + journal recovery/restore still needs
explicit independent approval and testing. A separate synthetic regression
takes a complete SQLite backup, commits later writes to its original WAL-mode
source, performs `PRAGMA wal_checkpoint(TRUNCATE)`, and restores the backup to
a **different** disposable filename. The restored audit must match the
pre-write snapshot exactly, with SQLite `quick_check=ok` and no copied later
history. This validates only a temporary SQLite recovery rehearsal, not
LoreItems' actual live installation, data, or rollback procedure.

**Required later gates:** prove cross-world collisions and world recreation,
naturally loaded block/chest half proof, bounded idempotent migration, ambiguous
world-name review, full audit history preservation, WAL-aware backup and
restore, interrupted migration recovery, rollback, 48-case Paper double-chest
matrix (#95), matched performance baseline, and explicit owner approval before
any live deployment.
