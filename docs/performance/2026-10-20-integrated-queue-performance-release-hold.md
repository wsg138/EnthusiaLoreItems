# Combined LoreItems AI-OFF candidate — 2026-10-20

**DRAFT. No SMP/TEST installation, main merge or database mutation.**

Combined from exact-head green draft [#158](https://github.com/wsg138/EnthusiaLoreItems/pull/158) at `69b49c914cce133bb49ffd8a814cb060ee850244` (25/25 hosted workflows) and exact-head green draft [#93](https://github.com/wsg138/EnthusiaLoreItems/pull/93) at `f79af6876869eed83c0eaaae7d1eee79a6bd3828` (25/25 workflows).

This integration transplants the 10 changed #93 source/test/doc files onto #158 and deliberately resolves their four overlapping files:

- `PaperPhysicalTrackingListener`: chooses #93's generation-fenced, natural-load seed/recovery and 2ms drain implementation, whose new extracted `PaperChunkContainerReconciler` retains #79's loaded-only `getTileEntities(false)` and non-snapshot inventory references; preserves #158's hopper fastpath and scanner components.
- `PaperTrackingScanRequest`: chooses #93's generation token and loaded-only run behavior; do not treat skipped/inaccessible as completed.
- `PaperPhysicalScanTelemetry`: chooses #93's boolean outcomes and error isolation compatible with #158's `TrackingMetrics` skipped/failed/peak gauges.
- `PaperPhysicalTrackingQueueCoalescingTest`: combines #93's queue, generations, retries and 2ms budget tests with all five unique #158 truthful telemetry/timing/failure regressions. Multi-request assertions are adapted to bounded eventual draining across up to 16 ticks, not unconditional single-tick completion.

**Mandatory new exact-head verification**: 25 workflows including CI, Codacy and real Paper 26.2 + Java25. Neither parent's green status transfers automatically. Must inspect partial scan/truncation, persisted ACK truth and queue saturation under actual AI-OFF load.

**UNRESOLVED release gates**: #39 rejected scan catch-up and fairness under 20k+ event rejections; #83 physically correct event-first ACK and >256 nested/large-inventory cursor continuation; #95 complete real 48-case 54-slot cross-chunk double-chest and unload/reload; #103 legacy name-to-UUID SQLite migration; historical item location gaps while LoreItems JAR was disabled; WAL-safe backup, restore drill, physical PDC reobservation and owner-approved rollback. Neither old `CONFIRMED_NOW` nor an empty scan proves present possession.

No source or evidence of live deployment is created by this branch.
