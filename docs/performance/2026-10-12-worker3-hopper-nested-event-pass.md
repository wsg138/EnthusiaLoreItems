# Worker 3 — hopper, nested identity, and event fan-out (2026-10-12)

Scope: issue #86, isolated from production. This patch does **not** fix chunk-scan queue admission (#39), GUI cursor semantics (Worker 2), identity persistence (Worker 4), or all event listeners (integration Worker 5).

## Changes and reasons

- Physical hopper observer now asks the bounded identity-evidence collector whether the moved stack (including nested shulker/bundle contents and partial/malformed PDC) could be tracked. Plain stacks return without capturing expensive inventory holders or queuing source/destination inventory scans; each call still records its event count and duration.
- For evidence-bearing moves, retain source/destination post-event reconciliation, with the destination's redundant generic rescan omitted when source and destination resolve to the same immutable inventory reference. The destination identity-match scan remains in place.
- Anomaly observer likewise skips ordinary untracked hopper items before resolving any inventory holder. Tracked and malformed/nested evidence still runs the existing anomaly logic.
- Block/physical references use Paper `getHolder(false)` and `getState(false)`, and block resolution checks `isChunkLoaded` before block access. No live Bukkit inventory or holder is retained in these references.
- Template-update move events retain **all** source/destination invalidation obligations. References are coalesced by immutable identity within a server tick; a single next-tick callback enqueues distinct affected references. Do not skip template work merely because the moved item is ordinary: an outdated tracked item elsewhere in those inventories may need discovery.
- Replace thread-safe temporary identity maps with per-invocation HashMaps in main-thread-only physical scanner paths. No asynchronous mutable map sharing was added.

## Reproducible synthetic evidence (not a production benchmark)

Command: `gradle clean build --no-daemon --console=plain` with local Gradle 9.2.1; Java compilation targets 21 and runs via available toolchain. The adapter test suite includes:

- 1,000 sequential plain hopper moves: 1,000 recorded physical events; **zero** physical deferred scan callbacks, verified after a scheduler tick.
- Tracked moves, nested shulkers, bundles, incomplete PDC and accepted work drained on close: actual executed regression tests, no MockBukkit skips.
- Anomaly observer plain-item guard and malformed-stack report: actual executed regression tests.
- 1,000 same-reference template moves: one resolved template scanner invocation after the next-tick batch.
- Inventory snapshot non-snapshot holder assertions, unloaded block reference with **zero** world block lookups, and fresh inventory returned after natural load.
- All listed focused suites completed with zero skipped, zero failed tests on this local build. Other unrelated pre-existing MockBukkit tests may be skipped; inspect Gradle XML counts before drawing conclusions.

The instrumentation is **operation-count proof**, not a measured Java 25 / Paper 26.2 server-thread MSPT improvement. The 1,000 synthetic moves do not simulate the real SMP's loaded hoppers, storage mix, or profiler overhead. No live server was accessed.

## Remaining integration gates

1. Worker 1 must apply the non-snapshot `Chunk.getTileEntities(false)` change in its listener, preserve bounded/fair loaded discovery, and own queue admission/retry.
2. Worker 5 must reconcile the remaining pre-event `PaperIdentityObservationScanner.inventoryLocation` holder lookup and the separate protection listener's hopper costs; this worker cannot modify their source ownership. Verify those listeners don't reconstruct block snapshots on every ordinary move.
3. Review same-inventory/cancelled/re-written move semantics, double chests, block entities and NPC inventories on disposable **Paper 26.2 / Java 25**.
4. Run independent code review, hosted Codacy on exact commit, full 25-workflow acceptance, and WAL/rollback exercises before release approval. Do not merge/deploy from Worker 3 alone.
5. Compare time-per-event metrics and matched AI-OFF Spark captures only after owner-approved deployment; no attributable live CPU reduction is claimed here.

Historical `tracking.scan_event_rejected` counts are from queued player/chunk scans, **not** ordinary hopper callbacks. This patch must not be advertised as solving the 20,357 rejected admissions.
