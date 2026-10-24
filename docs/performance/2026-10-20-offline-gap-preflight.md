# LoreItems tracking-outage preflight — consistent snapshot only

**Development-only tool, not an SMP operation or release approval.** During the owner-confirmed multi-day absence of the LoreItems JAR, item PDC identities are expected to persist under ordinary moves, but none of the plugin's physical movement tracking was active. Old database states labelled `CONFIRMED_NOW` may refer to locations that are no longer current.

The companion script `tools/loreitems_offline_gap_preflight.py` produces a **read-only historical inventory** of the existing `instance_current_state` projections, their last observation IDs and any unresolved anomalies. It deliberately reports **zero verified current physical locations**, even when a stored confidence reads `CONFIRMED_NOW`, because it cannot inspect Minecraft inventories or recreate events while tracking was offline.

## Operator prerequisites — NOT yet approved for live execution

1. Do **not** install a new LoreItems JAR, hot-load a plugin, touch the SMP plugin directory, restart the SMP, or access/rewrite the production DB for this step. The owner must authorize a separate maintenance window and choose the recovery sequence after assessing stability.
2. Establish the actual plugin-disabled timestamp from server logs or the operator record; **do not guess it from the date of this document**.
3. From an authorized maintenance window, obtain a **transactionally consistent SQLite backup** of the original data, including any active WAL transactions, using the SQLite online backup API or a fully quiesced shutdown copy that correctly preserves/merges WAL. **Copying only a live `loreitems.db` file while SQLite has an open WAL can silently omit committed evidence.**
4. Run this script on a separate local copy of that backup, never the SMP's active database. An absolute path to the *copy* is required. The script sets read-only SQLite `mode=ro` and `PRAGMA query_only`, refuses missing tables, broken foreign-observation links, missing files, malformed timestamps or failed SQLite quick-check.
5. Example, on the separate analysis machine, using the *actual* disabled timestamp:

```bash
python3 tools/loreitems_offline_gap_preflight.py \
  --database /secure/backups/loreitems-consistent-copy.db \
  --disabled-since 2026-10-16T12:00:00+00:00
```

**Example timestamp is illustrative only, not verified SMP downtime.**

Output contains instance UUIDs, recorded location keys/paths, lifecycle states, active anomaly counts, last recorded update timestamps and the assessment `REVERIFY_PHYSICAL_LOCATION`, `HISTORICAL_LOCATION_ONLY`, `CONFLICT_REVIEW`, `ANOMALY_REVIEW`, `UNRESOLVED`, or `INACTIVE_HISTORY_ONLY`. These are **historical data**, not proof of current position or possession. Keep output private: recorded player IDs/location keys may be sensitive to players.

## Still required after preflight

- Inspect actual physical items on an isolated, compatible Paper/Java test server using unchanged PDC `definition_id`, `instance_id` and revision evidence; distinguish inaccessible/offline-player inventories, unloaded chunk halves, nested containers and duplicates. Do not guess missing locations.
- Prove no silent `CONFIRMED_NOW` promotion on plugin startup, shutdown, retry or old world-name/UUID migration. Preserve `LAST_CONFIRMED` where the actual current location cannot be verified.
- Complete [#95](https://github.com/wsg138/EnthusiaLoreItems/issues/95) full 48-case cross-chunk real Paper acceptance, [#103](https://github.com/wsg138/EnthusiaLoreItems/issues/103) historical world UUID proof, [#39](https://github.com/wsg138/EnthusiaLoreItems/issues/39) scan/backpressure safety, and AI-OFF TPS/MSPT benchmarks before approving a production restart.
- The synthetic unit tests in `tools/test_loreitems_offline_gap_preflight.py` prove only fail-closed schema, read-only hash preservation, and conservative classification. **They cannot verify actual Minecraft item locations.**

No changes to SMP, SQLite, item instances or `main` are authorized by this document.
