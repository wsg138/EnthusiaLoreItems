# Handoff 0021 — durable direct delivery and recovery

## Session metadata
- Date/time: 2026-08-03 05:35 EDT
- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Reported implementation head: `0ccf19507c422e547700803e5a2e5898206ff9ea`
- Session status: in progress

## Objective

Complete exactly the next unfinished logical slice in draft PR #3: durable direct delivery for self, online, offline, and full-inventory recipients, including join/restart recovery, exact-slot Paper mutation, and explicit review-required handling for ambiguity.

## Work completed

- Added `/loreitems give <lookup-key> [online/cached player name or UUID]` with `enthusia.loreitems.admin.give` authorization.
- Self-give resolves the executing player; console must provide a cached name or UUID; uncached offline players remain addressable by UUID without blocking name lookups.
- The command submits the existing versioned `LoreItemsServiceV1.queueDelivery` contract and wakes the bounded delivery worker only after a durable accepted/already-accepted result.
- Added an application execution use case and prepared-delivery model that claim, defer, complete, review, wake, and recover work through bounded SQLite operations.
- Extended SQLite delivery persistence to claim the active definition revision and encoded template together under a fenced lease.
- Added a Paper delivery operator that selects the first empty player-storage slot, decodes the persisted template, writes the preallocated hidden identity, inserts exactly one unstackable item, rereads the exact slot, verifies the identity, and fingerprints the stored bytes.
- Added a bounded Paper worker with one claim request in flight, configured claim/mutation budgets, periodic polling, player-join wakeup, offline/full-inventory deferral, and safe shutdown cancellation/unregistration.
- Completed deliveries transactionally advance `RESERVED -> APPLIED -> VERIFIED -> COMPLETED`, replace the queued observation/current state with the exact player-inventory slot, clear the claim, and append audit evidence.
- Physical or persistence ambiguity moves the delivery to `REVIEW_REQUIRED`; the queued current-state certainty is cleared rather than continuing to claim a confirmed queued location.
- Expired claims are moved to review in bounded batches during every poll, not only once at startup.
- Added focused application, SQLite, Paper/MockBukkit, domain-state, plugin wiring, command, and permission coverage.
- Added permanent startup guidance for `wsg138/EnthusiaStaff-Staging` and historical EnthusiaStaff Actions artifact `8848768264` from run `30794945133`.

## Important decisions and invariants

- A fresh instance identity is allocated when durable delivery intent is accepted, before inventory mutation.
- No live `Player`, `Inventory`, or mutable `ItemStack` crosses an asynchronous boundary.
- Database work remains on the bounded SQLite runtime; inventory access and mutation remain on the Paper server thread.
- Full inventories and offline players are deferred; no item is dropped.
- A claim that expires after physical work might have happened is not retried. It becomes review-required to avoid duplicate delivery.
- If insertion succeeds but durable completion fails or loses its fenced transition, the item is preserved and the delivery enters review.
- Recovery and claim work are bounded by the smaller of the configured claim batch and per-tick mutation budget.
- The broader PR 2 phase remains open; no environmental/durability listeners, void handling, display-entity support, mob pickup prevention, broad reconciliation, GUIs, editing, campaigns, deletion, or Tags integration were added.

## Files or modules changed

Most relevant additions and updates:

- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/GiveLoreItemCommandExecutor.java`
- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperDirectDeliveryOperator.java`
- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperDirectDeliveryWorker.java`
- `application/src/main/java/net/enthusia/loreitems/application/DirectDeliveryExecutionUseCase.java`
- `application/src/main/java/net/enthusia/loreitems/application/PersistingDirectDeliveryExecutionUseCase.java`
- `application/src/main/java/net/enthusia/loreitems/application/PreparedDirectDelivery.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java`
- `plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java`
- `plugin/src/main/resources/plugin.yml`
- `CHATGPT_START_HERE.md`
- focused tests under `adapters-paper`, `adapters-sqlite`, `application`, `domain`, and `plugin`

## Persistence, state-machine, or API changes

- No migration was edited or added.
- Existing direct-delivery rows now support prepared claims that include definition ID, applied revision, codec version, and template bytes.
- `PENDING` work is claimed as `RESERVED` using a unique claim token and expiration fence.
- Safe offline/full-inventory outcomes return `RESERVED -> PENDING` with a delayed next attempt.
- Verified insertion completes through `RESERVED -> APPLIED -> VERIFIED -> COMPLETED` in one SQLite transaction with observation/current-state/audit updates.
- Ambiguous claimed states and expired `RESERVED`, `APPLIED`, or `VERIFIED` claims move to `REVIEW_REQUIRED` and clear stale queued-location certainty.
- The public Bukkit service API shape did not change; the previously implemented durable queue contract is now consumed by the Paper command and worker.

## Harsh review findings and fixes

A separate review covered the complete PR diff and the new slice for item loss, duplicate delivery, claim expiry, crash windows, full inventory, offline users, exact-slot verification, server-thread ownership, async persistence, bounded work, shutdown, transaction rollback, misleading evidence, and phase leakage.

Confirmed defects fixed before publication:

1. Recovery initially handled only one expired-claim batch at startup. More than the configured limit could remain reserved indefinitely. Recovery now runs before every bounded poll, allowing all expired claims to drain to review over successive runs.
2. Review-required delivery transitions initially left the queued current-state observation looking confirmed. Review and expired-claim paths now clear that stale queued-location certainty.

No additional confirmed item-loss, duplicate-delivery, architecture, or merge-blocking defect was found for this completed slice.

## Verification actually performed

- Java 21 strict local compilation of the changed production sources completed with `-Xlint:all -Werror` before publication.
- GitHub Actions run `30801835882`, job `91647952682`, validated the exact reviewed patch SHA-256 `cc3fdcbc6cb64d366e46de003e68baccd78abdf266fc3a4564985f0b9ab2104f` before applying it.
- In that run, `gradle --no-daemon clean check` completed successfully in 34 seconds: 34 actionable tasks, 26 executed, 8 up-to-date.
- The successful gate then committed implementation head `0ccf19507c422e547700803e5a2e5898206ff9ea` and pushed it to the active branch.
- The same run executed `gradle --no-daemon clean check` again against the committed implementation tree and completed successfully in 5 seconds: 34 actionable tasks, 16 executed, 18 from cache.
- The successful run directly exercised domain, application, SQLite, Paper/MockBukkit, plugin, and architecture test tasks.
- No live Paper/Leaf server behavior is claimed.

## Live automation observed

- Codacy refreshed at `2026-08-03T09:34:35Z`, after implementation head `0ccf19507c422e547700803e5a2e5898206ff9ea` was pushed, and reported `Up to standards`, `0 issues`, and `0 new issues`.
- CodeRabbit refreshed after the implementation push but skipped automatic review because PR #3 remains draft.
- No submitted pull-request reviews or unresolved inline review threads were present when inspected.
- The bot-authored implementation push produced an Actions run with `action_required` and no jobs; this is not claimed as exact-head CI evidence. A normal connector-authored documentation/handoff commit must trigger the final exact-head CI run for this session.

## Unresolved risks or missing evidence

- PR #3 remains draft because environmental/durability protection, void terminal loss, display-entity support, mob pickup prevention, initial audit views, and duplicate/malformed five-minute staff warnings remain unfinished.
- No live server acceptance test has been performed.
- Automatic CodeRabbit review is skipped while the PR remains draft unless explicitly triggered.
- Review-required deliveries currently require later operator/audit tooling from this phase; they are safely fenced but not yet exposed through the complete administrative audit surface.
- Exact-head CI and Codacy must be rechecked after this report, `CURRENT.md`, `INDEX.md`, and PR-body updates are committed.

## Exact next step

Resume draft PR #3 and implement the next bounded protection slice: tracked-item environmental and durability protection plus terminal void-loss handling. Preserve hidden identity and malformed/duplicate evidence, keep event access on the owning Paper thread, avoid broad world scans or chunk loading, append durable observation/audit evidence asynchronously, and add focused event-path tests.

Do not begin broad tracking/reconciliation, GUIs, editing, campaigns, deletion, or Tags integration. Item-frame/glow-frame/armor-stand support and mob pickup prevention may remain a subsequent logical slice if combining them would make the protection change too broad.

## Required prior reports

- `0020-2026-08-03-pr3-held-item-adoption.md` — preceding adoption state machine and exact-slot mutation invariants.
- `0019-2026-08-03-pr3-held-item-definition-creation.md` — definition creation and PR #3 starting state.
- `0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md` — merged foundation baseline.
- `0014-2026-08-02-pr2-codec-foundation-completion.md` — template/identity codec invariants.
- `0013-2026-08-02-pr2-transaction-helper-consolidation.md` — transaction and unit-of-work invariants.
