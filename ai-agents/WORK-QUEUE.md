# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | merged and verified; `v1.0.0-rc.1` prerelease/assets verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | PARTIAL | canonical draft PR #18 retains the lock; acceptance/release work remains resumable |
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
- Immediately preceding implementation/evidence head: `05c1d59499b6785d6bf2b665f1f3cfac808de9b4`.
- Durable checkpoint commit: `30f9fa151381ae3110e715f733f8bf96c2ca5edc`.
- Permanent checkpoint: `ai-agents/reports/agent-handoffs/2026-08-08-wp-05-ci-tracking-partial.md`.
- Owner-approved scope amendment remains in force: real Microsoft/Xbox account authentication is out of scope; server-visible Java/Floodgate identity behavior remains required.

## Current WP-05 evidence summary
Permanent predecessor PASS evidence exists for `ACC-ID-001`, `ACC-ID-002`, and `ACC-CORE-001..005`. Successful predecessor workflows additionally map to `ACC-EDIT-001..002`, `ACC-DEST-001`, and `ACC-API-001`. Those older runs are traceability until the current/final exact head reruns successfully.

The 2026-08-08 06:02–06:12 EDT worker fixed two verification defects:
- `6aa457e499341ad405438961bf4999d74c515627`: shared-container test compile/API mismatch.
- `05c1d59499b6785d6bf2b665f1f3cfac808de9b4`: tracking acceptance create-source setup plus fatal-bot cleanup hang.

No new LoreItems production defect was confirmed by that worker. The one previously confirmed WP-05 production defect (already-prefixed real Floodgate recipient names rejected by binding) remains fixed and regression-verified.

## Exact next action
Resume WP-05 from the live canonical PR head. Inspect exact-head workflows first; repair any confirmed failure on the same branch. Once current identity/core/editor/tracking/exact-remove/API gates are green, record exact-head credit and build the next consolidated disposable-Paper acceptance block for `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Continue `ACC-EDIT-003`, protection, distribution, environment, backup/recovery/load cases, then rerun all 35 on the exact final JAR and finish review, sign-off, normal merge, post-merge verification, and `v1.0.0` release. Do not begin WP-06.
