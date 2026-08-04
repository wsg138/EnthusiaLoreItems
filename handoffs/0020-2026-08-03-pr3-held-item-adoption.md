# Handoff 0020 — PR #3 held-item adoption

## Session metadata

- Date: 2026-08-03 (America/Indiana/Indianapolis)
- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Reported implementation head: `d4f4df37c67ef21f10c03dd626ae362cc57273ed`
- Session status: held-item adoption logical item complete; broader PR phase remains in progress

## Objective

Resume draft PR #3 and complete exactly one bounded logical item: adopt an administrator's currently held item into an existing active lore definition without item loss, duplicate identity creation, main-thread violations, blind retries, or scope expansion into direct delivery or protection.

## Reconciled starting state

Live GitHub state took priority over the prior handoff:

- `main` was `62268c9197e28ff690fda095a4878aa0f0556721`, the verified merge commit for Foundation PR #2;
- PR #3 was the only relevant unfinished pull request and remained open, draft, and mergeable;
- its starting head was `56cad83421f0a1062f2ab7c219257e54c0bbb79e`;
- no submitted reviews or unresolved inline review threads existed;
- exact-head Actions and Codacy were green for the preceding held-item definition-creation slice;
- no newer merge had made report 0019 stale.

The required reports and governing documents were read before implementation: `CHATGPT_START_HERE.md`, `handoffs/CURRENT.md`, reports 0019, 0018, 0014, and 0013, `REQUIREMENTS.md`, `docs/architecture.md`, and `docs/implementation-plan.md`.

## Work completed

### Application adoption protocol

Added an explicit prepare/apply/finalize protocol:

- `PrepareHeldItemAdoptionRequest` binds the definition key, administrator UUID, exact selected hotbar slot, and lowercase SHA-256 fingerprint of the unmodified held item;
- `PersistingAdoptHeldItemUseCase` creates fresh mutation, instance, and claim identities with a bounded lease;
- `HeldItemAdoptionStore` exposes durable preparation, verified completion, and explicit review-required finalization;
- `PreparedHeldItemAdoption` carries only immutable scalar/domain data across the asynchronous database boundary;
- unknown definitions fail before physical mutation, while storage unavailability fails closed.

### Durable SQLite intent and finalization

Added `SQLiteHeldItemAdoptionStore` with transactional state changes:

1. Resolve the active definition and current revision.
2. Insert a fresh active instance using that revision.
3. Insert `MISSING_UNRESOLVED` current state at revision zero.
4. Insert an `ADOPT_HELD_ITEM` pending mutation directly in `CLAIMED` with a unique token, expiry, and attempt count one.
5. Append `held_item_adoption_prepared` audit evidence.

After exact-slot Paper verification, one transaction:

1. transitions `CLAIMED -> APPLIED`;
2. writes a confirmed player-inventory observation with `hotbar:<slot>` container path;
3. advances current state from missing revision zero to confirmed revision one;
4. transitions `APPLIED -> VERIFIED -> COMPLETED` and clears the claim;
5. appends `held_item_adopted` audit evidence.

Changed-slot, malformed, expired-claim, persistence, shutdown, or otherwise ambiguous outcomes enter `REVIEW_REQUIRED` with audit evidence instead of retrying or guessing.

### Paper exact-slot mutation

Added `PaperHeldItemAdoptionOperator` and `/loreitems adopt <lookup-key>`:

- command is player-only and permission-gated by `enthusia.loreitems.admin.adopt`;
- the exact selected hotbar slot is snapshotted on the Paper server thread;
- air, stacks larger than one, already tracked items, and malformed lore identity are rejected without mutation;
- a SHA-256 fingerprint of `ItemStack.serializeAsBytes()` protects the prepared physical state;
- after durable preparation, the player, selected slot, item validity, and fingerprint are checked again;
- the existing identity codec writes a fresh hidden definition/instance/revision identity to a clone, preserving visible appearance and foreign data while forcing amount and maximum stack size one;
- the exact slot is replaced and immediately reread to verify the expected identity before durable completion;
- no live Bukkit object crosses the asynchronous database boundary.

### Lifecycle and bounded work

- Plugin startup now performs the existing bounded expired-mutation recovery so abandoned adoption claims move to review.
- Adoption requests are globally bounded and limited to one in-flight operation per player.
- Startup, degraded storage, shutdown, and callback scheduling failures fail closed.
- No direct-delivery execution, offline/full-inventory recovery, environmental protection, durability protection, display-entity support, mob pickup prevention, broad tracking/reconciliation, GUI, editing, campaign, deletion, or Tags functionality was added.

## Important decisions and invariants

- Durable intent and a fresh instance identity exist before inventory mutation.
- Physical mutation is limited to the exact prepared hotbar slot.
- The held item's visible appearance and unrelated persistent data remain unchanged by default.
- A tracked identity is never assigned to a stack larger than one.
- Verification failure never triggers a blind duplicate write or automatic physical rollback.
- Ambiguous post-write outcomes are retained for staff review.
- SQLite work remains on the bounded database executor; Paper inventory access remains on the server thread.
- The inherited pending-mutation model stores `desiredRevision` as `Integer`; adoption uses `Math.toIntExact` consistently rather than changing that cross-phase persistence contract here.

