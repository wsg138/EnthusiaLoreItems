# ACC-OPS-001 — degraded/read-only startup

**Result:** PASS

## Exact tested inputs
- UTC start: `2026-08-07T21:09:42Z`
- UTC end: `2026-08-07T21:10:38Z`
- Acceptance source/harness commit: `c00271761e60446f4611706f6b70f3d00ccfde03`
- GitHub Actions run: `31218811889`
- Corrected Actions job: `92998463439`
- Raw artifact ID: `9009608497`
- Raw artifact digest: `sha256:11f4b670acc404ad93da26c5492f44eb2c0c1ba2a6b20a5a2f505c58e196d2ef`
- Raw artifact retention expiry: `2026-11-05T21:08:09Z`; durable excerpts/results needed for this PASS are committed in this directory.
- Release: `v1.0.0-rc.1`
- LoreItems JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Paper: pinned `1.21.11-116`, SHA-256 `e708e8c132dc143ffd73528cccb9532e2eb17628b1a0eee74469bf466c7003f8`
- Geyser JAR SHA-256: `996c1f1997c2db2f9baf802a6a42b374ffadb8d1527638ba5ad765b06b085ecb`
- Floodgate JAR SHA-256: `44bdb908e2fb4ff1b974d5313d048a625a21555a9844cfb86256a98e8e1c6bd1`
- ViaVersion JAR SHA-256: `18d19e90fc9467d68128c076630ae8700449c901402a3ef421837ce006bc8cae`
- Public API probe JAR SHA-256: `6ec3d6055454bb9b3f68b69ee87971967d6cf58ed496986d2919f78b22ebac8a`
- LoreItems config SHA-256: `039e6e8ece45fb3e0560d26599cd7a70152e11417aa2b27be604f89e8066172c`; complete fixture is committed at `../ACC-ENV-001/loreitems-config.yml`.
- Geyser config SHA-256: `7da0dbfeb21c570878386c9e560ec0e590c0cf5564c0d6b1d26a3c0bfe031a3b`; operative fixture is committed at `../ACC-ENV-001/geyser-config-acceptance.yml`, while the full generated file is bound by this hash/raw artifact.
- Healthy recovered database SHA-256: `bceb4fac935d139cde8c52093d9ed21005d53f07630a96b755c7d1318fcf892d`
- Test accounts: none required. A separate Bukkit consumer plugin called the public `LoreItemsServiceV1`; it did not impersonate a player session.

## Induced condition
Before the degraded boot, the expected SQLite file path `plugins/EnthusiaLoreItems/loreitems.db` was deliberately created as a directory. This is a reversible acceptance-only filesystem condition that causes SQLite open to fail without modifying an existing database.

Pre-state contained only:
- directory `plugins/EnthusiaLoreItems/`
- directory `plugins/EnthusiaLoreItems/loreitems.db`

After the degraded boot and clean stop, the only additional LoreItems filesystem object was the normal generated `config.yml`; `loreitems.db` remained a directory. No database file was created at an alternate path.

## Steps actually executed
1. Downloaded and checksum-verified the exact RC and pinned Paper/server support plugins.
2. Built an acceptance-only consumer plugin against Paper and the public LoreItems V1 API. The consumer obtains `LoreItemsServiceV1` from Bukkit's `ServicesManager` and calls `queueDelivery` for a deliberately nonexistent definition.
3. Generated Geyser/Floodgate configuration through a normal server boot and configured Floodgate authentication.
4. Created the reversible invalid DB-path directory and booted the exact RC.
5. Waited for Paper startup and the external API consumer result.
6. Required LoreItems to report degraded/read-only mode, required the consumer call to return `SERVICE_UNAVAILABLE`, and required that read/write startup never be announced.
7. Probed for item entities, stopped normally, and compared the LoreItems filesystem before/after the degraded boot.
8. Removed only the induced invalid directory and restarted the same exact RC.
9. Required LoreItems to enter read/write mode and the same external API call to return `UNKNOWN_DEFINITION`, demonstrating that service availability recovered after the condition was corrected.
10. Probed again for item entities, stopped normally, and queried SQLite integrity, foreign keys, durable counts, and the exact external-idempotency record.

