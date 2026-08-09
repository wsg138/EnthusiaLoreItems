# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact resumed predecessor head: `01c25d0fd3d96438b57d7c89a5308046c023495b`
- Dependency satisfied by verified WP-04 RC `v1.0.0-rc.1`.
- WP-06 remains blocked until the verified WP-05 production `v1.0.0` release.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | IN_PROGRESS | canonical draft PR #18 is actively resumed |
| WP-06 | 10% | BLOCKED | requires verified WP-05 production release |

- Packages complete: 4/6.
- Weighted completed progress: 75%.
- No package weight is awarded to WP-05 until complete.

## Owner-approved WP-05 scope amendment
Real Microsoft/Xbox account authentication is out of scope. Server-visible Java/Floodgate identity behavior remains required, including real `*`-prefixed names, UUID/name behavior, cached-offline/never-joined resolution, commands/GUI, delivery, audit, API and distribution behavior.

## Confirmed production findings
1. Fixed/regression-verified: valid already-prefixed Floodgate recipient names were rejected by recipient binding. Fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`; static cleanup `ed52a32688329be931bc6fdfc5008b393a0f2ffb`; historical regression run `31222017554` PASS.
2. Current defect: exact-head tracking run `31279868113` on `01c25d0fd3d96438b57d7c89a5308046c023495b` records `LAST_CONFIRMED` on `player-quit-unique`, then a later `inventory-close-unique` overwrites the same tracked inventory state to `CONFIRMED_NOW` after the player has disconnected.

## Acceptance evidence
Permanent predecessor PASS evidence exists for `ACC-ID-001`, `ACC-ID-002`, `ACC-CORE-001..005`, with traceability for `ACC-EDIT-001..002`, `ACC-DEST-001`, and `ACC-API-001`. Older runs are not exact-final-head evidence.

The latest tracking run reached real Paper acceptance and failed `ACC-TRACK-001` on the quit/close ordering defect above. Its preceding build/setup stage passed. No acceptance assertion was weakened.

## Remaining acceptance criteria
All 35 cases must ultimately PASS against the exact final JAR after the last code change. Incomplete areas include at least `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`; existing identity/core/editor/tracking/exact-remove/API coverage must also rerun on the final head.

Package-level gates still require the full exact-head WP-04 verification suite, final `1.0.0` artifacts/evidence, RC upgrade and backup/restore/rollback rehearsal, independent code review and evidence audit, owner/operator sign-off, normal merge commit, post-merge `main` verification, and verified production release.

## Blocker
None verified. WP-05 is resumable and is the sole active unfinished package. WP-06 remains `BLOCKED`.

## Exact next action
Fix the quit/InventoryClose tracking ordering defect in `PaperUniqueAccessTrackingListener`, add an automated regression test, run the repository and exact-head tracking gates, then persist the resulting WP-05 state on this same branch and PR. Do not begin WP-06.
