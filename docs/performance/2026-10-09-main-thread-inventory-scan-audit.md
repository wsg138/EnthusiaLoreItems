# LoreItems main-thread inventory scan audit — 2026-10-09

**Status:** source-level investigation, no production changes; optimization candidates NOT implemented or benchmarked.  
**Reviewed main:** `4fcc8711dff6bf634594a7e521d810d919c36270`  
**Scope:** `wsg138/EnthusiaLoreItems`. Related read-only evidence in `wsg138/Enthusia-AI`.  
**Production constraint:** no SMP restarts, JAR uploads, server settings, load tests, or live SFTP writes.

## Evidence and limitations

- AI/workload OFF: https://spark.lucko.me/vKZdMAkBX9 (180.323 s, 13–14 players).
- AI/workload ON: https://spark.lucko.me/92tX2g1hoK (240.347 s, 13–14 players).
- Previously decoded profile analysis: [Spark main-thread review](https://github.com/wsg138/Enthusia-AI/blob/fix/discord-command-safe-upsert-20261008/deploy/bloom/SPARK-SMP-STACK-ROOT-CAUSE-REVIEW-20261010.md). This audit reviewed that analysis and code, **not** the raw protobuf itself. Raw Spark pages were not independently retrievable in this session.
- In matched one-minute windows, the prior review reported template scanning 2.33 s OFF versus 9.64 s ON (10.20 s in the next slow minute), and deferred LoreItems tracking 2.99 s OFF versus 5.70 s ON. Main-thread `tickServer` was 21.21 s OFF versus 52.91 s in the slow minute. These are **overlapping, inclusive sampled execution/wall times**, not exclusive CPU consumption; they must not be added or treated as per-tick timings. The reported 1,253 ms and 1,101 ms worst ticks have no corresponding per-tick call-stack attribution.
- **Newer historical deployment evidence (October 9):** the [read-only SMP snapshot inventory](https://github.com/wsg138/Enthusia-Server/blob/5a00397b4ba622fd35cc372c901521b07592de24/reports/network/SMP/plugin-jars.csv), committed 2026-10-09 19:19 UTC (snapshot timestamp 19:18:57 UTC), lists `EnthusiaLoreItems-1.0.5.jar`, file timestamp **2026-10-09 18:24:40 UTC**, SHA-256 `5c7fceebc723e5d9293e45fc689528effc682e9a1433afb24c93ff7b22a17580`. The [October 5 inventory](https://github.com/wsg138/Enthusia-AI/blob/main/docs/audits/live-plugin-intelligence/SMP-PLUGIN-INVENTORY.md) instead listed `1.0.2-tps-test.2`, and [LoreItems #39](https://github.com/wsg138/EnthusiaLoreItems/issues/39) records 45 scan-queue-full warnings from that earlier period. **Neither the 1.0.5 JAR's source commit nor its loaded status and exact live configuration during the Spark profile have been verified.** Current repository `gradle.properties` still declares `releaseVersion=1.0.1` and the release list ends at v1.0.1. Do not assume current `main` code equals deployed `1.0.5`.
- Related AI issue [#119](https://github.com/wsg138/Enthusia-AI/issues/119) concerns packaging an AI stack on Bloom, **not** LoreItems performance. Use LoreItems #39 for the directly relevant queue issue.

## A. Confirmed source behavior and priority

### A1. Template scan repeatedly re-resolves live inventories (HIGH; Spark-supported path)

`PaperTemplateUpdateListener.start` schedules `PaperTemplateUpdateAccessController.drain` **every tick**. The controller's per-tick `budget` counts queue steps, not elapsed time. Its `scan` first resolves the inventory with `PaperInventoryReference.resolve(plugin)`, then passes that resolved `Inventory` to `PaperTemplateUpdateScanner.scan`.

Despite already having the live inventory, `PaperTemplateUpdateScanner.processPass` calls `node.reference().resolve(plugin)` for **every visited node**, up to `MAX_ITEMS_PER_PASS = 256`. `PaperTemplateUpdateItemReference.resolve` calls `inventoryReference.resolve(plugin)` again, then reads the root slot, walks each nested path and clones both root and target. For block references, `PaperInventoryReference.Block.resolve` checks loadedness then calls `world.getBlockAt(...).getState()` to obtain the block-inventory holder. For nested shulkers, `readAt/shulkerChild` calls `BlockStateMeta.getBlockState()` for every level of the path. `PaperTemplateUpdateScanner.enqueueShulker` also decodes the shulker to enumerate its children.

**Consequence:** a block container holding shulkers can pay repeated block-state snapshot construction and nested path traversal for every nested item. This explains an unusually expensive inclusive path seen in the supplied Spark analysis. It does **not** prove the absolute slowdown, relative contribution of unrelated world activity, or the worst individual tick.

**Safe-first candidate:** in discovery only, use the already-resolved `Inventory` within one synchronous `scan(...)` invocation instead of re-resolving its `PaperInventoryReference` for each node. Re-read current slots as before, re-resolve the inventory on each continuation pass, and retain the existing revalidation/fingerprint/claim/rollback behavior for mutations. Do not cache live `Inventory`/`BlockState` across ticks. Before release, assert no extra `getState()` calls per child and identical candidates/conflict handling.

### A2. Nested identity precheck can inspect the same shulker twice (HIGH; Spark-supported path)

`PaperPhysicalTrackingListener.scanPlayer` -> `PaperPhysicalInventoryScanner.scanPlayerUnique` -> `PaperTrackedItemCollector.collectItem` invokes `hasNestedIdentityEvidence` recursively, including `BlockStateMeta.getBlockState()`. For a positive result, `collectNested/collectShulker` decodes the *same* shulker again and traverses its contents. Recursion can repeat evidence discovery for descendants. `PaperPhysicalInventoryScanner.scanItem` similarly prechecks nested evidence and then reads the nested meta again for traversal.

**Candidate:** a depth-bounded one-pass tree walk that detects and collects tracked identities at the same time. Preserve exact observation multiplicity, canonical paths, the `MAX_NESTING_DEPTH=8` cutoff, incomplete-budget downgrade to `RECONCILIATION`, and duplicate fencing. Benchmark negative/empty and dense/positive shulkers before deciding: avoiding the precheck might increase identity decoding of negative leaves, so speedup is **not yet measured**.

### A3. Event duplication and deferred actions (HIGH/MEDIUM; code-confirmed, rate unmeasured)

`PaperPhysicalTrackingListener.onSlotChange`, respawn/drop/pickup handlers and `PaperUniqueAccessTrackingListener` click/drag/close actions can independently scan the same player's inventory. `PaperDeferredMainThreadActions.schedule` creates one Bukkit next-tick task **per accepted action**; it has no coalescing or per-tick execution-time limit. It must drain accepted work on close for durability, so dropping pending actions without a correct replacement is forbidden.

`PaperTemplateUpdateEvents` click/drag/move/slot events invalidate or reset scan progress and enqueue references. `PaperTemplateUpdateScanBacklog` de-duplicates *queued* references, but repeated `enqueue()` invalidates existing scanner cursors even if an identical reference is already queued. This can discard partial progress during bursts. A change must keep mutation-relevant freshness and complete duplicate coverage.

**Candidates:** coalesce semantically equivalent next-tick player scans by player UUID, preserve the strongest applicable evidence mode and shutdown-drain guarantees; use per-inventory change generations to avoid unnecessary cursor resets; instrument coalesced counts and deferred queue depths before changing broader event semantics.

### A4. Hopper/move matching can scan the destination once per identity (MEDIUM/HIGH; code-confirmed)

`PaperPhysicalTrackingListener.onInventoryMove` extracts identities from the moved item, schedules `submitMatchingIdentities(destination, identities)`, then scans the destination again. For **each identity**, `PaperPhysicalInventoryScanner.submitMatchingIdentity` calls `matchingLocations`, which recursively collects the whole destination inventory tree. A moved shulker containing N tracked identities can cause approximately N full destination scans plus a final scan; the same pattern occurs in `PaperBlockInventoryTracking`.

**Candidate:** collect destination identity-to-locations once for an event, then derive per-identity authoritative-vs-reconciliation evidence from that single, complete result. Preserve duplicate counts and bounded-scan downgrade. If insufficient budget, fail closed to reconciliation rather than claiming uniqueness.

### A5. Tracking queue saturation/backpressure (HIGH reliability priority; production evidence Oct 5)

`PaperPhysicalTrackingListener` drains at most `currentBudget()` queued requests **each tick**, and seeds up to that budget of loaded-chunk requests every **100 ticks**. Default `mutation-budget-per-tick=16` -> physical scan queue cap `16*32=512` requests. The `ArrayDeque<PaperTrackingScanRequest>` has **no key-based duplicate suppression or request-source priorities**. A full queue rejects new requests and increments `tracking.rejected`, preserving prior durable evidence but reducing tracking freshness. Player join/start scans use this queue; most next-tick player mutation scans bypass it, which is an important distinction. A FIFO full of periodic chunks can delay/reject join or chunk-load coverage.

`PaperTrackingCoordinator` has a **separate** in-flight durable-observation budget and queued cap `8 * maxInFlight`, plus a 250 ms bounded debounce only for observation sources ending `-unique`. That debounce is **after** inventory traversal; it does not prevent the expensive scan itself. The template scanner has its own distinct queue and retry backlog.

**Candidate:** record queued/executed/rejected by source, queue high water, last/maximum duration per request, and deferred-actions depth; then add bounded keyed chunk coalescing, stale-request removal, fairness, and preferential admission of important lifecycle work without losing authoritative evidence. Keep separate tests for coordinator overload and scan-queue overload; never merely raise the multiplier.

### A6. Budgets are logical work counts, not hard tick-time caps (HIGH risk, needs design)

- Template scanner: 256 visited references per pass; max depth 8, max 64 continuation passes, max 8,192 pending references. Abandoned/incomplete attempts are marked incomplete and retried; they are **not** silently accepted as complete.
- Template inventory controller: `budget` queue steps per tick. Template queue's per-tier capacity is clamped to [512, 4096] and total capacity is twice that; separate retry backlog. The entity-template walker also has a distinct budget.
- Physical listener: up to `budget` queued scan requests per tick; each chunk request can inspect entity/tile-entity structures and item trees. `PaperScanLimit(256)` counts eligible roots/expansions in different call paths, **not every nested leaf or all API calls**, and synchronous event-handler scans bypass queue timing caps.
- `World.getLoadedChunks()` and `Chunk.getTileEntities()` snapshot arrays can themselves scale with loaded chunks/tile entities; no forced loading was found in the reviewed paths.
- At 14 players, player scans increase with inventory events and online player coverage; chunk work increases with loaded chunks, tile entities, item entities and inventory holders; nested item work grows with actual nested container contents/depth. There is no supported quantitative TPS/player projection.

**Candidate:** separately track scan duration and scanned roots/nested nodes per tick; introduce monotonic-time budgets only with continuation/retry guarantees, generation fences, duplicate coverage, and no starvation. Never assume `16` means 16 milliseconds.

## B/C. Ranked optimization plan (unmeasured performance impact)

| Rank | Proposed work | Expected opportunity | Difficulty | Tracking correctness risk | Required verification |
|---|---|---|---|---|---|
| P0 | Reuse resolved inventory within each template discovery pass; mutation path unchanged | **High for block inventory workloads**: removes repeated `getState` at every visited child | Low/medium | Low if each pass resolves fresh and reads live slots | MockBukkit exact candidates, changed inventory between continuations, lost chunk, duplicate instances, measured snapshot call counts |
| P1 | Single-pass nested evidence collection | **High for shulker-heavy player scans**, but negative case uncertain | Medium/high | High: duplicate/fingerprint false negatives unacceptable | Empty/full/nested shulkers, bundles, identical IDs in siblings, depth/limit, mixed valid/invalid evidence; microbenchmarks |
| P1 | Prioritize and coalesce keyed periodic/lifecycle scan requests with metrics | **High reliability and likely queue reduction** | Medium | Medium/high: lifecycle/rollout freshness and shutdown | Queue saturation with periodic floods, join/quit, duplicate chunks, unload, retry, fairness, persistence barriers |
| P1 | Coalesce semantically equivalent next-tick player scans | **High for inventory-event bursts**, rate unmeasured | Medium | Medium/high: missed intermediate moves, evidence modes, close drain | Rapid clicks/drags, death/drop/pickup, closed listener, event source semantics, conflicting identities |
| P1 | One destination inventory traversal per multi-ID movement event | **High when moved bundles/shulkers contain many identities** | Medium | Medium | Same/different IDs, moved-item identity set, destination duplicates, truncated scans, changed destination |
| P2 | Avoid resetting template continuation for redundant invalidations with generations | Potentially high under event bursts | High | High | Moves during scan, duplicate added/removed mid-pass, delayed retries, regression on safe mutation claims |
| P2 | Elapsed-time budgets and fair continuation for all scan types | Better worst-tick predictability; gain uncertain | High | High | Deadline rollover, progress, no starvation, incomplete coverage fences, rollback |

**No performance percentages are claimed.** The P0 candidate most directly addresses the Spark-visible `CraftBlock.getState` chain while keeping the mutation path unchanged.

## D. Safety and test matrix before proposing code for merge

1. **Success:** equivalent ID, path, location, duplicate counts and template candidates for normal inventories, Ender Chests, double chests, shulkers, bundles and entities.
2. **Conflict:** two copies of the same instance across nested/outer or separate inventories never become a unique, mutable candidate.
3. **Rejection/overload:** saturated scan/coordinator queues preserve durable evidence, expose accurate rejection metrics and never fabricate authoritative uniqueness from truncated scans.
4. **Retry/continuation:** changed inventories between tick passes, unavailable chunks, invalidated references, failed scans and exhausted budgets recover without infinite stale observation.
5. **Rollback/data integrity:** mutation apply, fingerprint revalidation, persisted completion/review, failed remove and restore behavior remain untouched.
6. **Authorization:** no changes to staff controls, permissions or API response allowlists. No production SFTP access or configuration mutation.
7. **Measurement:** use deterministic fixtures (empty, dense untracked, 1 tracked, dense tracked, nested to depth 8; multiple identities) plus invocation counts and local microbenchmarks; evaluate both fewer snapshots and absolute wall-time cost.

Existing tests reviewed: `PaperTemplateUpdateScannerTest`, `PaperTemplateUpdateAccessControllerTest`, `PaperPhysicalInventoryScannerNestedMoveTest`, `PaperPhysicalTrackingListenerSlotChangeTest`, `PaperPhysicalTrackingBlockLifecycleTest`, `PaperTrackingCoordinatorSaturationTest`. These establish important correctness behavior but do **not** by themselves prove any new optimization safe or faster. No test/build/analyzer was executed during this read-only source review.

## E. Independent bottlenecks / alternative explanations

The same decoded Spark comparison reports `EntityTickList.forEach` rising 7.98 s -> 14.58 s, `Villager.tick` (a subset) 3.62 s -> 5.44 s, and `ServerChunkCache.tickChunks` 3.23 s -> 9.80 s; all inclusive sampled times. Villager AI and chunk natural spawning/random ticking deserve separate inspection. The simultaneous slowdowns and recovery when the other workload ended are **consistent** with physical-core/scheduler/cache/memory contention despite low host-wide CPU percent, but these profiles cannot measure CPU affinity, run-queue delays, cache-miss pressure or causation. Do not attribute the 1.253/1.101-second max ticks to any one method.

## Implementation state and boundaries

- Read: current `main` source, relevant tests, past PRs, source architecture/budgets, prior decoded Spark report, existing saturation issue and October 5 read-only deployed-JAR inventory.
- The October 9 sanitized SMP snapshot establishes a **JAR filename and SHA-256** but not a source-build mapping, active process proof at the exact Spark window, or deployed config.
- No product code or configuration altered. No new tests run; no benchmark executed. The repo's active deployment is untouched.
- Next implementation gate: isolated branch + tests for **P0 inventory reuse**, run clean Java 21 Gradle build/test and static analysis, review exact-head CI and hosted Codacy; keep PR draft. Do not merge or deploy without owner permission.
