# Chunk-admission development checkpoint — 2026-10-16

**Status: draft, NOT RELEASE APPROVED.** This is a review of the generation-aware natural-load branch [#93](https://github.com/wsg138/EnthusiaLoreItems/pull/93). No LoreItems JAR is installed on SMP during the owner's multiday absence, and the existing SQLite database and item PDC evidence are to remain untouched.

## Progress measured on exact source commits

- Draft #93 introduced natural-load generation tokens, dirty re-admission, loaded-only retry, delayed known-tracked verification and a two-millisecond **post-operation** drain stopwatch. Initial local Gradle succeeded but its hosted static-analysis gate needed review. The budget cannot interrupt an individual slow scan.
- [#160](https://github.com/wsg138/EnthusiaLoreItems/pull/160) exact reviewed head `cbccb2c2429e7b61ad4ca9845186d473e165353d`, CI + test JAR passed, merged only to this draft branch as `4615a4a67b6342b0786ec544b75866c0f75a2a18`. Retains at most one dirty-retry token per loaded chunk and purges old generations on unload. Regression: 5,000 dirty load/unload cycles, 1,000 duplicate marks, throttled fairness.
- [#161](https://github.com/wsg138/EnthusiaLoreItems/pull/161) exact reviewed head `23d99b8af5c986f55d24ccbd932c1cedd6a0f9c1`, CI + test JAR passed, merged only to this draft branch as `ab1caf5804f9f4adcfcbb729dfe774efb3c51ee3`. Inaccessible-generation completion or replacement invalidates stale tracked verification queue keys. Regression: 3,000 inaccessible-generation renewals, duplicate observations and independent worlds at identical chunk coordinates.
- A fresh full 25-workflow exact-head pass on the **resulting parent branch** is required. Green evidence from both children cannot by itself prove the combined parent. This document commit is intentionally used to trigger that full-matrix run.

## Remaining release blockers and proofs required

1. **Scan continuation:** current physical scan budget can stop before all tiles, nested shulkers/bundles, or large inventories are examined. A failed scan remains dirty, but the next scan begins from the same root. Implement a bounded, generation-fenced cursor/continuation that *actually progresses* over 256+ work units and records incomplete evidence as nonauthoritative. Validate changes between ticks and no loss of exact 0–53 double chest slots.
2. **Truthful durable outcomes:** completed Bukkit callback != committed authoritative SQLite proof; rejected admission, skipped inaccessible, failed callback, partial/truncated and durable ACK are distinct states. Test shutdown, restart, transaction rejection, WAL replay and exactly-once evidence attribution without false `CONFIRMED_NOW`.
3. **Chunk lifecycle:** scan before unload remains synchronous and unbounded per individual callback, and startup `getLoadedChunks()` has a potentially large snapshot cost. Develop an indexed/continuation-safe alternative before live deployment. Never force-load a chunk or retain strong Bukkit references across ticks.
4. **Fairness/bounds:** test 1,000 same-chunk loads and 1,200 distinct loaded chunks with 512 admitted / 688 deferred, alternating unload/reload generations, multiworld collisions, player join floods and optional periodic work. Bound memory to naturally loaded keys, online players and known-tracked scopes, including genuine overflow of each auxiliary ledger; measure p95/max queue age.
5. **Offline gap:** physical lore instances carry PDC identities, but SMP will have been without LoreItems for several days. Old SQLite current locations may be stale. Do not rewrite historical movement claims or automatically label an old location live on restart. Read-only preflight + WAL-safe backup and fresh item evidence required.
6. **Double-chest and migration:** #95 full physical double-chest 48-case / 54-slot X/Z negative and positive border+orientation matrix, and #103 old world-name block-key to UUID-qualified location semantics remain unproven. No live writes, aliases or automatic migration based on world name alone.
7. **Performance:** matched disposable Paper 26.2 / Java 25 AI-OFF stress benchmark with many loaded chunks, hoppers, items and players must show bounded tick time and complete important evidence catch-up. Existing isolated #79/#82 wins and these data-structure tests are not TPS proof.

## Boundaries

This branch is separate from [#158](https://github.com/wsg138/EnthusiaLoreItems/pull/158), the 25/25-green lower-cost inventory/hopper/diagnostic candidate. **Do not silently combine, merge to main, install to TEST/SMP, restart, alter configs, touch SQLite or mutate player items** before independent review of combined physical tracking and explicit owner-approved recovery/deployment.
