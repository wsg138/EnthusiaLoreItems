# Worker 10 — cross-chunk double-chest acceptance gate (independent)

Date: 2026-10-12. Scope: issues #83/#95/#103, refreshed integration draft #102 at audited SHA `2e38abe86de4807447b2e7d2dceaf3d694bff2d1`.
Owner: Worker 10, **tests and documentation only**. Nothing in this document authorizes merging, restarting, deploying, writing live-world files, SQLite, SFTP, or running an SMP performance test.

## Current verdict: NOT RUN on real Paper 26.2 / Java 25 — NO RELEASE SIGN-OFF

Independent Worker 10 refreshed the source base to integration PR #102, commit `2e38abe86de4807447b2e7d2dceaf3d694bff2d1`. The first seven failing synthetic `DoubleChestCrossChunkAcceptanceTest` tests used a generic 54-slot `Inventory` and null holder. This is *not* a legal identity for Paper's paired chest, and the fail-closed production resolver correctly rejected it. The corrected test now uses a `DoubleChestInventory`, two separate `Chest` block-state proxies carrying LEFT/RIGHT types with the same facing, two 27-slot physical inventories with distinct chest holders and locations, and a proxy world that checks loaded chunks before block reads. These are *interface-accurate synthetic fixtures only*; they do not stand in for live CraftBukkit/Paper implementation behavior.

The corrected tests assert one coalesced observation for repeated same-tick complete callbacks, precise `slot:0` and `slot:53` observations after a later tick, duplicate copies as distinct reconciliation observations, refusal when a half is unloaded, and refusal of an unidentifiable 54-slot inventory. A successful cached scan is invalidated by a synthetic natural chunk-load callback. The #101 prior unsuccessful-scan cache regression also passes on #102. Event-triggered mutation invalidation during the same tick remains a *separate real runtime oracle*, not inferred from those tests.

At integration source SHA `2e38abe86de4807447b2e7d2dceaf3d694bff2d1`, CI run [38331748002](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38331748002) reported **401 tests, 8 failed, 25 skipped**: seven invalid synthetic chest fixtures, plus the genuine `PaperUniqueAccessTrackingListenerTest.denseNestedLeavesDoNotStarveLaterTrackedCopy` (expected 2 copies, observed 1). The seven fixture failures are addressed on Worker 10's branch only. The nested continuation remains W08's production responsibility; never skip or weaken the red test. Integration also failed the Protection, Tracking, and Revision Rollout hosted acceptance workflows on that exact source. Do not interpret the isolated Worker 10 green subset as whole-repository success.

Existing WP-05 acceptance infrastructure uses a Paper 1.21.11 / Java 21 harness and is not evidence of **Paper 26.2 + Java 25** double-chest behavior. No disposable real Paper 26.2/Java 25 run was performed here. Loaded-JAR hash, world/chunk semantics, SQLite durability, and p95/p99 tick tail evidence remain **NOT RUN**. The precise per-case status matrix is in `docs/performance/2026-10-12-w10-integration-audit.md`.

## Current source-derived hazards and separation of responsibility

1. W06 versioned `double-chest:<worldUUID>:...:left=...` identities and 54-slot order are now covered by stronger synthetic interface fixtures, but **not proven against real Paper chunk unload/reload**; 27-slot hopper/half evidence must never alias the other half's local slot.
2. W07's `PaperPhysicalChunkScanner.scannedThisTick` now remembers a Boolean result instead of upgrading an incomplete visit to success. The independent #101 regression must keep passing; same-tick post-mutation cache invalidation must be checked by actual event lifecycle.
3. W08's 256-work-unit bound can leave real nested copies invisible until a continuation; a truncation is not complete uniqueness evidence. This remains a genuine failing test on the integration SHA.
4. New world-UUID block keys avoid a fresh namespaced-world collision. Historical world-name block rows and rollback/restart migration remain unresolved in issue #103.
5. On unload, no new physical state may be invented, and no unloaded neighbor block may be read. Durable `LAST_CONFIRMED` and acknowledgment/recovery need real SQLite/WAL/restart proof.

The evidence validator performs **schema and file-integrity checks, not attestation**. It now requires non-empty scenario-local evidence and a `provenance/` directory with SHA-256-verified `paper_server_jar`, `built_inner_jar`, `loaded_inner_jar`, and `java_version_log` file records, plus a positive exact `paper_build` number and matching declared built/loaded inner-JAR SHA-256. Those properties do not independently establish that the reported events occurred; reviewers must inspect the actual logs and replay the isolated cases.

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
