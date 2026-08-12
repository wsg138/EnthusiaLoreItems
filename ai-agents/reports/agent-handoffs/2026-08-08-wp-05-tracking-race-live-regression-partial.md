# WP-05 tracking race live-regression checkpoint

## Package state
- Package: WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact implementation/evidence head: `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a`
- WP-06 remains `BLOCKED` and was not started.

## Production defect fixed this worker
Exact-head tracking run `31279868113` on predecessor `01c25d0fd3d96438b57d7c89a5308046c023495b` exposed a real event-ordering defect. `PaperUniqueAccessTrackingListener` correctly wrote `LAST_CONFIRMED` during `PlayerQuitEvent`, then a trailing `InventoryCloseEvent` re-promoted the disconnected player's inventory to `CONFIRMED_NOW`.

Fixes:
- `1d144111d88a1c481e231bd1ba329c58a0fddc20` — adds a one-tick quitting-player guard so a trailing close from the same disconnect sequence cannot overwrite the quit observation; the guard is cleared next tick and on listener close.
- `39e4892562bc441d90c046c79b84d1a1004a2034` — adds `trailingInventoryCloseAfterQuitDoesNotRestoreLiveConfirmation`, reproducing quit then close and asserting only `LAST_CONFIRMED` / `player-quit-unique` is submitted.

Repository verification on `39e4892562bc441d90c046c79b84d1a1004a2034` passed the full CI `Verify`, repository tooling, and new-code complexity stages. CI's later exact-head Codacy step remained gated by the required independent `[wp04-code-quality]` review marker; that review evidence was not fabricated or weakened.

## Live Paper regression result
Tracking acceptance run `31287048615` on exact implementation/evidence head `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a` reached and **PASSed ACC-TRACK-001** after several harness-only transport repairs.

The run recorded the required sequence:
- ordinary storage/offhand/armor/cursor transitions;
- real Mineflayer Ender Chest deposit and withdrawal;
- `player-quit-unique` as `LAST_CONFIRMED` with no trailing `inventory-close-unique` re-promotion;
- reconnect restoring `CONFIRMED_NOW` for the same single instance;
- case output: `PASS ACC-TRACK-001: storage/offhand/armor/cursor/Ender/offline/rejoin identity continuity`.

This is live Paper regression evidence that the quit/close production defect is fixed. It is not final-release credit because subsequent code changes, if any, require the complete matrix to rerun on the exact final JAR.

## Tracking-harness repairs and current remaining failure
Harness-only commits made while reaching the live regression:
- `a90db027d0d8d8eb2b0768c7289ec2d75ca2db94` — wait for the bot to observe teleport destinations and destination chunks before interaction.
- `baada980e3fe1e0ba399e9ebfb2a7113d4d96e62` — replace radius block discovery with exact client block lookup.
- `e73ddc9d310e49e3c1309d70c1db9bc73cb7427a` — clear the physical access block above generated test containers so the ordinary player can open them.

Run `31287048615` then failed later in ACC-TRACK-002 for a diagnosed harness-coordinate error, not a LoreItems assertion. After teleporting to the chest test area, Mineflayer settled at Y `70.92`; the helper used `bot.entity.position.floored().offset(1,-1,0)`, queried `(11,69,0)`, and reported `observed=air`, while server feedback confirmed the intended chest had been placed at `(11,70,0)`. The next worker should replace relative Y block lookup with the fixed absolute world coordinates already used by `/setblock` (Ender `(1,70,0)`, chest `(11,70,0)`, hopper `(20,70,0)`) without weakening any acceptance assertion.

## Exact-head workflow state observed for `e73ddc9...`
- Java Identity/Core `31287048544` — `completed/success`.
- ACC-CORE-005 Full Inventory `31287048549` — `completed/success`.
- Editor Contract `31287048547` — `completed/success`.
- Exact Removal `31287048551` — `completed/success`.
- Public API `31287048546` — `completed/success`.
- Tracking Contract `31287048615` — `completed/failure`, but ACC-TRACK-001 explicitly PASSed before the later harness-coordinate failure in ACC-TRACK-002.
- CI `31287048568` — `completed/failure`; `Verify`, preserved test reports, repository tooling, and new-code complexity all succeeded; exact-head Codacy failed at the independent review-marker gate, so later WP-04 CI stages were skipped.
- Floodgate Identity `31287048553` — last observed `in_progress`, executing ACC-ID-002 after successful environment/build setup. No PASS is inferred from the running state.

## Other production finding retained
The earlier WP-05 Floodgate binding defect remains fixed/regression-verified: valid already-prefixed Floodgate names were rejected by recipient binding. Fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`; static cleanup `ed52a32688329be931bc6fdfc5008b393a0f2ffb`; historical live regression `31222017554` PASS.

## Remaining release work
All 35 acceptance cases still must PASS on the exact final JAR after the final code change. Incomplete areas include at least `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-TRACK-002..003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`; all already-covered cases must be rerun once more on the final head.

Package-level gates also remain: complete exact-head WP-04 migration/failure/saturation/profile/package/reproducibility/static-analysis/Codacy verification, final `1.0.0` version and release evidence/assets, RC-to-final upgrade, backup/restore and rollback rehearsal, independent harsh code review and separate evidence audit, owner/operator sign-off, normal merge, post-merge `main` verification, and verified production `v1.0.0` release.

## Exact next action
Resume PR #18 from the live canonical head. First reconcile branch movement and the final result of Floodgate run `31287048553`. Then repair the tracking acceptance helper to query the fixed absolute block coordinates instead of deriving container Y from the bot's fractional post-teleport position. Rerun Tracking Contract; if green, persist exact-head credit for `ACC-TRACK-001..003` and continue the next consolidated acceptance block for `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Do not begin WP-06.