## Files or modules changed

Application:

- `AdoptHeldItemUseCase`
- `HeldItemAdoptionPreparation`
- `HeldItemAdoptionStore`
- `PersistingAdoptHeldItemUseCase`
- `PrepareHeldItemAdoptionRequest`
- `PrepareHeldItemAdoptionResult`
- `PreparedHeldItemAdoption`
- focused application tests

SQLite:

- `SQLiteHeldItemAdoptionStore`
- `SQLiteUnitOfWork` exposure
- focused transaction and unit-of-work tests

Paper/plugin:

- `PaperHeldItemAdoptionOperator`
- `AdoptHeldItemCommandExecutor`
- command router integration
- plugin lifecycle wiring
- `plugin.yml` command/permission metadata
- MockBukkit tests

Documentation:

- `docs/development.md`
- this immutable handoff and the mutable current/index pointers

## Verification actually performed

- Java 21 strict compilation of the relevant domain, application, and SQLite main sources with `javac --release 21 -Xlint:all -Werror`: passed before repository upload.
- Local transfer-tree `git diff --check`: passed.
- Exact implementation head `237c151ce84797822a7526805fb1a7240ffd501e`:
  - GitHub Actions run `30795228671`, job `91627166104`;
  - `gradle --no-daemon clean check`;
  - `BUILD SUCCESSFUL in 29s`;
  - 34 actionable tasks: 23 executed, 3 from cache, 8 up-to-date.
- Codacy exposed six medium maintainability findings on the adoption code. A temporary repository-scoped check-annotation exporter retrieved all six exact annotations in run `30795768056`, job `91628873608`, artifact `8848805391`.
- All six were fixed:
  - item amount/max-stack literals replaced by a named constant;
  - MockBukkit `ServerMock` test field made local;
  - repeated mutation-state literals named;
  - JSON control-character boundary named;
  - epoch-zero validation literals named in both adoption records.
- One-use remediation run `30796057769`, job `91629783279`, applied those exact fixes, removed its diagnostic workflow, and ran `gradle --no-daemon clean check` before committing implementation head `d4f4df37c67ef21f10c03dd626ae362cc57273ed`:
  - `BUILD SUCCESSFUL in 33s`;
  - 34 actionable tasks: 23 executed, 3 from cache, 8 up-to-date.
- The final PR changed-file list after cleanup contained 33 intended phase files and no transfer archive or temporary workflow.

## Harsh review findings and fixes

A separate harsh review covered the complete PR #3 diff, not only the new files, with emphasis on item loss, duplicate creation, main-thread blocking, unbounded work, reload/shutdown ambiguity, transaction integrity, claim expiry, stale exact-slot writes, malformed identity, and phase leakage.

Confirmed findings:

- six Codacy maintainability findings described above; all were fixed and reverified.

No additional confirmed behavioral defect or merge blocker was found in the completed creation and adoption slices. Conservative review-required outcomes remain intentional because guessing or blind repair would create a greater duplicate/item-loss risk.

## Live automation observed

- CodeRabbit continued to skip automatic review because PR #3 is draft.
- No submitted human review or unresolved inline thread existed at the implementation head.
- A Codacy summary briefly included one critical token-pattern finding from the temporary annotation-export workflow plus the six older findings. That workflow was removed in `d4f4df37c67ef21f10c03dd626ae362cc57273ed`; the critical report was diagnostic residue, not a credential committed in the retained PR tree.
- The final documentation head still requires fresh exact-head Actions and Codacy confirmation after this report, `CURRENT.md`, and `INDEX.md` are committed. Future agents must use that later live evidence rather than treating this report as proof of a future check result.

## Unresolved risks or missing evidence

- PR #3 remains draft because the broader Implementation PR 2 phase is incomplete.
- No live Paper/Leaf server behavior was tested or claimed.
- Final exact-head Actions and Codacy evidence for the documentation-complete head must be checked live after the handoff commits.
- Direct delivery and recovery, protection, supported display entities, mob pickup prevention, initial audit browsing, duplicate/malformed warnings, and the rest of Implementation PR 2 remain unfinished.

## Exact next step

Resume draft PR #3 and implement durable direct-delivery execution and recovery as the next complete logical slice:

- consume the existing direct-delivery intent model for self, online, offline, and full-inventory recipients;
- claim work durably before creating or inserting a tracked physical item;
- create one fresh instance identity per accepted delivery;
- perform and verify Paper inventory mutation on the server thread;
- leave offline/full-inventory work durably queued;
- resume safely on join and restart;
- route ambiguous outcomes to review rather than duplicating delivery.

Do not begin environmental/durability protection, void loss handling, display entities, mob pickup prevention, broad reconciliation, GUIs, editing, campaigns, deletion, or Tags integration in the same chat unless that direct-delivery slice is fully complete and a new logical item is explicitly started.

## Required prior reports

- `0019-2026-08-03-pr3-held-item-definition-creation.md`
- `0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`
- `0014-2026-08-02-pr2-codec-foundation-completion.md`
- `0013-2026-08-02-pr2-transaction-helper-consolidation.md`
