# Worker 10 — cross-chunk double-chest acceptance gate (independent)

Date: 2026-10-12. Scope: issue #83, integration branch `integration/event-first-all-five-workers-20261012` at audited SHA `b09d1f5b6cd5d08fc857ec9064f0eb043698f832`.
Owner: Worker 10, **tests and documentation only**. Nothing in this document authorizes merging, restarting, deploying, writing live-world files, SQLite, SFTP, or running an SMP performance test.

## Current verdict: UNVERIFIED / NO RELEASE SIGN-OFF

The `DoubleChestCrossChunkAcceptanceTest` tests added alongside this document are **MockBukkit/synthetic boundary probes only**, not real Paper block-state integration. The test injects a shared 54-slot `Inventory`; it cannot establish that Paper actually returns the same holder, location, slot order, or durable inventory identity when only one half's chunk is loaded. Passing Java tests must not be reported as real double-chest acceptance.

The existing WP-05 tracking contract workflow in this baseline pins **Paper 1.21.11 build 116 and Java 21**, not the requested disposable **Paper 26.2 + Java 25** environment. Do not silently substitute those results. The artifact/provenance gate remains blocked until the exact test server binary and Java runtime are recorded.

## Source-derived risks to attack

1. `PaperPhysicalChunkScanner.scanChunkContainers` scans `chunk.getTileEntities(false)`, reads a holder inventory, then deduplicates only **within one chunk** by snapshot type/key. Independent scans of chunk A and B need real proof that both report one physical canonical 54-slot container, not two keys or a 27-slot alias.
2. `PaperPhysicalInventorySnapshot.capture` obtains `inventory.getHolder(false)` and `inventory.getLocation()`. `PaperInventoryReference.capture` stores **one** coordinate (the returned inventory location). Its `Block.resolve` checks only that coordinate's chunk loaded before calling `getBlockAt(...).getState(false)` and `holder.getInventory()`. This is not proof that resolving the 54-slot double chest is safe when the opposite half is unloaded. Measure natural chunk load counter before/after this resolve and assert no neighbor load.
3. `PaperInventoryReference.blockKey` presently uses **world.getKey()** (namespaced world key), while `PaperInventoryReference.Block` retains **world.getUID()** for resolution. World UUID identity is not encoded in the persistent block location key. Test the world-identity/restore policy explicitly rather than assuming the name/key suffices.
4. `PaperPhysicalChunkScanner` has a 256-work-unit bound **per chunk**. A refused 257th container returns incomplete; previously found copies remain reconciliation only. Do not turn partial inventory coverage into global UNIQUE. A 54-slot inventory is not by itself 54 work units, so a passing 54-slot test is not the truncation test.
5. WP-05 code records `slot:0` and formerly also `storage:0` for some apparent player positions. Decide whether these are one physical slot-path alias or separate real movements by comparing paired authoritative event snapshots and durable history. Never erase actual cursor, offhand, armor, or ender history to make a script pass.

These are source-review hypotheses/contracts, **not independently demonstrated runtime failures**.

## Real disposable Paper fixture and assertions

Build on exact source SHA and use a **new disposable Paper 26.2 / Java 25 world** with an isolated SQLite database. Assert actual `java -version`, full server build/version, plugin source commit, SHA-256 of inner built plugin JAR and loaded test JAR, harness JAR SHA-256, `wp05accept` access state, world UUID, item definition + instance UUID, no plugin chunk tickets, starting naturally loaded chunks, and the complete acceptance log. The test-world location must be isolated from spawn tickets before unloading. Never use `loadChunk`, `getChunkAt` on a previously unloaded neighbor, `setChunkForceLoaded`, or other artificial ticket methods to create a passing observation.

For each orientation, **physically place two matching CHEST blocks**, check the actual `Chest` block data (`LEFT`/`RIGHT`, facing and type) and the top `DoubleChestInventory` is 54 slots. Do not counterfeit the holder using two synthetic 27-slot inventories. Record `getBlockInventory()` (27 each) separately from `DoubleChestInventory` (54 total). Read the player-visible inventory ordering to derive which half owns each 0–53 slot; never assume WEST or NORTH is always global slot 0.

Fixture pairs at Y=70 (coordinates local to an otherwise clean disposable world):
- X+ boundary: (15,70,15) and (16,70,15)
- Z+ boundary: (15,70,15) and (15,70,16)
- X− boundary: (-1,70,-1) and (0,70,-1)
- Z− boundary: (-1,70,-1) and (-1,70,0)
- Same-chunk controls: (5,70,5) and (6,70,5), also reversed facing.

For each pair, cycle every legal compatible facing and LEFT/RIGHT orientation, including reversed placement order, **A then B load**, **B then A load**, **only A naturally loaded**, **only B naturally loaded**, and **unload B with the GUI open** (if Paper refuses to unload a viewed inventory, log that and mark that specific variant BLOCKED rather than force an unload). Each observation must be independently corroborated by the actual 54-slot top inventory, canonical physical block coordinates + **world UUID**, root path `slot:N`, durable `instance_observations`, and `instance_current_state`. After a half unload, `UNRESOLVED` or `LAST_CONFIRMED` is permitted; fabricated `CONFIRMED_NOW` from a stale location is not.

### Mandatory scenarios (all must have separate outcomes)

