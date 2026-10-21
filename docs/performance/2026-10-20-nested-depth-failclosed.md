# Nested-depth fail-closed evidence — 2026-10-20

**DRAFT / SMP release HOLD.** This development-only patch stacks on [the 25/25-green combined draft #165](https://github.com/wsg138/EnthusiaLoreItems/pull/165) (exact original SHA `2f227bf987f3b7edcaef65ef99fd3530e0e11b8a`).

## Defect and narrow safeguard

Physical scanner and collector have a maximum nested depth of 8. At that depth the old code unconditionally stopped descending without flagging `PaperScanLimit` incomplete. A metadata-bearing shulker or bundle could therefore contain **unvisited inventory data** but the outer scan could still count as complete. In a matching or unique scan this risks claiming an authoritative exact current position on incomplete evidence.

- `PaperScanLimit.markTruncated()` records irreversible incompleteness.
- At maximum depth, `PaperTrackedItemCollector.collectItem` and `PaperPhysicalInventoryScanner.scanItem` mark the limit incomplete if a *scannable metadata-bearing, nested-capable* item (shulker or bundle) is encountered.
- Ordinary stone or other leaf stacks at the limit are not falsely marked as truncated. Known tracked identities on visited items remain submitted unchanged, but incomplete scans cannot use full-scan uniqueness as proof.
- No extra force-loaded chunks, cross-tick Bukkit references, SQLite writes, live item mutations, migration, new permissions or network calls.
- MockBukkit/JUnit regression exercises the exact depth boundary with a shulker containing a nested stone stack, checks a stone leaf stays complete and verifies that `markTruncated()` remains incomplete even with work budget left.

## Scope limits and release requirements

This prevents one overconfident location claim; it does **not** implement a resumable cursor for chunk roots or nested data beyond 256 scan units, re-observe items after a four-day plugin outage, settle legacy world-name to UUID migration #103, or prove the complete real double-chest matrix #95. Performance must be measured against realistic AI-OFF workload before deployment. Any historical SQLite `CONFIRMED_NOW` record from the disabled interval is only historical until fresh physical evidence.

The full **exact combined-head** 25-workflow acceptance matrix, Codacy, reproducible JAR and independent review are mandatory. Do not merge to `main`, install to TEST/SMP, restart SMP, or modify the original database/player items from this PR without owner approval and a demonstrated rollback plan.
