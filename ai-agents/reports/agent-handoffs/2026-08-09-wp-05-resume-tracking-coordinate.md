# WP-05 resume checkpoint — tracking coordinate repair

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18 — `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact observed branch head being resumed: `f68e7fef1d60a188034ccacbf73c07580da1e167`
- Exact implementation/evidence predecessor retained by the prior checkpoint: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`

## Startup reconciliation
- Live `main` remains `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- PR #18 is open, draft, mergeable, and is the sole unfinished canonical package lock.
- WP-01 through WP-04 are recorded complete; their canonical branches are historical. No WP-06 primary, finalization, or API-blocker branch exists.
- Main-side queue/state snapshots are stale and still describe WP-05 as READY; the live canonical PR/branch outranks those snapshots.
- PR #18 has no submitted reviews and zero unresolved inline review threads at this checkpoint.
- Combined status on `f68e7fef1d60a188034ccacbf73c07580da1e167`: CodeRabbit `success`.

## Exact-head workflow results observed before resume
For `f68e7fef1d60a188034ccacbf73c07580da1e167`:
- CI run `31287238034` — `completed/success`.
- Floodgate Identity run `31287238055` — `completed/success`.
- Java Identity/Core run `31287238071` — `completed/success`.
- ACC-CORE-005 Full Inventory run `31287238058` — `completed/success`.
- Editor Contract run `31287238060` — `completed/success`.
- Exact Removal run `31287238045` — `completed/success`.
- Public API run `31287238052` — `completed/success`.
- Tracking Contract run `31287238063` — `completed/failure`.

## Completed acceptance criteria retained
- Two confirmed WP-05 production defects are fixed and regression-verified: prefixed Floodgate recipient binding and the quit/inventory-close tracking race.
- `ACC-TRACK-001` previously passed live on the implementation/evidence predecessor `e73ddc9d...` after the production tracking fix.
- Current-head CI and the existing identity/core/editor/exact-removal/API acceptance workflows are successful, subject to the package rule that all 35 cases must rerun on the exact final JAR after the last code change.

## Remaining acceptance criteria
- Repair and rerun `ACC-TRACK-001..003`; current Tracking Contract failure is a diagnosed harness-only coordinate lookup defect.
- Complete the remaining final-head matrix, including `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`.
- Repeat all 35 cases on the exact final JAR after the last code change.
- Complete exact-head WP-04 automated gates, final `1.0.0` packaging/release evidence, upgrade/backup/restore/rollback rehearsal, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge main verification, and verified production `v1.0.0` release.

## Tests run by this resume worker
No new implementation test has been run yet. Startup evidence reconciliation only; exact workflow results are listed above.

## Known findings
- Current Tracking Contract failure is harness-only: container lookup derives Y from Mineflayer's fractional post-teleport position and queries Y=69 for a chest placed at Y=70.
- The workflow must use the fixed absolute coordinates already controlled by setup: Ender Chest `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`.
- No new production defect is confirmed by this startup reconciliation.

## Blocker
None verified. The package is resumable. WP-06 remains `BLOCKED`.

## Exact next action
Modify `.github/workflows/wp05-tracking-contract-acceptance.yml` on this same branch so the acceptance harness queries the fixed absolute container coordinates without weakening ordinary-player interactions or assertions. Rerun Tracking Contract and persist exact-head evidence. If `ACC-TRACK-001..003` pass, continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` acceptance block. Do not begin WP-06.
