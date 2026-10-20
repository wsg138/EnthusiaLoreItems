# Combined low-risk LoreItems performance candidate — 2026-10-16

**Status: DEVELOPMENT ONLY, NOT APPROVED FOR SMP.** Live SMP currently runs with no LoreItems JAR; existing `plugins/EnthusiaLoreItems/loreitems.db` and surviving physical PDC identities must remain intact. Owner agreed to leave LoreItems absent for roughly four days while the release blockers are addressed.

## Exact provenance and scope

- Base performance change: [#79](https://github.com/wsg138/EnthusiaLoreItems/pull/79), exact source commit `81dd6b0e3c394d9feb8a6badebe76dd536b0b727`, based on current `main` lineage. Replaces expensive Paper tile/block holder **snapshot** calls with `getTileEntities(false)`, `getHolder(false)`, and `getState(false)`, retaining unloaded-chunk safeguards, fresh reads, and item identity provenance.
- Overlay: [#82](https://github.com/wsg138/EnthusiaLoreItems/pull/82), exact source commit `e8a5d498183bf8c143669332e009130060b6605f`. Transplants its **five exact GitHub file contents** into disjoint paths, leaving #79 files unchanged. A plain hopper move with no LoreItems identity evidence now avoids two inventory captures and next-tick source/destination scans. Tracked identities, nested shulker/bundle data and malformed/partial PDC evidence retain reconciliation.
- These are targeted CPU-cost reductions, **not** the unmerged #94 event-first overhaul. Neither change fixes #39's entire rejected chunk/player queue, last-confirmed backlog, #95's complete cross-chunk matrix, or #103's legacy-world-key migration.
- The previously reported isolated operation medians (~3.55→0.095 ms for a shulker-filled chest, ~0.711→0.042 ms for a hopper) originate from [#70](https://github.com/wsg138/EnthusiaLoreItems/pull/70), not a fresh combined benchmark or a claim of SMP TPS improvement.

## Combined invariants to test

1. Real Paper 26.2/Java 25 item scanning preserves unique PDC instance IDs and nested, duplicate, partial/malformed tracking evidence.
2. Loaded-only, no-force-load holder discovery; no stale snapshots and no retained mutable Bukkit inventories between ticks.
3. Ordinary untracked hopper items skip the deferred full source/destination scan, but both nested tracked and partial/malformed LoreItems identity evidence trigger existing reconciliation.
4. Full end-to-end physical tracking and SQLite failure/rollback tests remain required; no runtime JAR replacement, hot-load, SMP restart, or live DB mutation occurs in this PR.
5. Exact **combined commit** needs its own clean Gradle/CI/Codacy, complete hosted WP-05 workflow matrix, artifact reproducibility and real disposable Paper acceptance; individual #79/#82 successes are **not** joint proof.
6. Do not silently restore stale database location values to authoritative CONFIRMED_NOW after the multi-day outage. Future recovery must inventory/observe physical instances with proof, preserve ambiguous or unobservable states, and never delete, clone or forcibly relocate player items.

## Release and operator gates

Source review and tests can merge into development only after independent verification; `main`, staging and SMP deployment require separately reviewed runtime/performance and data-safety proofs and owner-authorized change window. Protect WAL-consistent `loreitems.db` plus sidecar evidence before any eventual installation and never replace the DB or regenerate old instance identifiers.
