# Handoff 0024 — PR #3 display entities and mob pickup prevention

## Session metadata

- Date: 2026-08-03
- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Reported clean implementation head: `431118eced6031b0dc2a47db458e3d06ca33e6f7`
- Handoff-preparation head before this report: `f9f06b9e6783b00da5f441d6c061113898bf2840`
- Session status: display-entity and mob-pickup logical slice complete; PR remains draft because the broader phase is incomplete

## Objective

Complete the next bounded PR #3 work item from report 0023: support tracked items in ordinary/glow item frames and armor stands, and prevent non-player entities from picking up tracked or malformed LoreItems evidence, without world scans, force-loaded chunks, off-thread Bukkit access, unbounded work, or speculative location replacement.

## Work completed

### Application boundary

Added:

- `DisplayItemObservationUseCase`;
- `DisplayItemObservationStore`;
- `PersistingDisplayItemObservationUseCase`.

A request carries the immutable LoreItems identity, an exact `ITEM_FRAME` or `ARMOR_STAND` location with a slot path, presence (`PRESENT` or `ABSENT`), and a bounded source string. Results distinguish recorded, unchanged, stale, unknown-instance, identity-mismatch, inactive-instance, blocked-anomaly, and service-unavailable outcomes.

### Atomic SQLite display observations

Added `SQLiteDisplayItemObservationStore` using the existing V1 observation, current-state, anomaly, instance, and audit tables. No released migration was edited and no new migration was required.

For present evidence, SQLite:

1. requires a durable active instance matching the physical definition ID and applied revision;
2. rejects unresolved duplicate, malformed-stack, conflicting-observation, or identity-mismatch anomalies;
3. preserves conflicting and terminal current state;
4. inserts a `CONFIRMED_NOW` observation for the exact display entity and slot;
5. advances current state with a state-revision compare-and-set;
6. appends audit evidence in the same transaction.

For absent evidence, SQLite advances only when the exact supplied display location still equals the current durable location. It records `LAST_CONFIRMED` rather than deleting the known location or guessing where the item moved. Stale removal evidence leaves current state unchanged.

Observation insertion, current-state replacement, and audit append commit atomically. A forced audit failure test proves rollback of the observation and current-state update.

### Paper display listener

Added `PaperDisplayItemListener` and activated it through plugin lifecycle wiring.

The listener handles:

- `PlayerItemFrameChangeEvent` for placement, replacement, rotation-related item changes, and removal;
- `HangingBreakEvent` for ordinary and glow item frames;
- `PlayerArmorStandManipulateEvent` for exact equipment-slot changes;
- armor-stand damage by scheduling an exact next-tick equipment reread;
- `EntityPickupItemEvent` to prevent non-player pickup of valid tracked items and malformed LoreItems identity evidence.

Glow item frames use the same path because they implement the item-frame contract. Each event records only candidate immutable identities and an exact entity UUID/location/slot key, then schedules one next-tick server-thread reread. The listener reacquires the exact entity by UUID and never scans worlds or force-loads chunks. No live entity or mutable `ItemStack` crosses the asynchronous persistence boundary.

Player pickup remains unchanged. Untracked vanilla items remain unchanged. Malformed LoreItems evidence is preserved and protected rather than repaired, split, deleted, or converted into a valid durable instance.

### Bounded work and lifecycle

Display work is coalesced by entity UUID, location type, slot, and source. Candidate identities for one coalesced slot are hard-capped at 16. Persistence concurrency is bounded by the configured mutation budget, and excess accepted requests enter a bounded FIFO that drains as prior writes complete. Capacity exhaustion logs a warning and preserves existing durable evidence instead of silently inventing or replacing state.

The plugin publishes the persistent display observation use case only after writable SQLite startup. Startup, degraded mode, and shutdown use a fail-closed unavailable delegate. The listener unregisters, clears pending/coalesced work, and rejects new work during close.

## Important decisions and invariants

