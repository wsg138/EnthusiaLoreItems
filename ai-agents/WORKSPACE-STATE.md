# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact implementation/evidence head checkpointed by this worker: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`
- Permanent handoff: `ai-agents/reports/agent-handoffs/2026-08-08-wp-05-tracking-race-live-regression-partial.md`
- Dependency satisfied by verified WP-04 RC `v1.0.0-rc.1`.
- WP-06 remains blocked until the verified WP-05 production `v1.0.0` release.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | PARTIAL | canonical draft PR #18 retains the lock; useful defect-fix and acceptance work is durable but release gates remain |
| WP-06 | 10% | BLOCKED | requires verified WP-05 production release |

- Packages complete: 4/6.
- Weighted completed progress: 75%.
- No package weight is awarded to WP-05 until complete.

## Owner-approved WP-05 scope amendment
Real Microsoft/Xbox account authentication is out of scope. Server-visible Java/Floodgate identity behavior remains required, including real `*`-prefixed names, UUID/name behavior, cached-offline/never-joined resolution, commands/GUI, delivery, audit, API and distribution behavior.

## Confirmed production findings
1. Fixed/regression-verified: valid already-prefixed Floodgate recipient names were rejected by recipient binding. Fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`; static cleanup `ed52a32688329be931bc6fdfc5008b393a0f2ffb`; historical live regression `31222017554` PASS.
2. Fixed and live-regression-verified this worker: `PlayerQuitEvent` correctly wrote `LAST_CONFIRMED`, but a trailing `InventoryCloseEvent` re-promoted the disconnected player's inventory to `CONFIRMED_NOW`. Production fix `1d144111d88a1c481e231bd1ba329c58a0fddc20`; automated regression `39e4892562bc441d90c046c79b84d1a1004a2034`. Live Paper run `31287048615` on exact head `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a` explicitly PASSed `ACC-TRACK-001`, including Ender Chest, quit `LAST_CONFIRMED`, and reconnect `CONFIRMED_NOW` continuity.

## Current worker verification and acceptance evidence
Exact implementation/evidence head `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a` produced:
- Java Identity/Core `31287048544` — success.
- ACC-CORE-005 Full Inventory `31287048549` — success.
- Editor Contract `31287048547` — success.
- Exact Removal `31287048551` — success.
- Public API `31287048546` — success.
- Tracking Contract `31287048615` — workflow failure after explicit `ACC-TRACK-001` PASS; later ACC-TRACK-002 setup failed on a diagnosed harness coordinate calculation, not a LoreItems assertion.
- CI `31287048568` — repository `Verify`, test-report preservation, repository tooling, and new-code complexity succeeded; exact-head Codacy failed because the required independent `[wp04-code-quality]` review marker is absent, so later CI release stages were skipped. No review marker was fabricated or bypassed.
- Floodgate Identity `31287048553` — last observed in progress while executing ACC-ID-002 after successful build/environment setup; no PASS inferred from that running state.

The tracking acceptance transport was hardened without weakening assertions. The current remaining TRACK-002 harness defect is exact: Mineflayer settled at Y `70.92` after teleport, then relative `floor(y)-1` queried chest coordinate Y=69 even though the workflow had successfully placed the chest at Y=70. The next change should use the fixed absolute world block coordinates already specified by the workflow.

Permanent predecessor evidence remains traceability only where later commits invalidate exact-head credit. All cases must rerun on the exact final JAR after the last code change.

## Remaining acceptance criteria
All 35 cases must ultimately PASS against the exact final JAR. Incomplete areas include at least:
- `ACC-ENV-001`
- `ACC-EDIT-003`
- `ACC-TRACK-002..003`
- `ACC-PROT-001..002`
- `ACC-ANOM-001..002`
- `ACC-DEST-002..004`
- `ACC-DIST-001..005`
- `ACC-LIFE-001..002`
- `ACC-OPS-001..005`

All already-covered identity/core/editor/tracking/exact-remove/API cases must be rerun again once the final code change is made.

Package-level gates still require complete exact-head WP-04 automated verification, final `1.0.0` version/release artifacts and evidence, RC-to-final upgrade, backup/restore and rollback rehearsal, independent harsh code review and separate evidence audit, owner/operator sign-off, normal merge commit, post-merge `main` verification, and verified production release.

## Blocker
None verified. WP-05 is resumable and remains the sole active unfinished canonical package. The missing independent Codacy review marker is a release gate, not a reason to start another package. WP-06 remains `BLOCKED`.

## Exact next action
Resume PR #18 from the live canonical head and reconcile any branch movement plus the final result of Floodgate run `31287048553`. Repair `.github/workflows/wp05-tracking-contract-acceptance.yml` so container lookup uses the fixed absolute coordinates (Ender `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`) rather than deriving Y from Mineflayer's fractional post-teleport position. Rerun Tracking Contract. If green, record exact-head `ACC-TRACK-001..003` evidence and proceed to the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` acceptance block. Do not begin WP-06.
