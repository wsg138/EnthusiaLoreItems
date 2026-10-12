# Synchronous hopper-event timing evidence (October 12, 2026)

## Why

Recent AI-OFF SMP Java Spark profiles [vzQFP8HmFH](https://spark.lucko.me/vzQFP8HmFH) and [rTX7VwSBCe](https://spark.lucko.me/rTX7VwSBCe) repeatedly contain the path `InventoryMoveItemEvent -> PaperPhysicalTrackingListener.onInventoryMove -> PaperPhysicalInventorySnapshot.capture -> CraftInventory.getHolder -> block state/NBT decoding` and a separate next-tick `scanReference`/nested-item reconciliation. This establishes the **presence** of a potentially expensive callback; it does not prove exclusive per-tick CPU time. The current dashboard reports only physical scan-queue time, which misses both synchronous move-event snapshots and non-queued next-tick processing. In October 12 owner screenshots, the scan queue's historical peak was 512 and cumulative event/periodic rejections were 871/32, although no further rejections occurred over the intervening sample.

## Added bounded, process-local counters

- `tracking.hopper_move_events`: incremented in `finally` for each received non-cancelled `InventoryMoveItemEvent` handler invocation, including ones whose processing throws.
- `tracking.hopper_move_event_nanos`: cumulative synchronous elapsed time from handler entry through snapshot capture, identity inspection and scheduling (includes Bukkit block-state holder snapshots), excluding actual deferred reconciliation.
- `tracking.hopper_move_deferred`: incremented after scheduled inventory-move reconciliation runs, including runs that throw.
- `tracking.hopper_move_deferred_nanos`: cumulative main-thread time for both source/destination reconcile passes and matching identity submissions run inside that scheduled callback. Does **not** include asynchronous database persistence.
- `tracking.hopper_move_event_peak_nanos` and `tracking.hopper_move_deferred_peak_nanos`: maximum elapsed time for a **single** execution of either phase since startup (monotonic until next plugin restart). There is no per-player or per-item history retained.
- Visible as `Hopper move event / deferred counts`, `Hopper move event / deferred (ms)` and `Hopper move peak event / deferred (ms)` in the existing staff tracking GUI diagnostics.

All labels are fixed strings. Each invocation adds two `System.nanoTime()` calls and bounded atomic sums, counts and monotonic peak-gauge updates, with no per-player/item/world tags, unbounded logs, or retained Bukkit references. No scanning, queue, identity, event ordering, reconciliation, item mutation or persistence policy changes. Metrics are cumulative from plugin startup.

## Separate queue saturation source attribution

The existing historical `scanEventRejected` count combines **queued player scans** and **queued chunk-load scans**, not hopper inventory-move events. The actual current `PaperPhysicalTrackingListener.enqueue` sources are startup/player-join and chunk-load; other entity/chunk unload paths scan synchronously and are not represented in this deferred queue. The prior 871 event rejections therefore cannot be assigned to hoppers.

This change adds **fixed-category**, process-local counters for `tracking.scan_player_rejected` and `tracking.scan_chunk_rejected`. `PaperPhysicalScanTelemetry.reject` preserves the previous aggregate `tracking.scan_event_rejected` and increments exactly one subcategory when the refused request is not optional periodic reconciliation. Existing periodic rejection accounting and unchanged source ordering are preserved. The GUI displays `Rejected event scans player / chunk`. On the new version, their **same-uptime total** should equal the aggregate event-rejected count. Test full mixed and all-event queue saturation to make sure nothing is dropped from accounting. Counters begin at zero **on next plugin restart**; older 871/32 screenshot cannot be retroactively split.

## Interpretation and limitations

Take screenshots from the **same server uptime** at T0 and T1, with actual wall timestamps, then subtract the counters. `(eventNanos delta / event count delta)` and `(deferredNanos delta / deferred count delta)` are approximate mean milliseconds **per invocation**. Total elapsed time divided by wall time approximates fraction of wall time spent in these callbacks but is *not* exclusive CPU cost in an asynchronous JVM profile. Peaks are **per-invocation** maxima across uptime and do not establish a per-tick total or p95. Never subtract across server restarts. If either count delta is zero, its per-invocation average is unavailable, not zero.

The `onInventoryPickup` (hopper pickup from ground) and `PaperIdentityAnomalyListener.onInventoryMove` paths remain outside these four metrics. Existing `tracking.scan_event_nanos` and `tracking.scan_periodic_nanos` measure the independent queued scan worker; **do not equate those with all plugin work**. Neither set of counters establishes which code was executing during one 100ms slow tick. Correlate with a correctly configured `--force-java-sampler` main-thread Spark sample and the SMP's TPS/MSPT time history. Long-term follow-up requires bounded percentiles/histograms or per-tick total budgets implemented without expensive hot-path logging; the current peak is one invocation, not the sum of multiple calls within a slow tick.

## Verification

`TrackingMetricsTest` validates accumulation, plugin's additional queue view and rejection of negative timing values. Exact-head full Java Gradle, Codacy, Lizard, 25 Paper acceptance workflows and reproducible JAR must pass. Synthetic tests do not constitute a production benchmark. Do not deploy the diagnostics PR to live SMP without owner-authorized backup and maintenance-window plan.

Related: [issue #71](https://github.com/wsg138/EnthusiaLoreItems/issues/71), [issue #39](https://github.com/wsg138/EnthusiaLoreItems/issues/39), and [PR #72](https://github.com/wsg138/EnthusiaLoreItems/pull/72) (independent performance fix).
