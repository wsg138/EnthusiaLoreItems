# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | PARTIAL | canonical draft PR #18 remains the sole unfinished package lock; implementation/evidence head `50633f1...` |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | requires verified WP-05 production `v1.0.0` release |

## Progress
- Completed: 4/6 packages.
- Remaining: 2/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no weight until the complete package contract, merge, post-merge verification, and production release are verified.

## WP-05 live lock
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Status: `PARTIAL`.
- Exact implementation/evidence head: `50633f1256aa2189f70219b7ebcca4a740e7acb0`.
- Permanent handoff: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-tracking-lifecycle-progress-partial.md`.
- Owner-approved scope amendment remains in force: real Microsoft/Xbox authentication is out of scope; server-visible Java/Floodgate identity behavior remains required.

## Current WP-05 evidence summary
Two production defects remain fixed/regression-verified: prefixed Floodgate recipient binding and the quit/inventory-close tracking race. No new production defect was confirmed in the current session.

Exact-head tracking run `31303340890` produced artifact `9035204924` with digest `sha256:b877c96ba230c4bb5d7386d8d9cf2996a0b1d9f62e614cabd947b93a84b78cdb`. It explicitly PASSed `ACC-TRACK-001` and the allowed-mode/lifecycle portion of `ACC-TRACK-002`. The workflow then failed in Track 003 because the test attempted frame placement before the naturally dropped tracked item had returned to player storage. The restricted `shared-containers-allowed: false` half of Track 002 is also still outstanding.

On the same head, Public API `31303340903`, Exact Removal `31303340925`, Editor `31303340916`, Java Identity/Core `31303340947`, and ACC-CORE-005 `31303340907` succeeded. Floodgate `31303340901` failed on an external dependency HTTP 403 before product behavior. CI `31303340889` passed Verify/tooling/complexity but stopped at the required independent exact-head Codacy/review-marker gate.

## Remaining boundary
All 35 acceptance cases must pass on the exact final JAR after the last code change. Final exact-head automated/release gates, upgrade/backup/restore/rollback rehearsal, independent code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge verification, and verified `v1.0.0` release remain required.

## Exact next action
Resume PR #18. Finish the tracking contract by proving deterministic natural pickup before display placement, natural unload/reload for frame/glow-frame/armor-stand holders, and ordinary-player shulker/bundle rejection with `shared-containers-allowed: false`. Rerun all `ACC-TRACK-001..003` on one exact head and persist evidence. If green, continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` acceptance block. Do not begin WP-06.
