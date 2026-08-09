# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact implementation/evidence head for this checkpoint: `50633f1256aa2189f70219b7ebcca4a740e7acb0`
- Permanent handoff: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-tracking-lifecycle-progress-partial.md`
- WP-06 remains `BLOCKED` until a verified WP-05 production `v1.0.0` release exists.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | PARTIAL | canonical draft PR #18 remains the sole unfinished package lock |
| WP-06 | 10% | BLOCKED | requires verified WP-05 production release |

- Completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no package weight until the complete contract, merge, post-merge verification, and production release are verified.

## Owner-approved WP-05 scope amendment
Real Microsoft/Xbox account authentication is out of scope. Server-visible Java/Floodgate identity behavior remains required, including literal `*` names, UUID/name behavior, cached-offline/never-joined resolution, commands/GUI, delivery, audit, API, and distribution.

## Confirmed production findings
1. Fixed/regression-verified: valid already-prefixed Floodgate recipient names were rejected. Fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
2. Fixed/regression-verified: a trailing inventory-close observation after quit re-promoted a disconnected player's inventory from `LAST_CONFIRMED` to `CONFIRMED_NOW`. Production fix `1d144111d88a1c481e231bd1ba329c58a0fddc20`; automated regression `39e4892562bc441d90c046c79b84d1a1004a2034`.
3. No new production defect was confirmed during the 2026-08-09 tracking-harness session. All new commits from resume checkpoint `180a5ea...` through implementation/evidence head `50633f1...` are acceptance workflow/harness changes.

## Tracking acceptance progress on exact head `50633f1...`
Tracking Contract run `31303340890` completed `failure`, but its evidence artifact `9035204924` (`sha256:b877c96ba230c4bb5d7386d8d9cf2996a0b1d9f62e614cabd947b93a84b78cdb`) contains explicit partial PASS evidence:
- `ACC-TRACK-001` PASS: storage/offhand/armor/cursor/Ender Chest/offline/rejoin continuity.
- `ACC-TRACK-002` allowed-mode PASS: ordinary chest/hopper movement, nested shulker `/shulker:0`, nested bundle `/bundle:0`, natural chunk unload to `LAST_CONFIRMED`, natural reload/reopen to `CONFIRMED_NOW`, and `chunk-unload`/`chunk-load` evidence.
- `ACC-TRACK-002` is NOT complete: the required `shared-containers-allowed: false` restricted-mode shulker/bundle insertion rejection has not yet been live-proven.
- `ACC-TRACK-003` is NOT complete. The run failed in harness sequencing: the naturally dropped sword was still `DROPPED_ITEM` when the first helper frame placement ran, so that helper action failed with `no tracked item in player storage`; later display fixtures were not observed in the chunk-lifecycle assertion. This is not a confirmed production failure.

## Exact-head workflow evidence for `50633f1...`
- Public API `31303340903` — success.
- Exact Removal `31303340925` — success.
- Editor Contract `31303340916` — success.
- Java Identity/Core `31303340947` — success.
- ACC-CORE-005 Full Inventory `31303340907` — success.
- Tracking Contract `31303340890` — failure after explicit Track 001 PASS and Track 002 allowed-mode PASS; Track 003 harness sequencing remains.
- Floodgate Identity `31303340901` — failure during environment setup because the external Floodgate download returned HTTP 403; no plugin-behavior failure was reached.
- CI `31303340889` — Verify, test reports, repository tooling, and new-code complexity succeeded; exact-head Codacy failed because the required independent `[wp04-code-quality]` review marker is absent, so downstream deterministic profile/release steps were skipped. That gate was not bypassed or fabricated.
- PR #18 has no submitted reviews and zero unresolved inline review threads at checkpoint time.

## Harness work completed this session
The tracking workflow was converted from timing-sensitive inline logic to versioned deterministic scripts and then hardened through exact-head live runs: fixed absolute container coordinates, stable supported teleports, ordinary nested-container access, explicit natural chunk unload/reload, fixed Node dependency resolution, durable delivery synchronization, safe test access cells, ordinary player-driven destination loading without force-load APIs, and deterministic block-above clearing for container access.

## Remaining boundary
All 35 acceptance cases must ultimately PASS on the exact final JAR after the last code change. Incomplete or not-yet-final-head areas include at least `ACC-ENV-001`, `ACC-EDIT-003`, the restricted half of `ACC-TRACK-002`, `ACC-TRACK-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`. All already-covered cases must rerun after the final code change.

Package-level gates also remain: complete exact-head WP-04 automated verification, final `1.0.0` packaging/release evidence, RC-to-final upgrade, backup/restore and rollback rehearsal, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge commit, post-merge `main` verification, and verified production release.

## Blocker
No repository blocker is verified. WP-05 is resumable. The Floodgate exact-head run has an external dependency-download 403 and should be rerun when the dependency endpoint is available or via an approved pinned source; do not reinterpret it as a product defect.

## Exact next action
Resume PR #18 from live GitHub. First finish Tracking Contract without weakening assertions: (1) make natural drop pickup deterministic and prove the same instance returns to `PLAYER_INVENTORY` before display placement; (2) place/observe frame, glow frame, and armor stand in a deliberately unloadable fixture chunk and prove `LAST_CONFIRMED` on natural unload then `CONFIRMED_NOW` on reload; (3) add the required `shared-containers-allowed: false` ordinary-player shulker/bundle rejection phase. Rerun `ACC-TRACK-001..003` on one exact head. If fully green, persist tracking evidence and continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` block. Do not begin WP-06.
