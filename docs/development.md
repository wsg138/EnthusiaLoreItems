# Development status

## Build

The project targets Java 21 and uses a multi-module Gradle Kotlin DSL build.

```text
gradle clean check
gradle :plugin:shadowJar
```

The deployable jar is produced by `:plugin:shadowJar`.

## Module direction

```text
plugin -> api, adapters-paper, adapters-sqlite
adapters-paper -> api, application, domain
adapters-sqlite -> application, domain
api -> application
application -> domain
domain -> JDK only
```

`architecture-tests` imports the compiled modules only to verify dependency rules and cycles.

## Foundation configuration

`plugins/EnthusiaLoreItems/config.yml` is created from the packaged defaults by the off-thread lifecycle worker. It is parsed into an immutable `FoundationConfiguration` and validated before publication.

Reload builds a complete candidate snapshot and swaps it atomically only after validation. Database busy timeout, database queue capacity, and database shutdown timeout are startup resources; changing those values is rejected during reload and requires a restart. Reload does not recreate executors, close SQLite, or discard pending work.

All queue, claim, paging, warning, and per-tick settings have explicit safe bounds. Unknown or duplicate configuration keys fail validation instead of silently using a misspelled value.

## Foundation database

The production path is:

```text
plugins/EnthusiaLoreItems/loreitems.db
```

Migration `V1__foundation.sql` establishes the durable records required by the planning documents. SQLite connections enable foreign keys, WAL mode, `synchronous=NORMAL`, and a bounded busy timeout.

A single bounded database executor serializes storage work. Queue saturation rejects new work rather than allowing unbounded memory growth. Plugin shutdown stops acceptance, drains for the configured bounded timeout, and then forces executor shutdown if necessary.

Startup configuration parsing, directory creation, database opening, and migration run away from the server thread. The Bukkit service is registered immediately through a stable delegate but returns `SERVICE_UNAVAILABLE` until writable storage is active.

## Held-item definition creation

Administrators with `enthusia.loreitems.admin.create` can create the first durable definition revision with:

```text
/loreitems create <lookup-key> <display name>
```

The command is player-only because it snapshots the item in the player's main hand. Paper item access and serialization stay on the server thread. The snapshot is cloned, normalized to an amount and maximum stack size of one, stripped of any existing LoreItems instance identity, and converted to an immutable encoded template before asynchronous persistence begins. Air and malformed LoreItems identity evidence are rejected without changing the held item.

The definition, revision 1 template, and `definition_created` audit event commit in one SQLite unit of work. An existing active lookup key returns a conflict without appending an audit event. Storage failure, degraded mode, or shutdown leaves the command unavailable rather than claiming success.

This operation creates only the reusable definition and its first template revision. It does not adopt the held item, assign an instance UUID, replace the item in the player's hand, or deliver a physical item.

## Held-item adoption

Administrators with `enthusia.loreitems.admin.adopt` can adopt exactly one item from the selected hotbar slot into an existing active definition with:

```text
/loreitems adopt <lookup-key>
```

The command rejects air, stacks larger than one, already tracked items, and malformed LoreItems identity without changing the inventory. It snapshots a SHA-256 fingerprint and selected slot on the Paper thread, then persists a fresh instance identity, missing/unresolved current state, claimed `ADOPT_HELD_ITEM` mutation, and preparation audit before any inventory mutation.

After durable preparation, the command returns to the Paper thread and requires the same player, selected slot, untracked identity, amount, and fingerprint. It writes hidden definition, instance, and applied-revision identity to a clone, forces amount and maximum stack size to one, replaces only the exact slot, and immediately rereads that slot. Visible metadata and foreign persistent data remain unchanged by default.

A verified write completes the mutation through `APPLIED`, `VERIFIED`, and `COMPLETED` in one SQLite transaction, appends a confirmed player-inventory observation, advances current state, and writes an audit event. Slot changes, disconnects, shutdown scheduling failures, claim expiry, verification failure, or uncertain persistence are moved to `REVIEW_REQUIRED` rather than retried blindly. Expired mutation claims are recovered in a bounded startup batch.

Only one adoption per administrator is active at a time, and global in-flight adoption bookkeeping is hard-bounded. No live `Player`, inventory, or `ItemStack` reference crosses the asynchronous database boundary.

## Durable direct delivery

Administrators with `enthusia.loreitems.admin.give` can queue one fresh instance for themselves, an online or cached player name, or an explicit offline-player UUID:

```text
/loreitems give <lookup-key> [online/cached player name or UUID]
```

The command accepts no physical side effect before SQLite commits a fresh instance identity, queued-delivery observation/current-state projection, direct-delivery row, external idempotency result, and audit event in one transaction. Replaying the same external operation ID with the same arguments returns `ALREADY_ACCEPTED` and creates no additional instance.

A bounded worker claims only the smaller of the configured delivery batch and per-tick mutation budget. Each claim carries the exact definition revision and immutable encoded template. Paper template decoding, hidden identity assignment, empty-slot selection, insertion, and immediate reread occur only on the server thread. The item is forced to amount and maximum stack size one, and completion is persisted only after the exact slot contains the expected definition, instance UUID, and revision.

Offline players and full inventories return safely to `PENDING` with a retry time. Nothing is dropped as overflow. Player join wakes matching pending deliveries, while a bounded low-frequency poll resumes queued work after restart and eventually retries full inventories. A crash or persistence failure after physical insertion is never retried blindly: claimed or partially applied work expires or is moved explicitly to `REVIEW_REQUIRED` for staff inspection.

Successful completion atomically advances `RESERVED -> APPLIED -> VERIFIED -> COMPLETED`, appends a confirmed player-inventory observation, replaces the queued current-state projection, clears the claim, and records the verified item fingerprint and inventory slot in audit history. No live `Player`, inventory, or mutable `ItemStack` reference crosses the asynchronous database boundary.

## Degraded startup and recovery

If the database cannot open or migrate, the runtime enters `DEGRADED_READ_ONLY`. No write-capable service is published, and delivery requests return `SERVICE_UNAVAILABLE`. The plugin does not pretend a request was accepted.

Operators should preserve the database file, inspect the logged startup error, verify filesystem permissions and free space, and restore from backup when corruption is suspected. Do not delete the database merely to make the plugin start: that would discard authoritative workflow and identity records.

## Current limitations

The current PR 2 slices create definitions, adopt held items, and execute durable direct delivery with offline/full-inventory waiting. Environmental and durability protection, void terminal loss, display entities, mob pickup prevention, broad reconciliation, duplicate/malformed warnings, GUIs, editing, deletion, and campaigns remain unfinished.

No live Paper/Leaf server behavior has been tested. Automated codec and SQLite tests do not prove real-server command registration, item-component serialization, reload behavior, or operator workflow.
