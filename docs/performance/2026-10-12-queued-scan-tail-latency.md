# Queue scan tail-latency diagnostics — October 12, 2026

## Motivation

AI-off Minecraft Spark profiles `vzQFP8HmFH`, `rTX7VwSBCe`, and the later post-restart `BIyhziBxcY` show physical tracking work but cannot assign exclusive tick duration to a single LoreItems scan. The production diagnostics previously recorded a peak scan queue of 512 and 871 event/32 periodic rejects. Cumulative scan time alone can hide expensive individual player or chunk scans. These separate queue metrics complement the synchronous hopper timers introduced by PR #73.

## New fixed-cardinality metrics

- `tracking.scan_player_peak_nanos`: highest *one queued player request* execution duration since plugin startup.
- `tracking.scan_chunk_peak_nanos`: highest *one non-periodic chunk lifecycle request* duration.
- `tracking.scan_periodic_peak_nanos`: highest *one optional periodic chunk request* duration.
- `tracking.scan_drain_peak_nanos`: highest elapsed time from entry through exit of one `PaperPhysicalTrackingListener.drain()` (the work scheduled once per tick), including the queue polling, executed requests, and metrics updates. The timer uses `finally`, so an exceptional drain also contributes to the peak.

The operator's existing `LoreItems diagnostics:` view shows two new lines: `Scan peak player / chunk (ms)` and `Scan peak periodic / drain (ms)`. Integer milliseconds are **truncated**; a zero on screen may reflect a nonzero submillisecond sample. Monotonic process-local atomics retain only peak amounts, no player, world, inventory or item identifiers, no stack traces and no per-event logs. Counters restart with plugin initialization, so never subtract peaks across restarts. Compare with Spark at similar uptime and workload.

The request-level peaks include `PaperTrackingScanRequest.run()` execution (including nested scanning), and deliberately exclude diagnostics logging and warning stack traces after a failed request. The drain peak **can include multiple requests** and can therefore be far larger than a single request. Neither includes unrelated `InventoryMoveItemEvent` listeners, all other plugins, world ticking outside this callback, GC pauses, or asynchronous persistence. Peak elapsed time is not exclusive CPU, percentile latency, or proof that LoreItems alone caused a slow tick. The queue itself still has a **count-based, not time-based**, per-tick budget; the new measurements are intentionally observational rather than changing scheduling or item history.

## Verification and safety

- `TrackingMetricsTest`: same process views, negative samples ignored, later smaller samples never reduce a maximum.
- `PaperPhysicalTrackingQueueCoalescingTest`: actual online-player, inaccessible/offline-player, non-periodic chunk and periodic chunk callbacks each update the appropriate peak. Outcomes are independently classified as completed, skipped or failed, and the drain peak encloses processing even when a request throws.
- Review all exact-head CI and runtime acceptance checks on the draft PR. No changes to scan budget, queue capacity, player/chunk admission or evictions, lifecycle presence, identity, SQLite storage, scheduled callback semantics or player data. Do not deploy without a separately approved backup/staging plan.

Related [#71](https://github.com/wsg138/EnthusiaLoreItems/issues/71), [#39](https://github.com/wsg138/EnthusiaLoreItems/issues/39), [PR #73](https://github.com/wsg138/EnthusiaLoreItems/pull/73).

## Integration with exception and accessibility accounting

This is a **stacked review branch based on draft #76 at `969d565c79f290dc6c305f5fd28048c3386c9be8`**, not a production release. It deliberately replaces the original isolated #75 test expecting exceptions to escape the queue. `PaperPhysicalScanTelemetry.execute` catches a failed request and continues with the next one, classifies offline-player/unloaded-chunk returns as skipped, and records peak timings in `finally` for successful, skipped and failed requests alike. `drain()` also records the entire tick's queued-scan cost. Tests must not conflate scheduler attempts with physically observed item identities.

An earlier #75 test expected one `NullPointerException` to bubble from `drain()`; that is incompatible with intentional #76 containment and is superseded by the new isolated failure-plus-following-valid-request test. Peak metrics remain bounded and process-local; a wall-clock request peak can include OS scheduling, GC or other interference. #39 overflow debt and #45 LAST_CONFIRMED remain unresolved.
