# Handoff 0023 — PR #3 tracked-item protection and terminal void loss

## Session metadata

- Date: 2026-08-03
- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Reported implementation head: `04f25f85050a77929bb2731f745bf429c192ade3`
- Handoff-preparation head before this report: `52206ae7ee4dcfba285eee9ca8f8d20d2d7f38e9`
- Session status: logical protection slice complete; PR remains draft because the broader phase is incomplete

## Objective

Complete the next bounded PR #3 work item from the prior handoff: protect tracked items from environmental, merge, despawn, and durability loss while implementing durable, intentionally terminal void destruction without world scans, force-loaded chunks, blind restoration, or Bukkit access off the owning thread.

## Work completed

### Paper protection listener

Added `PaperTrackedItemProtectionListener` and activated it during plugin enable, before durable storage finishes starting.

The listener now:

- cancels natural item despawn for valid tracked items and malformed LoreItems identity evidence;
- cancels item combustion;
- cancels item-entity merging when either side contains LoreItems identity evidence;
- cancels ordinary item-entity environmental damage, including fire, lava, explosions, cactus, and equivalent `EntityDamageEvent` paths;
- cancels `PlayerItemDamageEvent` so tracked equipment and tools do not lose durability;
- leaves untracked vanilla items unchanged;
- preserves malformed identity evidence rather than splitting, repairing, deleting, or terminalizing it.

The listener keeps in-flight instance IDs and retry cooldowns behind one synchronization boundary. Both collections are hard-bounded, cleared on close, and reject new work after shutdown starts.

### Durable terminal void-loss workflow

Added application contracts and implementation:

- `PreparedVoidLoss`;
- `VoidLossUseCase`;
- `VoidLossStore`;
- `PersistingVoidLossUseCase`.

Added `SQLiteVoidLossStore` using the existing V1 schema and mutation state machine. No released migration was edited and no new migration was required.

For a valid tracked item receiving true void damage:

1. Paper cancels the destructive event so the physical item remains present while storage work begins.
2. The application prepares a fresh claimed `VOID_TERMINAL_LOSS` mutation with a bounded lease and preparation audit evidence.
3. SQLite requires the durable instance to exist, remain active, and match the physical definition ID and applied revision.
4. Open or acknowledged duplicate, malformed-stack, conflicting-observation, or identity-mismatch anomalies block destruction.
5. Paper returns to the server thread, reacquires the exact entity by UUID, rereads the hidden identity, and requires it to remain below the world's minimum height.
6. Only then is the exact item entity removed.
7. One SQLite transaction advances `CLAIMED -> APPLIED -> VERIFIED -> COMPLETED`, changes the instance lifecycle to `VOID_DESTROYED`, appends a `TERMINAL_VOID` observation, advances current state to `TERMINAL_VOID`, clears the claim, and appends completion audit evidence.

If the item rises above the minimum height before removal, the mutation is completed as an audited abort and the active instance remains unchanged. Missing entities, identity changes, scheduling failures, claim expiry, or uncertain post-removal completion move to `REVIEW_REQUIRED` instead of causing blind removal, recreation, or retry.

Each prepared operation captures the storage-backed use case that accepted it. Plugin shutdown or a later delegate swap therefore cannot redirect completion/review work to the unavailable startup delegate.

### Plugin wiring and documentation

`LoreItemsPlugin` now:

- installs the protection listener during enable;
- publishes the persistent void-loss use case only after writable SQLite startup;
- exposes a fail-closed unavailable implementation during startup, degraded mode, and shutdown;
- closes the protection listener before database shutdown;
- continues using the existing bounded expired-mutation recovery path.

`docs/development.md` now documents environmental/durability protection, durable void destruction, failure/review behavior, threading boundaries, and the remaining phase limitations.

## Important decisions and invariants