- Physical identity and forced unstackability are read, not rewritten, by display handling.
- Display location evidence is event-driven and exact; there is no broad reconciliation or periodic world scan in this slice.
- Present evidence may replace ordinary nonterminal current state only after durable identity checks.
- Absent evidence may only downgrade the exact current display location from `CONFIRMED_NOW` to `LAST_CONFIRMED`.
- Unknown, mismatched, inactive, anomalous, conflicting, terminal, or stale evidence never replaces durable current state.
- Current-state updates use state-revision compare-and-set semantics.
- Observation, current state, and audit evidence commit atomically.
- Bukkit entity/item access remains on the Paper server thread.
- Non-player mob pickup is blocked for both valid and malformed LoreItems identity evidence.
- No migration or public `LoreItemsServiceV1` contract changed.
- Initial audit browsing, staff warning loops, broad tracking/reconciliation, and later GUI/editing/campaign work remain outside this slice.

## Files or modules changed

### Application

- `application/src/main/java/net/enthusia/loreitems/application/DisplayItemObservationUseCase.java`
- `application/src/main/java/net/enthusia/loreitems/application/DisplayItemObservationStore.java`
- `application/src/main/java/net/enthusia/loreitems/application/PersistingDisplayItemObservationUseCase.java`
- `application/src/test/java/net/enthusia/loreitems/application/PersistingDisplayItemObservationUseCaseTest.java`

### SQLite adapter

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDisplayItemObservationStore.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteDisplayItemObservationStoreTest.java`

### Paper adapter

- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperDisplayItemListener.java`
- `adapters-paper/src/test/java/net/enthusia/loreitems/paper/PaperDisplayItemListenerTest.java`

### Plugin and documentation

- `plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java`
- `docs/development.md`

All temporary source-export, remediation, and Codacy-inspection workflows were removed. The retained CI workflow was restored byte-for-byte.

## Persistence and audit changes

- Existing observation confidence values used:
  - `CONFIRMED_NOW`;
  - `LAST_CONFIRMED`.
- Existing location types used:
  - `ITEM_FRAME`;
  - `ARMOR_STAND`.
- New audit event types:
  - `display_item_confirmed`;
  - `display_item_last_confirmed`.
- No database migration changed.
- No public Bukkit service API changed.

## Verification actually performed

### Automated tests added

Application tests verify validated request delegation at the injected clock instant.

SQLite integration tests verify:

- confirmed display evidence followed by exact removal becomes `LAST_CONFIRMED` and survives runtime restart;
- repeated identical evidence is unchanged rather than duplicated;
- unknown instances, identity mismatches, unresolved anomalies, and stale removal evidence do not replace current state;
- observation, current state, and audit evidence remain aligned;
- forced audit failure rolls back every display-state write.

Paper/MockBukkit tests verify:

- non-player mobs cannot pick up valid tracked or malformed LoreItems items;
- player pickup and ordinary vanilla items remain unaffected;
- item-frame placement and removal produce exact present then absent evidence;
- armor-stand placement, removal, and destruction use the exact equipment slot;
- a busy persistence limit queues and later drains observations instead of dropping them;
- one coalesced display slot cannot accumulate more than 16 candidate identities.

### GitHub Actions

Clean retained implementation head `431118eced6031b0dc2a47db458e3d06ca33e6f7` was verified by Actions run `30843047869`, job `91784567014`.

The job checked merge ref `ee89e29cc20125927b1c19d92e9b1f383c01ac61`, containing exact branch head `431118eced6031b0dc2a47db458e3d06ca33e6f7` against base `62268c9197e28ff690fda095a4878aa0f0556721`.

- Java: Temurin `21.0.11+10`.
- Gradle: `8.14.3`.
- Command: `gradle --no-daemon clean check`.
- Result: `BUILD SUCCESSFUL in 35s`.
- Tasks: 34 actionable, 26 executed, 8 up-to-date.
- Domain, application, SQLite, Paper/MockBukkit, plugin, and architecture checks completed.

A fresh exact-head run is still required after this immutable report and the handoff pointer/index updates. That final evidence belongs in the PR body and live handoff reconciliation; this immutable report does not predict its result.

