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

The definition, revision 1 template, and `definition_created` audit event commit in one SQLite unit of work. An existing active lookup key returns a conflict without appending an audit event. Storage failure, queue rejection, degraded mode, or shutdown leaves the command unavailable rather than claiming success.

This operation creates only the reusable definition and its first template revision. It does not adopt the held item, assign an instance UUID, replace the item in the player's hand, or deliver a physical item.

## Held-item adoption

Administrators with `enthusia.loreitems.admin.adopt` can adopt exactly one item from the selected hotbar slot into an existing active definition with:

```text
/loreitems adopt <lookup-key>
```

The command rejects air, stacks larger than one, already tracked items, and malformed LoreItems identity without changing the inventory. It snapshots a SHA-256 fingerprint and selected slot on the Paper thread, then persists a fresh instance identity, missing/unresolved current state, claimed `ADOPT_HELD_ITEM` mutation, and preparation audit before any inventory mutation.

After durable preparation, the command returns to the Paper thread and requires the same player, selected slot, untracked identity, amount, and fingerprint. It writes hidden definition, instance, and applied-revision identity to a clone, forces amount and maximum stack size to one, replaces only the exact slot, and immediately rereads that slot. Visible metadata and foreign persistent data remain unchanged by default.

A verified write completes the mutation through `APPLIED`, `VERIFIED`, and `COMPLETED` in one SQLite transaction, appends a confirmed player-inventory observation, advances current state, and writes an audit event. Slot changes, disconnects, shutdown scheduling failures, claim expiry, verification failure, or uncertain persistence are moved to `REVIEW_REQUIRED` rather than retried blindly. Expired mutation claims are recovered in a bounded startup batch and by a bounded non-overlapping periodic worker, so an overflow batch cannot remain claimed indefinitely.

Only one adoption per administrator is active at a time, and global in-flight adoption bookkeeping is hard-bounded. No live `Player`, inventory, or `ItemStack` reference crosses the asynchronous database boundary.

## Durable direct delivery

Administrators with `enthusia.loreitems.admin.give` can queue one fresh instance for themselves, an online or cached player name, or an explicit offline-player UUID:

```text
/loreitems give <lookup-key> [online/cached player name or UUID]
```

The command accepts no physical side effect before SQLite commits a fresh instance identity, queued-delivery observation/current-state projection, direct-delivery row, external idempotency result, and audit event in one transaction. Replaying the same external operation ID with the same arguments returns `ALREADY_ACCEPTED` and creates no additional instance. Synchronous queue rejection, null service results, and lifecycle races are reported as failures rather than escaping the command thread or claiming durable acceptance.

A bounded worker claims only the smaller of the configured delivery batch and per-tick mutation budget. Each claim carries the exact definition revision and immutable encoded template. Paper template decoding, hidden identity assignment, empty-slot selection, insertion, and immediate reread occur only on the server thread. The item is forced to amount and maximum stack size one, and completion is persisted only after the exact slot contains the expected definition, instance UUID, and revision.

Offline players and full inventories return safely to `PENDING` with a retry time. Nothing is dropped as overflow. Player join wakes matching pending deliveries, while a bounded low-frequency poll resumes queued work after restart and eventually retries full inventories. A crash or persistence failure after physical insertion is never retried blindly: claimed or partially applied work expires or is moved explicitly to `REVIEW_REQUIRED` for staff inspection. Synchronous defer, completion, or review-submission failures are fenced through the same durable recovery path.

Successful completion atomically advances `RESERVED -> APPLIED -> VERIFIED -> COMPLETED`, appends a confirmed player-inventory observation, replaces the queued current-state projection, clears the claim, and records the verified item fingerprint and inventory slot in audit history. No live `Player`, inventory, or mutable `ItemStack` reference crosses the asynchronous database boundary.

## Environmental, durability, conversion, and void protection

The Paper listener recognizes both valid tracked identity and malformed LoreItems identity evidence. It cancels natural item despawn, combustion, item-entity merging, ordinary item-entity damage such as fire, lava, explosions, and cactus, and durability damage to player-held or entity-held tracked items. Invalid identity evidence is preserved rather than repaired, split, or deleted. Untracked vanilla items are unaffected.

Identity-losing conversions are rejected before the platform can consume or replace the tracked stack. Covered paths include player consumption, crafting and automated crafters, cooking, furnace and brewing fuel, brewing contents, composting, block and entity placement, flower pots, bucket conversion, entity-bucket capture, consumptive block/entity interactions, projectile and arrow consumption, elytra firework use, bow ammunition consumption, and dispenser behavior. Inventory result extraction is denied when a tracked input or malformed identity is involved, covering smithing, grinding, anvil, and other result-slot conversions without mutating the evidence.

Void damage is the intentional terminal-loss exception. The event remains cancelled while the plugin persists a claimed `VOID_TERMINAL_LOSS` mutation and preparation audit off-thread. Known unresolved duplicate, malformed, conflicting-observation, or identity-mismatch anomalies block preparation. The Paper thread then reacquires the exact item entity by UUID, verifies the same hidden identity, and requires the entity to remain below the world's minimum height before removing it.

