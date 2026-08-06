# WP-01 author-side full-package harsh review

## Review identity

This was a separate author-side harsh-review pass over the complete WP-01 package after implementation. It is **not** an external review and is **not** an independent automated review. External review remains preferred when available, but the WP-01 contract permits this recorded pass when an external service is unavailable or rate-limited.

## Scope reviewed

The pass compared the entire branch against:

- `ai-agents/work-packages/WP-01-editor-and-template-management.md`;
- the item-editor, tracking, identity, persistence, and administration requirements in `REQUIREMENTS.md`;
- the template-representation, rollout, threading, bounded-work, and SQLite rules in `docs/architecture.md`;
- the editing and rollout phase in `docs/implementation-plan.md`;
- repository-wide loss, duplication, identity, ambiguity, threading, bounds, reload, shutdown, and architecture risks.

The review directly inspected the GUI/chat session boundary, every typed component editor, exact held-item replacement, immutable revision confirmation, SQLite idempotency and transaction boundaries, rollout planning/execution wake-up behavior, naturally accessible inventory/entity coverage, identity-preserving physical replacement, anomaly/review routing, lifecycle cleanup, operator documentation, and test evidence.

## Findings and confirmed fixes

### 1. Rollout pending count omitted unscheduled active instances

**Risk:** The management GUI counted only existing pending mutation rows. A large rollout schedules mutation rows in bounded batches, so active instances whose desired revision was already newer but whose mutation row had not yet been staged were incorrectly excluded.

**Fix:** `SQLiteTemplateManagementQueryStore` now counts active instances whose `applied_revision` is below the definition's current revision. This reflects the full logical rollout, including durable work not yet materialized into a mutation row.

**Regression evidence:** `SQLiteTemplateEditorConfirmationTest.managementSnapshotReturnsBoundedCountsAndCurrentTemplate` now proves the count includes both scheduled and unscheduled outdated instances.

### 2. Exact component-operation tests did not exercise every required sub-operation

**Risk:** Several editor families had happy-path tests but did not prove update/remove/clear variants, tooltip controls, all banner/firework operations, or all modern custom-model command routes.

**Fix:** `PaperTemplateDraftEditorTest` was expanded to cover literal/solid/gradient names and lore; enchant set/update/remove/clear and tooltip state; glint tri-state; flags and tooltip controls; attributes set/update/remove/clear with stable identity; item-model set/clear; custom-model floats/flags/strings/colors/clear; dye clear; potion effect removal/clear; banner add/set/remove/clear; and firework rocket/star set/add/remove/clear paths. MockBukkit limitations for modern Paper component value retention are stated in `docs/development.md`; codec round-trip coverage remains the exact-copy fallback evidence.

### 3. Later rollout batches did not wake accessible execution

**Risk:** Confirmation woke accessible scanning for the initial batch, but subsequent planner batches only woke the planner. Already-online inventories and loaded containers could remain stale until an unrelated natural event or periodic activity.

**Fix:** `PaperTemplateRevisionPlannerWorker` now schedules an owning-thread accessible-execution wake after every successfully scheduled non-empty batch. `LoreItemsPlugin` wires the callback to the existing bounded mutation/access listeners.

**Regression evidence:** `PaperTemplateRevisionPlannerWorkerTest` proves two scheduled batches produce two execution wakes while retaining bounded planner behavior.

### 4. Delayed async chat could cross editor sessions

**Risk:** The async chat boundary originally keyed pending input only by player UUID. A delayed message from a closed session could be delivered after the same player opened a new prompt and mutate the new draft.

**Fix:** Pending chat is now fenced by both player UUID and editor session UUID. The async event captures the current session token, and the main-thread receiver discards stale tokens without clearing a newer prompt.

**Regression evidence:** `PaperTemplateEditorManagerTest.staleAsyncChatFromAClosedSessionCannotEditANewSession` exercises the race through the registered event boundary.

### 5. Session cleanup assumed an always-present top inventory