## Expected result
Under a controlled unwritable-storage condition, LoreItems must fail closed for mutations, expose an unavailable/read-only public-service outcome, avoid partial durable or physical work, and recover to normal read/write behavior after the condition is corrected and the server restarts.

## Actual result
PASS.

### Degraded boot
LoreItems reported:

`LoreItems entered degraded read-only mode: SQLiteException: [SQLITE_CANTOPEN] Unable to open the database file (unable to open database file)`

The independent consumer then received:

`WP05_PROBE status=SERVICE_UNAVAILABLE operation=acc-ops-001-probe detail=LoreItems is in degraded read-only mode: SQLiteException: [SQLITE_CANTOPEN] Unable to open the database file (unable to open database file)`

`Durable storage is active` was absent. No item-entity marker appeared. The invalid `loreitems.db` directory remained unchanged and no database file was created. LoreItems stopped normally.

### Healthy recovery boot
After removing only the invalid directory, LoreItems reported `Durable storage is active` and its normal delivery/recovery/anomaly/distribution components activated. The same external consumer call returned:

`WP05_PROBE status=UNKNOWN_DEFINITION operation=acc-ops-001-probe detail=No active lore definition has that key.`

No item-entity marker appeared. The healthy database was `journal_mode=wal`, `integrity_check=["ok"]`, and `foreign_key_check=[]`.

Durable counts after the healthy probe were zero for lore definitions, instances, direct deliveries, pending mutations, campaigns, anomalies, destructive work, observations/current-state rows, deleted markers, and audit events. `schema_history=7` as expected. `external_delivery_requests=1` was intentionally present: the single row stores the idempotent `UNKNOWN_DEFINITION` outcome for `acc-ops-001-probe`, with `delivery_id=null`. Production `SQLiteDirectDeliveryRepository.rejectUnknownDefinition` intentionally persists this rejection so retries return the same durable result. There was therefore durable API-result bookkeeping but no delivery intent or physical item state.

## Harness finding and correction
The first execution of this case, run `31218454541`, correctly demonstrated degraded mode and healthy service recovery but the acceptance harness wrongly asserted `external_delivery_requests=0`, causing a false harness failure. Review of production code established that persisting `UNKNOWN_DEFINITION` is required idempotency behavior. Commit `c00271761e60446f4611706f6b70f3d00ccfde03` corrected the assertion to require exactly one `UNKNOWN_DEFINITION` row with `delivery_id=null`, while still requiring zero definitions, instances, and direct deliveries. The corrected run `31218811889` passed. This was a test-harness defect, not a LoreItems implementation defect.

## Evidence integrity
- Raw artifact: GitHub Actions artifact `9009608497`, digest `sha256:11f4b670acc404ad93da26c5492f44eb2c0c1ba2a6b20a5a2f505c58e196d2ef`.
- `degraded-server-excerpt.log`: relevant LoreItems/probe/error/shutdown evidence.
- `healthy-server-excerpt.log`: relevant LoreItems/probe/read-write/shutdown evidence.
- `healthy-database.json`: complete queried integrity/count/idempotency evidence.
- `filesystem-state.txt`: before/after degraded LoreItems filesystem state.
- `artifact-sha256.txt`: exact server/plugin/RC/config/database/probe hashes.
- Full generated logs/config remain additionally available in the raw artifact; committed fixtures/hash bindings prevent the PASS from depending solely on that expiring artifact.

## Cleanup
Both server phases used the normal `stop` command. The induced invalid DB directory was removed before the healthy recovery phase, and the GitHub-hosted runner was discarded after evidence upload.

## Rollback
Remove the acceptance-only invalid `loreitems.db` directory and restart with the exact pinned RC. Because the degraded phase created no database or physical item work, no database rollback is required for this induced condition.

## Audit conclusion
The corrected workflow result was not accepted on status alone. The degraded and healthy logs, filesystem diff, public-service outcomes, production idempotency code, database integrity/counts, and hashes were separately reviewed. ACC-OPS-001 therefore satisfies its fail-closed and recovery acceptance contract against the exact RC.
