# AI-OFF physical scan work budget: count ordinary items

**DRAFT ONLY. Not approved for `main`, TEST or live SMP.**

## Correctness/performance defect

Prior `PaperScanLimit(256)` was applied to nested shulker/bundle *expansions* and entity/container roots but **did not decrement for ordinary inventory roots or nested leaf items**. This could let one scan visit more than 256 non-air item stacks while still returning a completeness/unique-identity outcome. Under large inventories this may worsen tick latency, and with nested PDC matching it risks a false complete scan.

## Fail-closed fix

- `PaperPhysicalInventoryScanner.scanArray` charges one work unit for every nonair inventory root **before** observing its identity or children. `scanItem` also charges one work unit per nested nonair leaf (depth > 0), retaining caller responsibility for standalone item/entity roots.
- `PaperTrackedItemCollector.collectArray` and nested-depth `collectItem` mirror the same accounting for matching / duplicate checking. Shulker and bundle expansion overhead continues to cost its existing work unit.
- Budget exhaustion is **incomplete evidence** (`PaperScanLimit.hasRemaining() == false`) and unique-match paths must use RECONCILIATION, not AUTHORITATIVE_TRANSITION. Ordinary empty inventory slots do not consume units.
- No forced chunk loads, cross-tick Bukkit references, PDC/identity writes or SQLite mutations.

## Synthetic tests

`PaperPerItemScanBudgetTest` checks 257 ordinary roots under 256 units (incomplete), exactly 54 roots under 54 units (complete), a 54-slot physical inventory under 40 vs 54 units, and a 27-slot nested shulker with a late valid tracked PDC identity beyond a 10-unit limit. It is intentional to leave such an item unresolved until a later, more complete scan.

**This is NOT a resumable cursor or a lossless recovery implementation.** It may conservatively reduce the number of identities discovered by one scan under high load. A generation-fenced, fair cursor with revalidation, durable acknowledgment, and actual full catch-up for >256 roots is still needed under #39/#83/#84 before live restoration.

Retest on exact combined commit with the full 25 hosted workflows, real Paper 26.2 cross-border chests, and an explicit AI-OFF MSPT/correctness benchmark. No live server or SQLite edits.
