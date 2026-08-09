# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact implementation/evidence head checkpointed: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`
- Permanent checkpoint: `ai-agents/reports/agent-handoffs/2026-08-08-wp-05-tracking-race-live-regression-partial.md`

## What this worker completed
- Reconciled live GitHub and resumed the sole canonical WP-05 lock; WP-06 was not started.
- Published the required GitHub-backed resume checkpoint before implementation work.
- Confirmed a production tracking race from live run `31279868113`: quit wrote `LAST_CONFIRMED`, then a trailing inventory-close observation incorrectly restored `CONFIRMED_NOW` after disconnect.
- Fixed the production race in `1d144111d88a1c481e231bd1ba329c58a0fddc20` with a one-tick quitting-player guard.
- Added automated regression `trailingInventoryCloseAfterQuitDoesNotRestoreLiveConfirmation` in `39e4892562bc441d90c046c79b84d1a1004a2034`.
- Verified the repository CI `Verify`, repository tooling, and new-code complexity stages pass with the fix. The exact-head Codacy stage remains gated by the required independent `[wp04-code-quality]` review marker; no marker was fabricated or bypassed.
- Repaired several deterministic tracking-harness transport issues without weakening acceptance assertions: teleport/chunk synchronization, exact block lookup, and clear access space above generated test containers.
- Obtained live Paper regression evidence on `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`: run `31287048615` explicitly PASSed `ACC-TRACK-001`, including ordinary offhand/armor/cursor/Ender Chest movement, quit `LAST_CONFIRMED`, and reconnect `CONFIRMED_NOW` continuity for the same single instance.

## Current exact-head evidence state
For exact implementation/evidence head `e73ddc9d...`:
- Java Identity/Core `31287048544` — success.
- ACC-CORE-005 Full Inventory `31287048549` — success.
- Editor Contract `31287048547` — success.
- Exact Removal `31287048551` — success.
- Public API `31287048546` — success.
- Tracking Contract `31287048615` — workflow failure after explicit ACC-TRACK-001 PASS. ACC-TRACK-002 later failed from a test-coordinate calculation, not a LoreItems assertion.
- CI `31287048568` — Verify/test reports/tooling/complexity success; exact-head Codacy failed at the required independent review-marker gate, so downstream WP-04 release stages were skipped.
- Floodgate Identity `31287048553` — last observed in progress in ACC-ID-002 after successful environment/build setup. Subsequent metadata commits may cancel/obsolete that exact-head run; do not infer PASS.

Because metadata commits follow the implementation/evidence head, these runs are traceability for `e73ddc9d...`; all 35 cases must still rerun after the final code change against the exact final JAR.

## Current remaining tracking harness defect
Run `31287048615` showed the next failure precisely. The bot teleported to the chest test area and settled at Y `70.92`. The workflow then used `bot.entity.position.floored().offset(1,-1,0)`, so it queried `(11,69,0)` even though `/setblock` had successfully placed the chest at `(11,70,0)`. Evidence reported `observed=air` at Y=69. This is harness-only.

The next worker should replace relative container lookup with fixed absolute world coordinates already controlled by the workflow: Ender Chest `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`. Preserve the existing ordinary-player interactions and all acceptance assertions.

## Findings
- Confirmed production defects in WP-05: 2 found, 2 fixed/regression-verified.
- Defect 1: valid already-prefixed Floodgate recipient names rejected by binding — fixed previously.
- Defect 2: trailing inventory close after quit re-promoted stale inventory to current confirmation — fixed and live-regressed this worker.
- PR #18 had no submitted reviews and zero unresolved inline review threads when inspected during this worker session.

## Remaining work
All 35 cases must ultimately PASS on the exact final JAR. Incomplete or not-yet-final-head areas include at least `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-TRACK-002..003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`. All already-covered cases must be repeated on the final head after the last code change.

Final WP-04 verification, final `1.0.0` release artifacts/evidence, RC-to-final upgrade, backup/restore/rollback rehearsal, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge main verification, and verified production `v1.0.0` release remain required.

## Routing
WP-05 is `PARTIAL`, not complete and not replaced by another package. Resume this same canonical branch/PR before any other package. WP-06 remains `BLOCKED`.

## Exact next action
Reconcile the live PR head and current workflows. Repair `.github/workflows/wp05-tracking-contract-acceptance.yml` to query fixed absolute block coordinates rather than deriving container Y from Mineflayer's fractional post-teleport position. Rerun Tracking Contract; if `ACC-TRACK-001..003` all pass, persist the evidence and continue the consolidated acceptance block for `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Do not begin WP-06.
