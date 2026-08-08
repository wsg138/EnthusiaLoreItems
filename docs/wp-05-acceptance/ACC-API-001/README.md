# ACC-API-001 — public Bukkit API acceptance

Result: **PASS**

## Exact tested build
- Source head: `e8921e0d3b633bc4a8c803aa37898acfeea2747e`
- Plugin version: `1.0.0-rc.1`
- Plugin JAR SHA-256: `e817b066ce18daca3556b83e20828ad40d45257f2c75e03b5ee4e43221820dd1`
- Server: Paper `1.21.11` build `116`
- Java: Temurin `21.0.11+10`
- Dedicated GitHub Actions run: `31242418203`
- Uploaded artifact ID: `9017460902`
- Artifact digest: `sha256:16e727aa54bb30c5fe29b2f75f53b27659be1ab8f3a140d524b4369f1e6adfbc`
- Runtime config override: `database-busy-timeout-millis: 500` and `duplicate-warning-interval-seconds: 300`

The protocol client used offline authentication solely to provide a deterministic server-visible UUID/name boundary. It did not exercise Microsoft/Xbox authentication and this evidence makes no such claim.

## Live sequence and observed results
1. The independent acceptance helper resolved the registered `LoreItemsServiceV1` from Bukkit's `ServicesManager`.
2. `api-op-1` against active definition `acc_api_live` returned `ACCEPTED_QUEUED` and created one durable request/delivery.
3. Replaying `api-op-1` before restart returned `ALREADY_ACCEPTED` and created no second durable intent.
4. `api-op-unknown` against a missing definition returned `UNKNOWN_DEFINITION`; the non-acceptance outcome was durably recorded with no delivery.
5. `api-op-validation` used syntactically invalid definition key `!` and returned `VALIDATION_FAILURE`; it did not reach durable request storage.
6. An independent SQLite connection held `BEGIN IMMEDIATE` while the plugin busy timeout was 500 ms. `api-op-degraded` returned `SERVICE_UNAVAILABLE` rather than reporting a partial success.
7. After releasing the lock, retrying the same `api-op-degraded` returned `ACCEPTED_QUEUED`; exactly one durable request and one delivery existed for that operation ID.
8. Both accepted deliveries reached `COMPLETED` with attempt count 1 and idempotency keys equal to the caller operation IDs.
9. The server and independent helper were stopped and restarted. The same protocol client rejoined with the same server-visible UUID.
10. Replaying `api-op-1` after restart again returned `ALREADY_ACCEPTED`; durable request counts remained one per accepted operation.
11. Final `PRAGMA integrity_check` returned `ok`; `PRAGMA foreign_key_check` returned no rows.

## SQLite schema evidence
The artifact also records `PRAGMA user_version = 0`; LoreItems intentionally does **not** use that SQLite field as its migration ledger. The exact tested source defines migrations V1 through V7 in `MigrationRunner`. `SQLiteStorageRuntime.start()` calls `migrationRunner.migrate(connection)` before it can transition to `READ_WRITE`, and the test log reached the writable-storage activation path before any API call. Therefore the runtime schema for this exact test is LoreItems schema **V7**. A future evidence collector should query `schema_history` directly rather than treating `PRAGMA user_version` as the application schema version.

## Sanitization
The retained permanent evidence intentionally omits hidden lore-instance UUIDs and delivery UUIDs. The GitHub Actions artifact contains the raw disposable-server logs for short-term audit, while `evidence-summary.json` retains the durable assertions needed for package review without publishing hidden instance identity.

## Harness-only correction
The first workflow attempt, run `31242194001` on `3477fc6103441573ccf628aacce92d99615d2bd4`, reached the intended live API behavior but failed its final evidence parser because the parser incorrectly assumed unknown-definition requests were not persisted and incorrectly prefixed external idempotency keys with `external:`. The production implementation was consistent with its contract; commit `e8921e0d3b633bc4a8c803aa37898acfeea2747e` corrected only the acceptance assertions. No production defect was found in this case.
