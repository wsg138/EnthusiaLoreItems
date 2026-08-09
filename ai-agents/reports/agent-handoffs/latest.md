# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact observed branch head resumed: `f68e7fef1d60a188034ccacbf73c07580da1e167`
- Prior implementation/evidence head: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`
- Permanent resume checkpoint: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-resume-tracking-coordinate.md`

## Live reconciliation
PR #18 remains the sole unfinished canonical package lock. WP-06 has no primary Tags branch and no LoreItems finalization/API-blocker branch. Main-side READY snapshots are stale and are outranked by the live branch/PR. PR #18 currently has no submitted reviews and zero unresolved inline review threads.

## Current exact-head evidence
For `f68e7fef1d60a188034ccacbf73c07580da1e167`:
- CI `31287238034` — success.
- Floodgate Identity `31287238055` — success.
- Java Identity/Core `31287238071` — success.
- ACC-CORE-005 Full Inventory `31287238058` — success.
- Editor Contract `31287238060` — success.
- Exact Removal `31287238045` — success.
- Public API `31287238052` — success.
- Tracking Contract `31287238063` — failure.
- CodeRabbit combined status — success.

## Findings retained
Two confirmed production defects have been fixed/regression-verified in WP-05: prefixed Floodgate recipient binding, and the quit/inventory-close tracking race. The current Tracking Contract failure is harness-only: the workflow derives container Y from Mineflayer's fractional post-teleport position and queries Y=69 for a chest placed at Y=70.

## Remaining work
All 35 cases must ultimately PASS on the exact final JAR after the final code change. Remaining or not-yet-final-head areas include `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-TRACK-002..003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`, followed by complete final-head automated/release/review/sign-off/merge/post-merge/release gates.

## Blocker
None verified. WP-05 is active and resumable; WP-06 remains `BLOCKED`.

## Exact next action
Repair `.github/workflows/wp05-tracking-contract-acceptance.yml` to use fixed absolute world coordinates for the generated containers: Ender Chest `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`. Preserve ordinary-player interactions and acceptance assertions. Rerun `ACC-TRACK-001..003`, persist exact-head evidence, then continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` block. Do not begin WP-06.
