# Full-source LoreItems AI-OFF performance candidate — 2026-10-16

**DRAFT, NO DEPLOYMENT.** SMP LoreItems JAR remains absent intentionally. Existing `plugins/EnthusiaLoreItems/loreitems.db` and item PDC identities are not modified by this work.

## Provenance

| Source | Exact reviewed commit | Changes included |
| --- | --- | --- |
| [#79](https://github.com/wsg138/EnthusiaLoreItems/pull/79) | `81dd6b0e3c394d9feb8a6badebe76dd536b0b727` | Loaded-only non-snapshot block-holder/tile traversal; fresh inventory references |
| [#82](https://github.com/wsg138/EnthusiaLoreItems/pull/82) | `e8a5d498183bf8c143669332e009130060b6605f` | Skip ordinary hopper moves that carry no LoreItems PDC/nested evidence; tests |
| [#80](https://github.com/wsg138/EnthusiaLoreItems/pull/80) | `47f57cff2354e0f9f45f3aff40084812b30a78e6` | Accurate completed/skipped/failed queue accounting; per-source and whole-drain peak timing; isolate per-request runtime failures |

The overlapping `PaperPhysicalTrackingListener` intentionally retains #79's `getTileEntities(false)` and #80's scan-player outcome/whole-drain elapsed timer together. All other #80 files are copied unmodified from its verified head. The earlier [combined CPU-only PR #158](https://github.com/wsg138/EnthusiaLoreItems/pull/158) passed **25/25** hosted workflows on exact head `03394eb8152086efaa5b3e10e4c1c6366244a50d`, but those results **cannot** clear this newer code.

## What the new diagnostics mean

- `tracking.scan_event_completed` / `tracking.scan_periodic_completed`: callback returned from an accessible player/loaded chunk. This **does not** mean an item's location was proven and SQLite committed.
- `tracking.scan_event_skipped` / `tracking.scan_periodic_skipped`: inaccessible player or chunk; no item inventory was scanned. Do **not** call old DB position current based on this.
- `tracking.scan_event_failed` / `tracking.scan_periodic_failed`: an exception interrupted that queued scan. Earlier durable evidence remains, but current scan is **incomplete**, may have partially submitted work, and has **not** been durably retried by this change.
- `tracking.scan_player_peak_nanos`, `scan_chunk_peak_nanos`, `scan_periodic_peak_nanos`, and `scan_drain_peak_nanos`: process-local monotonically increasing elapsed maxima. They are **not exclusive CPU or end-to-end TPS metrics**; reset with plugin restart.
- `tracking.rejected` and player/chunk/periodic admission-rejection counters remain separate from failed/skipped work. A completely drained queue **does not prove previously rejected important work was recovered**.
- On an exceptional queued request, process later queued work in the same tick rather than aborting the whole drain. This is exception isolation, not guaranteed recovery of the poison request.

## Safety and release gates

1. Run **all 25 hosted workflows on this new exact source HEAD**, including Codacy, reproducible artifact, Paper 26.2, anomaly, lifecycle, and tracking contract acceptance. No inherited green badges.
2. Independently inspect queue exception containment, nested identity fastpath, loaded-only non-snapshot resolution, and malformed PDC behavior. Reject false CONFIRMED_NOW from incomplete/inaccessible scans.
3. On disposable, matched AI-OFF server loads, measure whole-tick MSPT, player/chunk/periodic peaks, queue rejection by category, hopper hotpath, GC, and item location correctness.
4. Address #39 and #83/#84 missing admission-recovery design. Do **not** assume this patch's stats resolve the overflow. Preserve #95 real cross-chunk double-chest coverage and #103 world-name→UUID migration gate.
5. After >4 days without tracking, PDC item identities should remain but old DB locations may be stale. Preserve original DB and WAL, keep inaccessible/ambiguous instance states unresolved until strong new physical observations; never spawn replacements, rewrite historical evidence, or force-load chunks to find the two–three instances.
6. No live/TEST plugin restore or restart, SFTP, config/DB edits, `main` merge, destructive inventory ops, or retroactive location claims until explicit owner-approved release window and rollback verification.

**CI success is necessary but not sufficient for release.**
