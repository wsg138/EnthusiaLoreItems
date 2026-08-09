# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Refreshed live `main`: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Exact implementation/evidence head being resumed: `dcf9578e0be281ccb3d6f90830d6bd9b31fb4359`
- Current resume checkpoint: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-resume-live-tracking.md`

## Live reconciliation
PR #18 remains the sole unfinished canonical package lock. Its head was stable at `dcf9578...` across reconciliation and the pre-claim re-fetch. WP-06 has no primary, finalization, or API-blocker lock and remains `BLOCKED`. PR #18 is open, draft, mergeable, has no submitted reviews, and has zero unresolved inline review threads at this checkpoint.

## Current exact-head evidence
On `dcf9578...`:
- Public API `31339822613` — success.
- Exact Removal `31339822644` — success.
- Editor Contract `31339822621` — success.
- Java Identity/Core `31339822625` — success.
- ACC-CORE-005 Full Inventory `31339822651` — success.
- Floodgate Identity `31339822638` — success.
- Tracking Contract `31339822658` — failure after explicit PASS evidence for `ACC-TRACK-001`, allowed-mode/lifecycle `ACC-TRACK-002`, and ordinary item-frame/glow-frame/armor-stand placement/observation for `ACC-TRACK-003`.
- Remaining tracking failure: the three display-holder observations stayed `CONFIRMED_NOW` during the intended natural-unload wait instead of becoming `LAST_CONFIRMED`; restricted `shared-containers-allowed: false` coverage was skipped after that failure.
- CI `31339822635` — failure at the required independent exact-head Codacy/review-marker gate; do not bypass it.

## Production findings
Two confirmed WP-05 production defects remain fixed/regression-verified: prefixed Floodgate recipient binding and the quit/InventoryClose tracking race. The current display-unload failure is not yet classified as a production defect because the harness must first prove that the intended fixture chunk actually unloaded.

## Remaining boundary
All 35 acceptance cases must ultimately PASS on the exact final JAR after the final code change. Tracking still requires display-holder natural unload/reload lifecycle evidence and restricted shulker/bundle rejection evidence. The remaining WP-05 manual matrix, exact-head WP-04 automated gates, final `1.0.0` packaging, upgrade/backup/restore/rollback rehearsal, independent code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge verification, and verified production release also remain.

## Blocker
None verified. WP-05 is actionable.

## Exact next action
Inspect the current tracking harness and LoreItems chunk/display observation paths against Tracking run `31339822658`. Prove whether the fixture chunk unloads naturally, fix the owning component without weakening the criterion, rerun `ACC-TRACK-001..003`, and persist exact-head evidence. Do not begin WP-06.