Verified removal completes one SQLite transaction that advances the mutation through `APPLIED`, `VERIFIED`, and `COMPLETED`, changes the instance lifecycle to `VOID_DESTROYED`, appends a `TERMINAL_VOID` observation, advances current state to `TERMINAL_VOID`, and records audit evidence. If the item was rescued before removal, the claimed mutation completes as an audited abort and the active instance remains unchanged. Missing entities, identity changes, scheduling failure, claim expiry, or uncertain post-removal persistence enter `REVIEW_REQUIRED` rather than causing a blind second removal or restoration.

Protection remains active during storage startup and degraded mode, but terminal void destruction is withheld unless durable storage has accepted the intent. In-flight void work and retry cooldown bookkeeping are bounded, no world or inventory scan occurs, no chunk is force-loaded, and no live Bukkit entity or item reference crosses the asynchronous storage boundary.

## Display entities and mob pickup prevention

Tracked items placed in ordinary or glow item frames and in armor-stand equipment slots are observed through Paper's event surface. Item-frame changes and breaks, armor-stand manipulation, and armor-stand damage schedule a next-tick reread of the exact entity UUID and slot. Glow item frames follow the same path because they implement the item-frame contract. No world scan or chunk force-load is used.

A confirmed item records the exact display entity location and slot as `CONFIRMED_NOW`. When the same exact durable location is observed empty after a supported removal or destruction event, the previous location is retained as `LAST_CONFIRMED` rather than erased or replaced with a guess. Unknown instances, definition/revision mismatches, inactive instances, unresolved identity anomalies, conflicting current state, terminal state, and stale removal evidence do not replace durable current state. Observation, current-state compare-and-set, and audit evidence commit atomically on the bounded SQLite executor.

Paper work is coalesced by display entity, location type, slot, and event source. Candidate identities per coalesced slot and queued persistence requests are hard-bounded. When the configured concurrency limit is occupied, observations enter a bounded FIFO and drain iteratively as prior writes complete instead of recursing or being silently discarded. Capacity exhaustion logs a warning and preserves existing durable evidence. No live Bukkit entity or mutable item reference crosses the asynchronous storage boundary.

Non-player entities cannot pick up an item that contains either valid tracked identity or malformed LoreItems identity evidence. Player pickup and untracked vanilla item pickup remain unchanged. The listener does not repair, split, delete, or otherwise mutate malformed physical evidence.

## Identity anomalies, warnings, and initial administration

Event-bounded inventory, dropped-item, display, interaction, conversion, and protection observations detect two copies carrying the same instance UUID and recoverable malformed tracked stacks. Both physical copies remain usable and unchanged. Evidence includes the observed locations and is persisted atomically with anomaly and audit records on the bounded SQLite executor.

Anomaly persistence uses bounded in-flight work, a bounded coalescing queue, iterative synchronous-completion draining, cooldowns for repeated identical evidence, and explicit overflow logging. New active anomalies request an immediate staff/console warning. A separate bounded query worker repeats warnings every five minutes while unresolved warning-eligible anomalies remain. Refreshing the same anomaly does not create a five-minute warning loop.

Administrators with `enthusia.loreitems.admin.audit` can use the initial paginated command surface:

```text
/loreitems anomalies [page]
/loreitems audit <instance-uuid> [page]
/loreitems recovery [page]
```

These commands expose unresolved anomalies, current state, recent observation/audit evidence, and non-terminal delivery or mutation recovery records. They are read-only. Explicit anomaly resolution, recovery mutation controls, and GUI administration remain assigned to later phases.

## Static analysis policy

Codacy continues to analyze Java source and tests with PMD and the configured Java analyzers. Its metric engine is excluded only for exact policy-boundary, lifecycle-orchestration, and fixture files whose line-count thresholds do not represent an item-safety or lifecycle defect. The immutable SQLite migration remains excluded from non-SQLite SQL policies. The final rationale and exact zero-annotation evidence are recorded in handoff report 0026.

## Degraded startup and recovery

If the database cannot open or migrate, the runtime enters `DEGRADED_READ_ONLY`. No write-capable service is published, and delivery requests return `SERVICE_UNAVAILABLE`. The plugin does not pretend a request was accepted.

Operators should preserve the database file, inspect the logged startup error, verify filesystem permissions and free space, and restore from backup when corruption is suspected. Do not delete the database merely to make the plugin start: that would discard authoritative workflow and identity records.

## Current limitations

Implementation PR 2 is complete in automated verification: held-item definition creation and adoption, durable direct delivery, environmental/durability/conversion protection, terminal void loss, supported display entities, non-player pickup prevention, initial audit/recovery command views, duplicate and malformed-stack evidence, and five-minute staff warnings are implemented within the documented phase boundary.

Broad event-driven tracking and reconciliation across all player/container/nested locations, Ender Chest support, paginated GUIs, explicit anomaly resolution and recovery actions, metrics/backpressure reporting, editing, deletion, campaigns, and EnthusiaTags integration remain later-phase work.

No live Paper/Leaf server behavior has been tested. Automated MockBukkit, codec, application, architecture, and SQLite tests do not prove real-server event ordering, item-component serialization, reload behavior, command registration, or operator workflow.
