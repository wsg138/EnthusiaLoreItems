# Non-snapshot Paper inventory reference resolution — 2026-10-12

This candidate starts from canonical main `86999c032eaf658d6e66223d73aedf58cbd6cee2`, after #72's holder-once capture overload and #74's single inventory read during chunk traversal.

## Scope / invariant

- Capture an inventory's holder **once** with Paper `getHolder(false)`, then pass the same holder to `PaperInventoryReference.capture(inventory, holder)`.
- Resolve block inventories with `getState(false)` **only after** checking the chunk is loaded. Resolve fresh contents on every call; never keep a live Bukkit holder across ticks or force-load a missing chunk.
- Apply the same non-snapshot holder access to identity-location capture.
- Iterate `Chunk.getTileEntities(false)` on chunk-load, scheduled scans, and unload callbacks to avoid full tile-entity copies **before** the existing bounded scanner begins. Keep the full existing set of tile entities and the existing scan budget.
- Keep player/ender/entity/block location semantics, nested shulker/bundle discovery, identity provenance, LAST_CONFIRMED persistence and FIFO scan queues unchanged. The internal nested `BlockStateMeta.getBlockState()` call is **not** optimized away.
- Tests enforce the overload signature and single holder lookup; block resolution tests enforce loaded-chunk gating and freshness.

## Evidence limits / next gates

Original conflicting [PR #70](https://github.com/wsg138/EnthusiaLoreItems/pull/70) reported isolated Paper 26.2 / Java 25 100-run reference lookup medians of 3.5468 -> 0.0951 ms (shulker-filled chest) and 0.7110 -> 0.0424 ms (hopper). Those are **prior branch operation benchmarks**, not newly reproduced numbers, nor a measured end-to-end chunk unload speedup or live SMP TPS comparison. Source stack includes `CraftChunk.getTileEntities`; the new false overload removes its snapshot-copy phase, but enumeration, entity retrieval, identity traversal and nested shulker inspection may still be expensive.

Keep this candidate in **DRAFT** pending exact-head 25-workflow acceptance, Codacy, independent review, complete player/entity/ender/double chest/container tests, disposable real-Paper benchmark, integration with #75/#76 and queue loss resolution. No live SMP/Bloom/AI action is authorized.