# Deterministic scan-queue burst baseline (October 12, 2026)

## Why this test exists

The **AI-off** SMP experienced severe intermittent lag, while LoreItems' staff GUI after restart showed a physical-scan queue historical peak of **512**, **871 non-periodic event rejections** and **32 periodic rejections**. Current tracking admission has bounded capacity `budget * 32`, displaces optional periodic reconciliation to accept new lifecycle requests, and **rejects** a new lifecycle request when the entire bounded queue consists of earlier lifecycle requests. The old counters did not identify rejected event sources; [PR #73](https://github.com/wsg138/EnthusiaLoreItems/pull/73) adds player-versus-chunk categorization and hopper timing, but **does not change the queue**.

The production 871 event rejections have not been attributed conclusively to player joins or chunk loads. A 512-wide queue peak shows saturation, not why it occurred; inventory hopper transfers are **not** enqueued in `PaperTrackingScanRequest` and their immediate and next-tick scan work is separate.

## Reproduction

`PaperPhysicalScanBurstBaselineTest` uses disposable MockBukkit (no live server access) with per-tick request budget 4 and queue capacity 128:

1. Submit **136 distinct nonperiodic chunk-load** scan requests. Baseline expectation: queue has 128 accepted requests and records **8 rejected nonperiodic chunk** requests; no retry is currently scheduled for the eight rejected requests.
2. Drain for 32 ticks at the budget of four scans/tick. All 128 accepted queued requests are removed and their successful callback attempts are recorded; the eight rejected source events remain rejected, not silently counted as queued completions.
3. Submit 126 distinct chunk-load and 2 player-join requests, then one additional of each. Baseline expectation: independently identify **one player and one chunk** rejection under the same full all-lifecycle backlog.
4. Fill 127 slots with player lifecycle requests and the final slot with optional routine reconciliation; show that a new chunk-load lifecycle request evicts **only** the optional periodic scan. The lifecycle queue remains full without dropping an already accepted event.

These are *characterization* tests for the present behavior. They deliberately do **not** make the existing admission policy appear to preserve all newly rejected evidence. A future reliability fix must update these assertions and demonstrate a new correctness contract rather than changing the queue size until tests happen to pass.

## Required design before a queue-behavior PR

A bounded queue cannot retain unlimited arbitrary new distinct load events indefinitely. A safe recovery strategy needs (a) explicit bounded overload representation, (b) forward progress without main-thread overload, (c) source-specific accounting for dropped/deferred/recovered observations, (d) same-world chunk identity isolation and an unload/reload *generation* boundary, (e) no force-loading of chunks or retaining Bukkit entities/holders asynchronously, and (f) lifecycle and duplicate-identity semantics protecting LAST_CONFIRMED when a chunk unloads before a deferred PRESENT scan. Existing routine loaded-chunk sweeps may eventually observe still-loaded inventories but don't by themselves guarantee that a **rejected chunk-load transition** was observed; don't falsely count periodic retry as authoritative historical evidence. Player join retry must likewise handle disconnect/reconnect and preserve tracking source semantics.

Keep a strict per-tick elapsed-work budget when evaluating recovery; simply draining the entire backlog synchronously during saturation would make MSPT worse. All new behavior must survive mixed event/periodic floods, shutdown and SQLite WAL-safe restart with no tracked-item loss or invented identity evidence. Prefer measuring new GUI player/chunk rejection deltas and the hopper/scan peak timings during natural load before deciding exact priorities.

## Operational safety

This PR only adds deterministic tests and documentation. It does not modify any production Java, queue capacity, handler, identity, event scheduling, persistence or configuration. No live SMP changes, AI load tests, database access or restart. Related [master task #71](https://github.com/wsg138/EnthusiaLoreItems/issues/71), [production incident #39](https://github.com/wsg138/EnthusiaLoreItems/issues/39), [#73 instrumentation](https://github.com/wsg138/EnthusiaLoreItems/pull/73) and [#74 chunk inventory optimization](https://github.com/wsg138/EnthusiaLoreItems/pull/74).
