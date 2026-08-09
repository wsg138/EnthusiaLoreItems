# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | merged and verified; `v1.0.0-rc.1` prerelease/assets verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | IN_PROGRESS | canonical draft PR #18 actively resumed from exact observed head `f68e7fef...` |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | requires verified WP-05 production `v1.0.0` release |

## Progress
- Completed: 4/6 packages.
- Remaining: 2/6 packages.
- Weighted completed progress: 75%.
- No WP-05 weight is awarded until the complete package contract, merge, post-merge verification, and production release are verified.

## WP-05 live lock
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Active status: `IN_PROGRESS`.
- Exact observed branch head resumed: `f68e7fef1d60a188034ccacbf73c07580da1e167`.
- Prior implementation/evidence head: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`.
- Permanent resume checkpoint: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-resume-tracking-coordinate.md`.
- Owner-approved scope amendment remains in force: real Microsoft/Xbox authentication is out of scope; server-visible Java/Floodgate identity behavior remains required.

## Current WP-05 evidence summary
Two production defects have been confirmed and fixed/regression-verified: prefixed Floodgate recipient binding and the trailing inventory-close-after-quit tracking race.

For current observed head `f68e7fef...`, CI `31287238034`, Floodgate Identity `31287238055`, Java Identity/Core `31287238071`, ACC-CORE-005 `31287238058`, Editor `31287238060`, Exact Removal `31287238045`, and Public API `31287238052` all completed successfully. Tracking Contract `31287238063` failed because the harness derives a container Y coordinate from Mineflayer's fractional post-teleport position and queries Y=69 for a block placed at Y=70. CodeRabbit combined status is successful; PR #18 has no submitted reviews and zero unresolved review threads at resume.

## Remaining boundary
All 35 acceptance cases must pass on the exact final JAR after the last code change. Final exact-head automated/release gates, upgrade/backup/restore/rollback rehearsal, independent code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge verification, and verified `v1.0.0` release remain required.

## Exact next action
Repair Tracking Contract to use fixed absolute setup coordinates: Ender Chest `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`. Rerun `ACC-TRACK-001..003` without weakening assertions, persist exact-head evidence, then continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` acceptance block. Do not begin WP-06.
