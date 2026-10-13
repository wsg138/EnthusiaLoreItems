# Worker 10 — integration #102 independent acceptance audit (2026-10-12)

**Release gate: FAIL / NOT VERIFIED.** This document is an independent tests-only handoff; nothing authorizes merge, deployment, SFTP, live SMP change, or AI restart.

## Exact source and test scope

- Parent integration draft: [#102](https://github.com/wsg138/EnthusiaLoreItems/pull/102), exact starting SHA `2e38abe86de4807447b2e7d2dceaf3d694bff2d1`. New Worker 10 PR derives from this SHA. Main branch remains untouched.
- Source modules: `adapters-paper`, `adapters-sqlite`, `application`, `domain`, `api`, `plugin`; production source is not changed in this audit.
- Owner requirements: [#83](https://github.com/wsg138/EnthusiaLoreItems/issues/83), mandatory [#95](https://github.com/wsg138/EnthusiaLoreItems/issues/95), world-key migration [#103](https://github.com/wsg138/EnthusiaLoreItems/issues/103).
- The seven initial W10 double-chest RED tests modeled a generic 54-slot inventory with a null holder, so they did *not* establish a real double chest. Corrected synthetic tests model `DoubleChestInventory`, 27-slot half inventories, two Chest BlockStates with correct facing/types, loaded chunk guards, 54-slot order, and exact versioned world-UUID key.
- Previously completed scan may be reused during the **same unchanged tick**, but an incomplete visit may not be upgraded. A natural chunk-load lifecycle callback invalidates prior cache entries in the test. Event-specific mutation invalidation must still be tested on the real runtime.

## Test / CI classification

| Evidence | Status | Meaning |
|---|---|---|
| Java 25 local: corrected double chest and same-tick regression classes | PASS | Synthetic API-contract tests only, not Paper runtime; source compile targets Java 21 |
| Local full `:adapters-paper:test :plugin:shadowJar --continue --offline` | FAIL (404 tests; 1 failed; 26 skipped) | JAR task succeeded; only genuine W08 dense-nested two-copy regression remains red |
| Hosted #106 initial full [CI](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38334429324) | FAIL (404 tests; 2 failed; 25 skipped) | Genuine W08 nested-copy failure plus a timing-brittle queue test asserting a precise one-tick dequeue count on a 2ms time-capped scanner. Worker 10 revised the latter test to require bounded eventual routine progress, never silent rejection, and preserved the W08 red test. Hosted verification of that adjustment requires the next exact HEAD run. |
| Python `test_double_chest_acceptance.py` | PASS (18 tests) | Manifest schema, digest and negative-path validation only |
| Integration #102 CI [38331748002](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38331748002) | FAIL | 401 tests, 8 failed, 25 skipped before W10 fixture fix: 7 invalid fixtures; 1 real nested duplicate-observation shortfall |
| Integration #102 [Tracking](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38331748169), [Protection](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38331748058), [Revision](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38331748107) | FAIL | Hosted real-suite regressions; do not conflate with W10 synthetic green |
| Gradle `spotbugsMain --continue --offline` | TASK SUCCESS, analyzer findings present | Existing production warnings reported by SpotBugs; its Gradle configuration uses `ignoreFailures=true`, so this is not a clean-zero-finding claim. Tests-only changes introduce no production classes. |
| Real disposable Paper 26.2 / Java25 | NOT RUN | No server instance, loaded JAR hash or per-case evidence was verified |
| Stable AI-OFF SMP performance and SQLite WAL/restoration | NOT RUN | Requires separate approval; live environment off limits |

## Real Paper acceptance matrix

These values are **real-runtime statuses**. A matching synthetic test never changes NOT RUN to PASS.

| ID | Scenario | Real Paper status | Evidence |
|---|---|---|---|
| DC-01 | Same-chunk paired chest | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-02 | Positive X chunk boundary | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-03 | Positive Z chunk boundary | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-04 | Negative X chunk boundary | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-05 | Negative Z chunk boundary | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-06 | Facing/LEFT-RIGHT permutations | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-07 | Reversed placement order | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-08 | Exact physical slots 0/26/27/53 | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-09 | Natural load A then B | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-10 | Natural load B then A | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-11 | Only half A accessible | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-12 | Only half B accessible | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-13 | Unload while GUI open | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-14 | Natural reopen/reload | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-15 | Next-tick callback during unload | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-16 | Single to double | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-17 | Double to single | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-18 | Break/re-place during hopper pull | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-19 | No implicit chunk/ticket loads | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-20 | Move slot 0 to 53 and back | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-21 | Shift-click | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-22 | Double-click collect | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-23 | Inventory drag | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-24 | Number-key swap | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-25 | Cursor and hotbar | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-26 | Offhand | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-27 | Armor | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-28 | Ender chest | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-29 | Canceled interaction | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-30 | Quit and immediate relog | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-31 | Two players opening different halves | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-32 | Hopper push into both halves | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-33 | Hopper pull from both halves | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-34 | Nested shulker in slot 53 | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-35 | Nested bundle in slot 53 | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-36 | Nested movement back to player | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-37 | Duplicate item IDs in slots 0 and 53 | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-38 | Copy in unloaded/unvisited half | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-39 | 257th root/continuation | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-40 | World-UUID and block identity | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-41 | Death/drop/pickup | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-42 | SQLite WAL crash/restart | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-43 | LAST_CONFIRMED on unload | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-44 | Ordinary hopper fast path | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-45 | No recurring world sweep | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-46 | Tracked-only 1200-tick cadence | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-47 | Main-thread timing p50/p95/p99/max | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |
| DC-48 | Dirty/retry with alternating load/unload | NOT RUN | No disposable Paper 26.2 / Java 25 scenario log |

**Total: 0 PASS / 0 FAIL / 48 NOT RUN.** The separate synthetic suite can pass without changing this count.

## Provenance and fail-closed verdict

Every real scenario must supply event-local logs, assertion results, durable history, chunk load/ticket proof, and timing evidence, each stored under `DC-XX/` with checked SHA-256 digests. Shared provenance must contain exact commit SHA, Paper version **26.2** and positive build number, Java major **25**, world UUID, actual Java version log, Paper server JAR, inner built plugin JAR and inner loaded plugin JAR under `provenance/`, each present and SHA-256 checked. Any missing, tampered, wrong-version, or non-local evidence rejects the gate. Even a schema-valid manifest **does not authenticate the events**; manual/replay review is required.

Do not substitute WP-05's Paper 1.21.11/Java21 evidence, a MockBukkit proxy, idle TPS, or merely equal SHA strings for the real comparison.

## Production ownership handoff

- **W06:** Verify genuine two-half inventory and hopper 27-slot identity/slot translation; no false alias, unknown loaded half -> unresolved.
- **W07:** Prove success-cache invalidation on every meaningful same-tick movement and no durable false-present after natural unload.
- **W08:** Repair `denseNestedLeavesDoNotStarveLaterTrackedCopy` without increasing the 256-work bound; real continuation must recover second copy.
- **W09:** Durable ack and crash/restart/WAL replay/rollback, including issue #103 migration/backcompat.
- **Coordinator:** Classify Tracking/Protection/Revision hosted failures from their actual stdout/logs, repeat exact-head CI, then run isolated real Paper acceptance and a matched tick-tail stress experiment before asking for any release approval.