| Family | Scenario IDs / required coverage |
|---|---|
| Geometry | DC-01 same chunk; DC-02 +X; DC-03 +Z; DC-04 -X; DC-05 -Z; DC-06 all legal facing/L/R permutations; DC-07 reversed physical pair placement; DC-08 full 54-slot left/right slot map |
| Chunk lifecycle | DC-09 load A→B; DC-10 load B→A; DC-11 only A; DC-12 only B; DC-13 unload neighbor while inventory is open; DC-14 reopen/reload; DC-15 next-tick callback queued at unload; DC-16 single→double; DC-17 double→single; DC-18 remove/re-place while hopper pulls; DC-19 no implicit chunk/ticket loads |
| Player movement | DC-20 slot 0→53→0; DC-21 shift-click; DC-22 double-click collect; DC-23 drag; DC-24 number-key swap; DC-25 cursor/hotbar; DC-26 offhand; DC-27 armor; DC-28 ender chest; DC-29 canceled interactions; DC-30 disconnect/quit and immediate relog; DC-31 two simultaneous players opening opposite halves |
| Hoppers/nested | DC-32 hopper push both halves; DC-33 hopper pull both halves; DC-34 shulker in slot 53; DC-35 bundle in slot 53; DC-36 nested identity moved to player and back |
| Security/correctness | DC-37 duplicate ID slot 0+53; DC-38 duplicate while second half unvisited; DC-39 257th+ scanned root; DC-40 identity world UUID and physical block type; DC-41 death/drop/pickup; DC-42 source→DB WAL crash/restart recovery; DC-43 last-confirmed exact slot after unload |
| Performance | DC-44 ordinary hopper fast path; DC-45 zero recurring full all-chunk sweep; DC-46 ≥1,200 ticks tracked-only cadence; DC-47 p50/p95/p99/max callback and scan elapsed, memory and queue age; DC-48 dirty/retry bounded under alternating natural loads/unloads |

A PASS for DC-01 through DC-08 does not waive any DC-09 through DC-48. **UNVERIFIED**, **BLOCKED**, and **FAIL** are not PASS.

### Required concrete oracles per movement

1. Confirm the unique item instance ID and its exact actual destination via the real Paper inventory **after** the event settles (tick boundary, inventory close/reopen where appropriate); compare the original source and the target. Verify no ghost copy or removal from the wrong half.
2. Query the isolated test DB's `instance_observations` ordered by `observation_id`; retain all movement history, including `slot:N`, `cursor`, `offhand`, `armor:3`, and ender chest paths. Cross-check `instance_current_state` against authoritative reality. Log both the history and current-state row with the same item ID.
3. For collisions, partial scans, unvisited halves, or unloaded neighbors, forbid authoritative UNIQUE and forbid a made-up PRESENT location. Accept only explicit reconciliation, unresolved state, or last-confirmed provenance until complete evidence can be recovered.
4. Observe world/chunk state **before and after** the tested action, with natural load/unload event log, chunk tickets, and optionally Spark/JFR counters; fail on unexpected load, forced ticket, or snapshot reads of unloaded chunks. A lack of a chunk event in a short window is not itself proof.
5. Record event admission/dirty/retry/recovery, nested paths, actual transfer action (not only test-only `setItem`), elapsed per tick and per event, and compare against a matched baseline. Measure p95/p99 and worst tick with a timed workload; never infer these from idle TPS or a synthetic fixture.
6. Record the real `world.getUID()` and the persistent world key. A copied/recreated world reusing the same namespaced key but changing UUID must not be conflated with its old block history without an explicit migration policy.

### Exact-run evidence template

Store `DC-XX/{manifest.json,actual-paper.log,assertions.json,observation-history.json,current-state.json,chunk-lifecycle.log,performance.json}` in the disposable CI artifact. Every scenario record must include `status`, `runner=ACTUAL_DISPOSABLE_PAPER`, expected vs observed canonical `world_uuid:blockX:blockY:blockZ:slot`, proof of 54-slot physical chest (or explicit scenario exception), first and second half block data, chunk state/tickets before/after, zero forced loads, zero false unique, event action, DB identity rows, exact source SHA, Java and Paper versions, artifact hashes, timestamps and failure reason. Never stamp a case PASS based only on `assertTrue` against a manually constructed mock.

Run the existing synthetic subset independently:
```sh
java -version
gradle --no-daemon :adapters-paper:test --tests net.enthusia.loreitems.paper.DoubleChestCrossChunkAcceptanceTest
```
This is **not** the Paper integration gate. On the expected Paper 26.2/Java 25 test runner, also build `:plugin:shadowJar` and `:acceptance-harness:jar` at the exact PR head before installing both in a fresh server. Do not reuse WP-05's current Paper 1.21.11/Java 21 fixture as a surrogate.

## WP-05 regression follow-up

Coordinator fixed the historical negative-history assertion in `acceptance-harness/scripts/wp05-tracking-acceptance.sh` at `b09d1f5`: explicit cursor, offhand and armor history is now **required**. The earlier missing `go-reconnect` may be a symptom of an aborting assertion, not evidence that the bot itself was defective. Re-run the entire real acceptance sequence from `go-reconnect` through later ACC-TRACK phases; collect the first failing stdout line and server log. Specifically require that `storage:0` vs `slot:0` either maps to distinct physical locations or is normalized consistently for uniqueness without deleting history.

## Stop conditions and handoff

Do not merge or deploy until independent real Paper evidence establishes all 48 scenarios (including failures exercised), clean build/unit tests/analyzers, hosted Codacy **zero new valid findings**, static review/complexity and p95/p99 comparison, WAL restore/rollback, and source→built JAR→loaded JAR hash equality. If hosted CI cannot obtain verified Paper 26.2/Java 25, leave gate **UNVERIFIED**, attach reproducible runner instructions, and hand off to coordinator and Workers 06–09.

Production ownership remains with Workers 06–09. Worker 10 changes tests and audit documentation only.