### Codacy

The Codacy PR summary refreshed at `2026-08-03T18:51:55Z`, after the clean retained implementation head and CI run, and reported:

- `Up to standards`;
- `0 issues`;
- `0 new issues`.

An earlier temporary 16-issue summary was superseded after diagnostic workflow files were removed. No analyzer was disabled or weakened. Final documentation-complete head evidence is still required after the handoff commits.

## Harsh review findings and fixes

The display slice and its integration with the full active PR were separately reviewed for item loss, stale location replacement, duplicate identity handling, event ordering, thread ownership, startup/degraded behavior, shutdown, transaction rollback, queue saturation, unbounded coalescing, dropped asynchronous work, migration leakage, and misleading tests.

Confirmed defects fixed:

1. When the persistence concurrency limit was occupied, accepted display observations were silently discarded. A bounded FIFO now retains and drains them as prior writes complete; overflow is explicit and preserves current durable evidence.
2. Repeated same-tick changes for one display slot could accumulate candidate identities without a hard cap. Candidate identities are now capped at 16, overflow is warned, and the actual next-tick current identity is handled separately.

No additional confirmed item-loss, duplication, transaction, owning-thread, bounded-work, migration, architecture, or phase-boundary defect remained in this logical slice.

## Live automation and review state observed

- PR #3 remained open, draft, and mergeable during implementation.
- CodeRabbit continued to skip substantive automatic review because the PR is draft.
- Submitted reviews: none observed before handoff preparation.
- Unresolved review threads: none observed before handoff preparation.
- No live Paper/Leaf server behavior was tested or claimed.

## Preserved phase boundary

Completed in this slice:

- ordinary and glow item-frame event support;
- armor-stand equipment event support;
- exact confirmed/last-confirmed display evidence persistence;
- non-player mob pickup prevention;
- bounded asynchronous display observation handling.

Still deferred within Implementation PR 2:

- remaining identity-losing use or conversion restrictions not covered by completed event paths;
- initial audit browsing and recovery administration surface;
- duplicate/malformed-stack detection presentation and bounded five-minute staff warnings;
- broader tracking and reconciliation.

Later phases remain deferred: paginated tracking GUIs, definition editing, campaigns, deletion, production hardening, and EnthusiaTags integration.

## Exact next step

First reconcile this immutable report with live GitHub and verify the final documentation-complete branch head, current `main`, exact-head Actions, Codacy, submitted reviews, unresolved threads, PR body, and absence of temporary workflows.

Then resume draft PR #3 with one bounded remaining-protection work item: identify and prevent the still-unhandled identity-losing item use or conversion paths required by the PR 2 protection scope. Preserve physical identity and unstackability, keep Bukkit access on the owning thread, and fence ambiguity rather than recreating or deleting items blindly.

Do not begin initial audit/recovery UI, duplicate/malformed warning scheduling, broad PR 3 reconciliation, GUIs, editing, campaigns, deletion, production deployment, or Tags integration in that slice.

## Required prior reports

- [`0023-2026-08-03-pr3-tracked-item-void-protection.md`](0023-2026-08-03-pr3-tracked-item-void-protection.md) — environmental/durability protection and terminal void-loss invariants inherited by this listener.
- [`0022-2026-08-03-pr3-direct-delivery-codacy-cleanup.md`](0022-2026-08-03-pr3-direct-delivery-codacy-cleanup.md) — clean direct-delivery baseline.
- [`0021-2026-08-03-pr3-direct-delivery-recovery.md`](0021-2026-08-03-pr3-direct-delivery-recovery.md) — bounded claim/recovery behavior.
- [`0020-2026-08-03-pr3-held-item-adoption.md`](0020-2026-08-03-pr3-held-item-adoption.md) — exact physical identity and mutation invariants.
- [`0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`](0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md) — current `main` foundation baseline.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — hidden identity codec behavior used by the Paper listener.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — transaction and rollback invariants used by the SQLite store.