- Protection is active even when storage is unavailable; terminal void destruction is withheld unless durable intent was accepted.
- Malformed identity evidence is protected and preserved, not treated as a valid terminal instance.
- The physical removal occurs only after durable preparation and exact entity/identity revalidation.
- No live `Entity`, `Item`, `ItemStack`, world, or inventory reference crosses the asynchronous persistence boundary.
- Entity reacquisition uses the exact UUID and does not scan worlds or force-load chunks.
- Ambiguity is fenced into review; the plugin never restores or re-removes an uncertain item blindly.
- Existing unresolved identity anomalies block terminalization.
- Terminal lifecycle, observation, current-state projection, mutation state, and audit evidence commit atomically.
- The released V1 migration remains unchanged.
- Item-frame, glow-frame, armor-stand, and mob-pickup handling remain outside this logical slice.

## Files or modules changed

### Application

- `application/src/main/java/net/enthusia/loreitems/application/PreparedVoidLoss.java`
- `application/src/main/java/net/enthusia/loreitems/application/VoidLossUseCase.java`
- `application/src/main/java/net/enthusia/loreitems/application/VoidLossStore.java`
- `application/src/main/java/net/enthusia/loreitems/application/PersistingVoidLossUseCase.java`
- `application/src/test/java/net/enthusia/loreitems/application/PersistingVoidLossUseCaseTest.java`

### Paper adapter

- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperTrackedItemProtectionListener.java`
- `adapters-paper/src/test/java/net/enthusia/loreitems/paper/PaperTrackedItemProtectionListenerTest.java`

### SQLite adapter

- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteVoidLossStore.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteVoidLossStoreTest.java`

### Plugin and documentation

- `plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java`
- `docs/development.md`

Temporary Codacy evidence and self-removing remediation workflows were deleted after use and are not part of the retained implementation.

## Persistence, state-machine, or API changes

- New pending mutation type: `VOID_TERMINAL_LOSS`.
- Existing lifecycle value used: `VOID_DESTROYED`.
- Existing observation/current-state value used: `TERMINAL_VOID`.
- New audit event types:
  - `void_loss_prepared`;
  - `void_loss_completed`;
  - `void_loss_aborted`;
  - `void_loss_review_required`.
- No public `LoreItemsServiceV1` contract changed.
- No database migration changed.

## Verification actually performed

### Automated tests added

Application tests verify deterministic mutation/claim generation, bounded claim lease, and completion/abort/review delegation.

SQLite integration tests verify:

- terminal instance, observation, current state, mutation, and audit persistence across runtime restart;
- unknown and mismatched identities create no destructive intent;
- unresolved duplicate anomalies block terminalization;
- rescued-item abort leaves the instance active;
- review-required work blocks blind re-preparation;
- forced audit failure rolls back every terminal write.

Paper/MockBukkit tests verify:

- tracked and malformed items cannot despawn, combust, or merge;
- ordinary untracked items remain unaffected;
- tracked durability and environmental damage are cancelled;
- a valid below-minimum-height item prepares durable intent, removes the exact entity, and invokes completion.

### GitHub Actions

Run `30838581666`, job `91769836647`, checked merge ref `7c6e4a5dc716a1ee5699b0e2b08346528803d654`, containing branch head `66fef8f2094740c0d47e6e9d8e3dc5f6959790e9` against base `62268c9197e28ff690fda095a4878aa0f0556721`.

- Java: Temurin `21.0.11+10`.
- Gradle: `8.14.3`.
- Command: `gradle --no-daemon clean check`.
- Result: `BUILD SUCCESSFUL in 33s`.
- Tasks: 34 actionable, 26 executed, 8 up-to-date.
- Domain, application, SQLite, Paper/MockBukkit, plugin, and architecture checks completed.

The later implementation commit `04f25f85050a77929bb2731f745bf429c192ade3` replaced nullable JDBC claim-expiry mapping with `OptionalLong` after an exact remediation workflow ran `clean check --rerun-tasks` before publishing the commit. A fresh documentation-complete exact-head CI run is still required after the handoff pointer/index updates and must be recorded in the PR body.

### Codacy

Exact check-run evidence was exported for implementation head `04f25f85050a77929bb2731f745bf429c192ade3`:

- check run: `91770317978`;
- status: completed;
- conclusion: success;
- title: `Your pull request is up to standards!`;
- summary: `Codacy found no issues in your code`;
- annotations: 0;
- completed: `2026-08-03T17:53:06Z`.

