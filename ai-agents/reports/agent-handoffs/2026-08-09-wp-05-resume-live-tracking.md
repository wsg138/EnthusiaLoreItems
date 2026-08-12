# WP-05 resume checkpoint — live tracking continuation

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Refreshed live `main`: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Exact resume base / implementation-evidence head being checkpointed: `dcf9578e0be281ccb3d6f90830d6bd9b31fb4359`
- Claim basis: the canonical branch remained exactly at `dcf9578...` across reconciliation and the pre-claim re-fetch; PR #18 is the sole open package PR; no WP-06 lock exists.

## Completed acceptance evidence at the resume base
Current-head workflow evidence on `dcf9578...`:
- Public API run `31339822613` — success.
- Exact Removal run `31339822644` — success.
- Editor Contract run `31339822621` — success.
- Java Identity/Core run `31339822625` — success.
- ACC-CORE-005 Full Inventory run `31339822651` — success.
- Floodgate Identity run `31339822638` — success; the earlier external HTTP-403 setup failure is no longer current-head evidence.
- Tracking Contract run `31339822658` — failure, with explicit partial PASS evidence:
  - PASS `ACC-TRACK-001`.
  - PASS allowed-mode/lifecycle portion of `ACC-TRACK-002`.
  - PASS ordinary client placement/observation portion of `ACC-TRACK-003` for item frame, glow item frame, and armor stand.
  - FAIL remaining `ACC-TRACK-003` lifecycle assertion: all three display-holder states remained `CONFIRMED_NOW` during the intended natural-unload wait instead of becoming `LAST_CONFIRMED`.
  - The restricted `shared-containers-allowed: false` phase of `ACC-TRACK-002` was skipped because the preceding tracking phase failed.
- CI run `31339822635` — failure at the required independent exact-head Codacy/review-marker gate; this gate remains required and is not bypassed.
- PR #18 has no submitted reviews and zero unresolved inline review threads at claim time.

## Production findings
Two previously confirmed WP-05 production defects remain fixed/regression-verified: already-prefixed Floodgate recipient binding and the quit/InventoryClose tracking race. No new production defect is yet confirmed by the current tracking failure; the live evidence first requires distinguishing a harness chunk-unload failure from a plugin display-lifecycle failure.

## Remaining acceptance criteria
All WP-05 criteria remain indivisible. Immediate tracking remainder:
1. Diagnose whether the display fixture chunk actually unloads naturally on `dcf9578...`.
2. If the harness prevents unload, correct only the acceptance harness and rerun.
3. If the chunk unloads but LoreItems leaves display-holder observations `CONFIRMED_NOW`, treat that as a product defect, fix it in WP-05, add regression coverage, and rerun affected safety cases.
4. Complete ordinary-player `shared-containers-allowed: false` shulker/bundle rejection evidence.
5. Obtain one exact-head PASS for `ACC-TRACK-001..003`, then persist evidence and continue the remaining in-package acceptance matrix.

Beyond tracking, all 35 final cases must PASS on the exact final JAR after the final code change, followed by the complete automated/review/evidence/sign-off/merge/post-merge/release gates from the package contract.

## Known findings / blocker
- Known current failure: display-holder unload lifecycle did not transition to `LAST_CONFIRMED` in Tracking run `31339822658`.
- Blocker: none verified. WP-05 remains actionable.

## Exact next action
Inspect the current tracking harness and LoreItems chunk/display observation paths against run `31339822658`. Prove whether the intended fixture chunk unload event occurs, fix the owning component without weakening the acceptance criterion, and rerun the exact tracking contract. Do not begin WP-06.
