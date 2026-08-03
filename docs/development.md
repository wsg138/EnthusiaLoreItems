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

## Durable external delivery foundation

The versioned Bukkit service now durably accepts idempotent external delivery requests when an active definition exists. One transaction creates the instance identity, pending direct-delivery record, and external operation result. Replaying the same external operation ID with the same arguments returns `ALREADY_ACCEPTED` and creates no additional instance.

Physical inventory insertion remains deliberately inactive until the next implementation phase. `ACCEPTED_QUEUED` means durable intent exists, not that an item was inserted.

Pending deliveries are claimed in bounded pages with a claim token and lease. Compare-and-set transitions require the expected state and claim token. A reservation that expires across restart is moved to `REVIEW_REQUIRED`; it is never silently retried because a later physical side effect could be ambiguous.

## Degraded startup and recovery

If the database cannot open or migrate, the runtime enters `DEGRADED_READ_ONLY`. No write-capable service is published, and delivery requests return `SERVICE_UNAVAILABLE`. The plugin does not pretend a request was accepted.

Operators should preserve the database file, inspect the logged startup error, verify filesystem permissions and free space, and restore from backup when corruption is suspected. Do not delete the database merely to make the plugin start: that would discard authoritative workflow and identity records.

## Current limitations

The current PR 2 slice creates definitions and supports administrator-held-item adoption only. It does not execute queued direct delivery, handle offline/full-inventory delivery, add environmental protections or broad listeners, manage display entities or mob pickup, warn about duplicate observations, expose GUIs, edit templates, execute deletion, or run campaigns.

No live Paper/Leaf server behavior has been tested. Automated codec and SQLite tests do not prove real-server command registration, item-component serialization, reload behavior, or operator workflow.
