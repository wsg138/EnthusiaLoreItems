# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | merged and verified; `v1.0.0-rc.1` prerelease/assets verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | PARTIAL | canonical draft PR #18 retains the lock; tracking race fix is live-regression verified, matrix/release gates remain |
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
- Intentional-stop status: `PARTIAL`.
- Exact implementation/evidence head: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`.
- Permanent checkpoint: `ai-agents/reports/agent-handoffs/2026-08-08-wp-05-tracking-race-live-regression-partial.md`.
- Owner-approved scope amendment remains in force: real Microsoft/Xbox authentication is out of scope; server-visible Java/Floodgate identity behavior remains required.

## Current WP-05 evidence summary
Two production defects have been confirmed in WP-05 and both are fixed/regression-verified:
1. valid already-prefixed Floodgate recipient names rejected by binding — fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`;
2. trailing InventoryClose after PlayerQuit re-promoted disconnected inventory from `LAST_CONFIRMED` to `CONFIRMED_NOW` — fix `1d144111d88a1c481e231bd1ba329c58a0fddc20`, automated regression `39e4892562bc441d90c046c79b84d1a1004a2034`, live Paper `ACC-TRACK-001` PASS in run `31287048615` on `e73ddc9d...`.

Exact-head successful workflows on `e73ddc9d...` include Java Identity/Core `31287048544`, ACC-CORE-005 `31287048549`, Editor `31287048547`, Exact Removal `31287048551`, and Public API `31287048546`. Tracking run `31287048615` explicitly PASSed ACC-TRACK-001, then failed later from a harness-only ACC-TRACK-002 coordinate bug. CI `31287048568` passed Verify/tooling/complexity but stopped at the required independent Codacy review-marker gate. Floodgate run `31287048553` was last observed still executing ACC-ID-002; no success inferred.

## Exact next action
Resume WP-05 from the live canonical PR head. Reconcile branch movement and the final Floodgate result, then repair Tracking Contract container lookup to use its fixed absolute world coordinates instead of deriving Y from Mineflayer's fractional post-teleport position. Rerun TRACK-001..003 without weakening assertions. If green, persist exact-head tracking credit and build the next consolidated disposable-Paper acceptance block for `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Continue the remaining matrix and release gates. Do not begin WP-06.
