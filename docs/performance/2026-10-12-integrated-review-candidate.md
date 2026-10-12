# LoreItems integrated release candidate — review checkpoint

**Target:** GitHub review branch only; **do not merge into main or deploy to Bloom/SMP** without a later explicit owner decision.

This candidate combines reviewed work from draft #77 (reproduction), #78 (offline backup checking/runbook), #79 (non-snapshot inventory/chunk scans), and #80 (failure isolation, skipped outcome accounting and peak telemetry), preserving the existing holder-once and one-inventory-read optimizations on main.

## Correctness invariants
- No chunk force-load; fresh world and player resolution before queued work. Chunk-unload LAST_CONFIRMED scanning remains synchronous and bounded; this change removes block-state snapshot copying but does not defer authoritative unload transitions.
- Player, ender, block and entity inventory tracking, nested shulker/bundle contents, identity confidence and source labels remain meaningful.
- Queued work distinguishes completed callbacks, skipped inaccessible targets, thrown failures and admission rejects; failed requests cannot falsely count as completed. Queue FIFO, periodic fairness, capacity and per-tick budget remain unchanged. **The existing queue may still reject unique lifecycle scans under overload**.
- Timers capture cumulative scan work and monotonic worst-case player/chunk/periodic/drain times; metrics don't alter scheduling.
- Offline SQLite backup checker is deliberately a *structural check*, not proof of atomic multi-file snapshot consistency. WAL/SHM must be handled with a separate disposable SQLite backup operation preserving the raw capture unchanged. No live file mutates.

## Review and release gates
- All source PRs individually passed 25/25 GitHub workflows at their recorded heads, but this **integrated combination requires its own fresh exact-head tests, analyzers, full 25-workflow suite and independent review**, with any failures resolved before marking code-ready.
- Repeated disposable Paper acceptance, queue burst saturation, nested tracked item, unload/reload LAST_CONFIRMED and rollback tests must be evaluated on the integrated head. None of these outcomes establish production MSPT improvement by themselves.
- Before any deployment, verify current live plugin SHA/source and migration lineage (#54), take consistent stopped-process backup (SQLite plus config/groups and world/player state), prove compatible rollback and obtain owner approval for maintenance. Capture matched AI-off performance after deployment and only then consider controlled AI-on comparisons.
- Outstanding reliability issues #39 (rejected scan admissions) and #45 (intermittent LAST_CONFIRMED timeout) remain open even if CI is green. Treat as production signoff blockers, not issues solved by an optimized hot path.