**Risk:** MockBukkit exposed a defensive lifecycle gap where a player could have no usable top inventory while sessions were being closed. Shutdown/reload cleanup could throw before clearing all sessions.

**Fix:** Session cleanup now null-checks the top inventory before inspecting its holder, then always closes and clears the session state.

### 6. Armor-stand equipment was explicitly excluded from template updates

**Risk:** WP-01 requires already-accessible armor-stand instances to update. The previous entity scanner supported dropped items, frames, and item displays but intentionally returned no candidate for armor stands.

**Fix:** Entity references now address one armor-stand equipment slot at a time. The scanner emits independent candidates for main hand, off hand, feet, legs, chest, and head; the access registry stores multiple candidates per entity; manipulation events schedule a next-tick observation; periodic loaded-entity reconciliation remains the fallback. Physical replacement verifies and changes only the addressed slot, preserving sibling equipment and the armor-stand entity.

**Regression evidence:** Scanner, controller, uniqueness, and reference tests cover multiple tracked slots, normal/glow frames, sibling preservation, and slot-specific identity replacement.

### 7. A slow submitted confirmation could produce a false timeout message

**Risk:** The generic editor timeout could fire while a durable confirmation transaction was already in flight and tell the administrator that no revision was created. The transaction might still complete, making the operator message false and encouraging an unsafe duplicate action.

**Fix:** A timeout before confirmation still states that no revision was created. A timeout while confirmation is in flight closes the bounded UI session but truthfully directs the administrator to reopen management and check durable status; it does not claim cancellation.

**Regression evidence:** `PaperTemplateEditorManagerTest.confirmationTimeoutDoesNotClaimTheDurableRequestWasCancelled` proves the distinction.

### 8. New-code complexity blockers in exact-head CI

**Risk:** The first exact-head CI run completed Gradle and repository-tool tests but found six introduced Codacy-Lizard threshold violations in editor dispatch, potion parsing, access draining, large manager/store files, and one oversized test.

**Fix:** Dispatch and parsing were decomposed, scan draining was split into bounded helpers, management loading and Bukkit events were separated from the manager, confirmation SQL was extracted from the rollout store, and the oversized test was divided by behavior. Limits were not weakened or suppressed.

## Risk conclusions

- **Loss/duplication:** Confirmed edits are immutable and transactionally coupled to rollout intent. Physical updates require one unique accessible candidate; duplicate/conflicting identities are not overwritten. Failed or ambiguous writes remain durable and enter review.
- **Identity:** Template codecs strip LoreItems identity only from editor source snapshots. Rollout replacement preserves the original definition ID and instance UUID and verifies the expected old and target revisions.
- **Threading:** Bukkit objects and serialization remain on the owning server thread. Database operations use completion stages backed by the bounded storage runtime. Async chat carries only immutable IDs/text to the main-thread receiver.
- **Bounds:** GUI queries, revision planning, accessible scanning, retry queues, and per-tick mutation execution are bounded. Loaded-world traversal snapshots only already-loaded chunks/entities and never force-loads a chunk; snapshot creation can still scale with the server's already-loaded set and should be observed during WP-05 live performance acceptance.
- **Reload/shutdown:** Unconfirmed sessions are cancelled and listener/tasks are unregistered. Durable confirmation/rollout state survives restart and is rediscovered by periodic workers.
- **Architecture:** Domain/application code remains independent of Bukkit/Paper/JDBC/GUI types; Paper and SQLite responsibilities remain in adapters.

## Verification available before final GitHub gate

- Exact exported branch source reconstructed and tested locally with Java 21 and Gradle 8.14.3.
- `gradle --offline --no-daemon clean test`: passed for all modules.
- Repository tool tests: 3 passed.
- Focused Paper and SQLite regression suites for every finding above: passed.
- `git diff --check`: passed.
- The full local `clean check` reached SpotBugs without a test failure but exceeded the local command execution window during static analysis; this is not recorded as a passing clean check.

Final completion still requires a new exact-head GitHub Actions run, exact-head Codacy success, review/thread reconciliation, normal merge, and live `main` verification.
