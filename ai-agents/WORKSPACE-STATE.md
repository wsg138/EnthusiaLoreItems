# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact observed branch head resumed: `f68e7fef1d60a188034ccacbf73c07580da1e167`
- Prior implementation/evidence head: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`
- Permanent resume checkpoint: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-resume-tracking-coordinate.md`
- Dependency satisfied by verified WP-04 RC `v1.0.0-rc.1`.
- WP-06 remains blocked until verified WP-05 production `v1.0.0` release.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | IN_PROGRESS | canonical draft PR #18 is actively resumed from exact observed head `f68e7fe...` |
| WP-06 | 10% | BLOCKED | requires verified WP-05 production release |

- Packages complete: 4/6.
- Weighted completed progress: 75%.
- No package weight is awarded to WP-05 until complete.

## Owner-approved WP-05 scope amendment
Real Microsoft/Xbox account authentication is out of scope. Server-visible Java/Floodgate identity behavior remains required, including real `*`-prefixed names, UUID/name behavior, cached-offline/never-joined resolution, commands/GUI, delivery, audit, API and distribution behavior.

## Confirmed production findings
1. Fixed/regression-verified: valid already-prefixed Floodgate recipient names were rejected by recipient binding. Fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
2. Fixed/regression-verified: a trailing inventory-close observation after quit re-promoted a disconnected player's inventory from `LAST_CONFIRMED` to `CONFIRMED_NOW`. Production fix `1d144111d88a1c481e231bd1ba329c58a0fddc20`; automated regression `39e4892562bc441d90c046c79b84d1a1004a2034`; live `ACC-TRACK-001` PASS on predecessor implementation head `e73ddc9d...`.

## Current resume evidence
For exact observed branch head `f68e7fef1d60a188034ccacbf73c07580da1e167`:
- CI `31287238034` — success.
- Floodgate Identity `31287238055` — success.
- Java Identity/Core `31287238071` — success.
- ACC-CORE-005 Full Inventory `31287238058` — success.
- Editor Contract `31287238060` — success.
- Exact Removal `31287238045` — success.
- Public API `31287238052` — success.
- Tracking Contract `31287238063` — failure.
- CodeRabbit combined status — success.
- PR #18 has no submitted reviews and zero unresolved inline review threads at resume.

The Tracking Contract failure is harness-only: the workflow derives block Y from Mineflayer's fractional post-teleport position and queries Y=69 for a chest created at Y=70. Use the fixed absolute setup coordinates: Ender Chest `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`.

## Remaining acceptance criteria
All 35 cases must ultimately PASS against the exact final JAR. Incomplete or not-yet-final-head areas include at least:
- `ACC-ENV-001`
- `ACC-EDIT-003`
- `ACC-TRACK-002..003`
- `ACC-PROT-001..002`
- `ACC-ANOM-001..002`
- `ACC-DEST-002..004`
- `ACC-DIST-001..005`
- `ACC-LIFE-001..002`
- `ACC-OPS-001..005`

All already-covered cases must rerun after the final code change. Package-level gates also require complete exact-head WP-04 automated verification, final `1.0.0` version/release artifacts and evidence, RC-to-final upgrade, backup/restore and rollback rehearsal, independent harsh code review and separate evidence audit, owner/operator sign-off, normal merge commit, post-merge `main` verification, and verified production release.

## Blocker
None verified. WP-05 is actively resumed and remains the sole unfinished canonical package. WP-06 remains `BLOCKED`.

## Exact next action
Repair `.github/workflows/wp05-tracking-contract-acceptance.yml` so generated container lookup uses the fixed absolute coordinates instead of Mineflayer's fractional post-teleport Y. Preserve ordinary-player interactions and all acceptance assertions. Rerun Tracking Contract; if `ACC-TRACK-001..003` pass, persist exact-head evidence and continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` acceptance block. Do not begin WP-06.