The earlier PR summary showing one high and two medium findings was stale. Exact annotations identified and fixed a literal boundary, nullable JDBC mapping, and an unobserved MockBukkit teleport result. No analyzer was suppressed or weakened.

## Harsh review findings and fixes

The complete protection slice was separately reviewed for item loss, duplicate terminalization, malformed evidence destruction, thread ownership, entity replacement races, startup/degraded behavior, claim expiry, post-removal persistence failure, shutdown races, unbounded bookkeeping, JDBC null handling, rollback, and phase leakage.

Confirmed defects fixed:

1. A scheduling failure after durable preparation could strand a claim and in-flight entry. The listener now moves that prepared mutation to review through the captured use case.
2. Swapping service delegates during shutdown could redirect prepared completion to the unavailable implementation. Each operation now captures the original storage-backed use case.
3. Concurrent `size()` and insertion checks did not make the cooldown/in-flight limits strictly hard-bounded. One synchronization boundary now protects admission, completion, cooldown insertion, and close.
4. A synchronous exception or null completion stage from a persistence port could strand in-flight work, including after physical removal. Every stage acquisition is guarded and uncertain removal enters review.
5. The JDBC mapper called `wasNull()` after reading another column, so it could test the wrong value. Claim expiry is now captured immediately as `OptionalLong`.
6. Paper test constructors deprecated for removal failed the repository's `-Werror` gate. Tests now use the supported float-duration and original-damage constructors.
7. The void test compared reconstructed identity by reference and ignored teleport success. It now checks value equality and asserts teleport success.
8. Codacy's literal-boundary finding was resolved with a named lower-bound constant.

No additional confirmed loss, duplication, transaction, owning-thread, bounded-work, migration, or phase-boundary defect remained in this slice.

## Live automation and review state observed

- PR #3 remained open, draft, and mergeable after the implementation changes.
- CodeRabbit continued to skip substantive automatic review because the PR is draft.
- Submitted human reviews: none observed.
- Unresolved review threads: none observed before handoff preparation.
- No live Paper/Leaf server behavior was tested or claimed.

## Preserved phase boundary

Completed in this slice:

- dropped tracked-item despawn, combustion, merge, and environmental-damage protection;
- player-held durability protection;
- durable terminal void destruction and ambiguity fencing.

Still deferred within Implementation PR 2:

- item-frame and glow-frame support;
- armor-stand support;
- mob pickup prevention;
- identity-losing use or conversion restrictions not covered by the completed event paths;
- initial audit browsing and recovery administration surface;
- duplicate/malformed five-minute staff warnings;
- broader tracking and reconciliation.

Later phases remain deferred: GUIs, definition editing, campaigns, deletion, and EnthusiaTags integration.

## Exact next step

First reconcile this report with live GitHub and verify the final documentation-complete branch head, CI, Codacy, submitted reviews, unresolved threads, and PR body. Do not rely solely on the implementation-head evidence above.

Then resume draft PR #3 with the next bounded protection slice: supported item-frame/glow-frame/armor-stand placement and break semantics plus mob pickup prevention. Preserve exact hidden identity and unstackability, keep Bukkit entity access on the owning Paper thread, avoid scans and force-loaded chunks, and persist only authoritative location changes. Do not begin audit UI, broad reconciliation, warnings, GUIs, editing, campaigns, deletion, or Tags integration in that slice.

## Required prior reports

- [`0022-2026-08-03-pr3-direct-delivery-codacy-cleanup.md`](0022-2026-08-03-pr3-direct-delivery-codacy-cleanup.md) — clean starting head and direct-delivery completion state.
- [`0021-2026-08-03-pr3-direct-delivery-recovery.md`](0021-2026-08-03-pr3-direct-delivery-recovery.md) — delivery claim/recovery behavior that shares pending-mutation recovery infrastructure.
- [`0020-2026-08-03-pr3-held-item-adoption.md`](0020-2026-08-03-pr3-held-item-adoption.md) — exact identity and mutation invariants inherited by terminal loss.
- [`0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`](0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md) — current `main` foundation baseline.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — Paper identity codec behavior used by the listener.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — transaction and rollback invariants used by the SQLite store.
