# Failed queued scan containment — October 12, 2026

## Incident context

The production LoreItems scan queue previously reached depth 512, with 871 non-periodic and 32 routine scan requests rejected. Those *admission* rejections are not the same as an exception during execution. The current `PaperPhysicalScanTelemetry.execute` previously incremented `tracking.scan_event_completed`/`tracking.scan_periodic_completed` in a `finally` clause, even if `request.run(...)` threw. After one such exception, `PaperPhysicalTrackingListener.drain()` stopped processing other queued requests for that tick and the failed task had already been dequeued. This is a verifiable code-correctness risk, **not proof a specific recent production lag spike was caused by an exception**.

## Fix and safety

- Catch **RuntimeException** around one queued player or chunk scan, record the appropriate fixed-category `tracking.scan_event_failed` or `tracking.scan_periodic_failed`, and continue with the next request under the **existing per-tick count budget**.
- Only a callback that runs against an accessible player or loaded chunk increments its completed counter. Offline-player and unloaded-chunk returns count as **skipped**; thrown requests count as **failed**. Completion means callback execution, not proof that tracked evidence was found or durably persisted. Existing per-request scan duration is still captured in `finally` on success or failure.
- Main-thread warnings include the fixed scan source, note incomplete current reconciliation, and include the exception stack trace **only on the first failure and each 100th failure**, preventing unbounded routine console logging under a poison-source flood.
- The operator GUI separately shows completed, inaccessible/skipped and failed event/periodic scans.
- Do not catch `Error`, drop other queued lifecycle evidence, force-load worlds/players, retry poison tasks, or modify SQLite/tracked item state. The failing request is not retried by this change; **prior durable evidence persists**, and future natural-access or periodic scans may reconcile if the item is accessible. This is **not an absolute no-loss guarantee**.

## Verification plan

MockBukkit regression enqueues a deliberately malformed chunk request followed by a valid player request. One `drain` must complete the player work, record exactly one scan failure and one genuine online-player completion, report zero skipped scans and persistence failures and leave the queue empty. The next drain must not replay the poison request. Another focused `TrackingMetricsTest` asserts event/periodic scan failures accumulate across metrics views without falsely incrementing completed or persistence-failed counters.

Before release, require exact-head clean CI, 25 WP-05 acceptance workflows, static analysis, Paper runtime fixture and independent review. The change does not repair historical 871/32 **queue admission rejections**; those require source attribution via the GUI from merged PR #73 before safe admission-policy changes. No live SMP action taken.

Related: [#71](https://github.com/wsg138/EnthusiaLoreItems/issues/71), [#39](https://github.com/wsg138/EnthusiaLoreItems/issues/39), [PR #73](https://github.com/wsg138/EnthusiaLoreItems/pull/73), [PR #75](https://github.com/wsg138/EnthusiaLoreItems/pull/75).
