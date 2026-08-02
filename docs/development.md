# Development and foundation status

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

## Durable external delivery foundation

The versioned Bukkit service now durably accepts idempotent external delivery requests when an active definition exists. One transaction creates the instance identity, pending direct-delivery record, and external operation result. Replaying the same external operation ID with the same arguments returns `ALREADY_ACCEPTED` and creates no additional instance.

Physical inventory insertion remains deliberately inactive until the next implementation phase. `ACCEPTED_QUEUED` means durable intent exists, not that an item was inserted.

Pending deliveries are claimed in bounded pages with a claim token and lease. Compare-and-set transitions require the expected state and claim token. A reservation that expires across restart is moved to `REVIEW_REQUIRED`; it is never silently retried because a later physical side effect could be ambiguous.

## Degraded startup and recovery

If the database cannot open or migrate, the runtime enters `DEGRADED_READ_ONLY`. No write-capable service is published, and delivery requests return `SERVICE_UNAVAILABLE`. The plugin does not pretend a request was accepted.

Operators should preserve the database file, inspect the logged startup error, verify filesystem permissions and free space, and restore from backup when corruption is suspected. Do not delete the database merely to make the plugin start: that would discard authoritative workflow and identity records.

## Current limitations

This foundation still does not create definitions from held items, adopt items, mutate inventories, protect physical items, track locations, expose GUIs, edit templates, execute deletion, or run campaigns. Paper item-template/PDC codec round trips, the remaining repository families, broader static-analysis configuration, and complete PR review still remain within PR 1.

No live-server behavior has been tested.
