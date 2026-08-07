# ACC-ENV-001 — RC artifact and environment baseline

**Result:** PASS

## Exact tested inputs
- UTC start: `2026-08-07T20:52:08Z`
- UTC end: `2026-08-07T20:52:37Z`
- Acceptance source/harness commit: `2ef2e9cf33a7e7974f494c1b275c6187a7d22118`
- GitHub Actions run: `31217633117`
- GitHub Actions job: `92994771480`
- Raw artifact ID: `9009152279`
- Raw artifact digest: `sha256:5d97f74bdcac3b62d81324e9d36e91f3744d9ce5aa97569915561b48ce172eea`
- Raw artifact retention expiry: `2026-11-05T20:51:17Z`; the evidence required to sustain this PASS is committed under this directory, so the result does not depend on artifact retention.
- Release: `v1.0.0-rc.1`
- LoreItems JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Plugin version: `1.0.0-rc.1`
- Java: Eclipse Temurin `21.0.11+10`
- Server: Paper `1.21.11-116-main@6f71be8`, API `1.21.11-R0.1-SNAPSHOT`
- Paper JAR SHA-256: `e708e8c132dc143ffd73528cccb9532e2eb17628b1a0eee74469bf466c7003f8`
- Geyser: `2.11.1-b1210 (git-master-6e70ba2)`; JAR SHA-256 `996c1f1997c2db2f9baf802a6a42b374ffadb8d1527638ba5ad765b06b085ecb`
- Floodgate: `2.2.5-SNAPSHOT (b138-fc99cfc)`; JAR SHA-256 `44bdb908e2fb4ff1b974d5313d048a625a21555a9844cfb86256a98e8e1c6bd1`
- ViaVersion: `5.11.0`; JAR SHA-256 `18d19e90fc9467d68128c076630ae8700449c901402a3ef421837ce006bc8cae`
- Geyser acceptance auth: `floodgate`; UDP port `19132`
- Test accounts: none required for the environment-baseline case.

## Steps actually executed
1. Downloaded pinned Paper `1.21.11-116` and checked its SHA-256 before execution.
2. Downloaded Geyser, Floodgate, and ViaVersion and recorded exact versions and JAR hashes.
3. Downloaded the published LoreItems `v1.0.0-rc.1` JAR and rejected the run unless its SHA-256 exactly matched the published release digest.
4. Booted Paper once without LoreItems to create Geyser/Floodgate configuration, stopped cleanly, changed Geyser authentication to `floodgate`, and hashed the full resulting acceptance configuration.
5. Installed the exact LoreItems RC and booted the acceptance server on Java 21.
6. Waited for Paper completion and LoreItems read/write storage plus its direct-delivery worker.
7. Exercised non-mutating console/admin status surfaces: server/plugin versions, LoreItems recovery/audit/destructive metrics, and distribution campaign listing.
8. Checked for any startup-created item entity.
9. Stopped the server cleanly.
10. Queried the resulting SQLite database for journal mode, integrity, foreign-key violations, schema history, foreign-key schema, and every durable table count.
11. Executed explicit pass/fail assertions and uploaded the raw run evidence.

## Expected result
The exact RC starts on the designated Java 21 / Paper 1.21.11-compatible environment with Geyser/Floodgate, opens the expected schema in read/write mode, passes SQLite integrity/foreign-key checks in WAL mode, has no unexplained pending/review work, and creates no physical lore item merely by starting.

## Actual result
PASS. Paper reported `Done (19.248s)`. LoreItems logged `Durable storage is active`, followed by active direct-delivery, mutation-recovery, anomaly/administration, and distribution components. Geyser started on UDP 19132 with Floodgate authentication. The database was schema V7, `journal_mode=wal`, `integrity_check=ok`, and `foreign_key_check=[]`. All durable work/definition/instance/anomaly/campaign/deletion counts were zero except seven expected `schema_history` rows. The recovery query found no nonterminal delivery, mutation, or campaign review record; destructive metrics were all zero; no item-entity marker was observed. The server and all four plugins stopped cleanly.

The console also emitted `A previous lore-item evidence query is still active; try again shortly.` because two read-only evidence commands were issued back-to-back; the subsequent recovery result completed and showed no nonterminal work. This was not treated as a failure or as proof of queue health by itself.

No `ERROR`, `SEVERE`, or exception entry was observed in the acceptance log. Two non-failing environment warnings were recorded: Floodgate warned that locale `en_` is unsupported, and Paper warned that the deliberately targeted 1.21.11 build is older than Paper's current latest release. Neither changes the target-version acceptance result.

## Durable/database evidence
- `database-evidence.json`: committed journal/integrity/foreign-key/schema-history/table-count output.
- Database file SHA-256 after clean stop: `9aa1285860536ba0672713e0da6e4bf969b4268d13d78977be1b27c9eee14b82`.
- Schema migrations present: V1 foundation; V2 template revision rollout; V3 mutation queue controls; V4 template editor confirmations; V5 destructive administration; V6 mass distribution recipient states; V7 mass distribution revision snapshot.
- `assertions.json`: every acceptance assertion is `true`.

## Configuration evidence
- `loreitems-config.yml`: complete generated LoreItems acceptance config; SHA-256 of the executed file `039e6e8ece45fb3e0560d26599cd7a70152e11417aa2b27be604f89e8066172c`.
- `geyser-config-acceptance.yml`: committed normalized fixture containing every operative Geyser setting used by this case. The full generated Geyser configuration remains in raw artifact `9009152279`; SHA-256 of the exact executed full file is `7da0dbfeb21c570878386c9e560ec0e590c0cf5564c0d6b1d26a3c0bfe031a3b`.

## Physical evidence
The startup baseline contained zero lore definitions and zero lore instances, and the command probe found no item entity. Startup therefore did not create a physical lore item.

## Cleanup
The server was stopped through the normal `stop` command and the disposable GitHub-hosted runner was discarded.

## Rollback
Discard the disposable runner and recreate the environment from the exact pinned artifacts/hashes above. No production or persistent acceptance environment was modified.

## Committed evidence files
- `acceptance-server.log` — complete acceptance-server startup, command, and shutdown log.
- `database-evidence.json` — SQLite query/result evidence.
- `loreitems-config.yml` — exact complete LoreItems configuration fixture.
- `geyser-config-acceptance.yml` — normalized operative Geyser configuration fixture; full executed file is independently bound by its SHA-256 and raw-artifact ID above.
- `artifact-sha256.txt` — server/plugin/config/database hashes.
- `assertions.json` — machine-readable acceptance assertions.
- `java-version.txt` — exact Java runtime output.

## Audit conclusion
The Actions job succeeded, but the PASS was assigned only after separately reading the startup log, assertions, hashes, configuration, and SQLite evidence. This case does not claim any Java-player or Bedrock-player interaction; those remain later matrix cases.
