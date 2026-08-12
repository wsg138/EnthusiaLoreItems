# WP-05 tracking lifecycle progress — PARTIAL

Date: 2026-08-09

## Package / lock
- WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Session start branch head: `f68e7fef1d60a188034ccacbf73c07580da1e167`
- Resume claim checkpoint: `180a5ea363b300579c27e2e04a8a44420ccfc99b`
- Exact implementation/evidence head at stop: `50633f1256aa2189f70219b7ebcca4a740e7acb0`

Live reconciliation confirmed WP-05 is the sole unfinished canonical package lock. WP-06 remains blocked and was not started.

## Production findings retained
Two previously confirmed production defects remain fixed/regression-verified:
1. Already-prefixed Floodgate recipient binding — fix `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
2. Quit/InventoryClose tracking race — production fix `1d144111d88a1c481e231bd1ba329c58a0fddc20`, automated regression `39e4892562bc441d90c046c79b84d1a1004a2034`.

No new production defect was confirmed in this session.

## Acceptance-harness commits made in this session
All implementation commits after the claim are acceptance-only; production plugin behavior was not changed.

- `0ed5bd4fe5cb1a2dab79e90b50648a8572d3a5c3` — fixed absolute tracking container coordinates.
- `838a70863532e9c40fb4037eece1657d0644b45e` — stable supported bot positions after chunk loading.
- `75148147c581d1aa8034fd1bbd18124bdc82300a` — nested tracking through ordinary player container access.
- `860c308e38f5f2080d17eb3f754d952e5524d2d0` — moved tracking acceptance into versioned deterministic scripts and explicit lifecycle phases.
- `0f10a817bde6b0ff010db667e351b9c23360d1cb` — fixed test-only Node dependency resolution.
- `118293412ab47861e7b1b2a6d3fec88bc88bf571` — synchronized helper placement with durable queued delivery/current-state evidence.
- `519b5c7a5ba424ba9ee60463e759c33a02087c36` — cleared safe bot access cells around test fixtures.
- `1c8d9f4bc83fb9661889e2ef062750b8af5811d4` — loaded far travel destinations by ordinary player presence, without force-load APIs.
- `50633f1256aa2189f70219b7ebcca4a740e7acb0` — made test container access terrain-independent by clearing the access block above the container; moved natural pickup approach to the actual drop area.

## Exact-head tracking evidence
Tracking Contract run: `31303340890`
Head: `50633f1256aa2189f70219b7ebcca4a740e7acb0`
Conclusion: `failure`
Artifact: `9035204924`
Artifact digest: `sha256:b877c96ba230c4bb5d7386d8d9cf2996a0b1d9f62e614cabd947b93a84b78cdb`

Explicit case evidence from the run:
- `ACC-TRACK-001` PASS — storage, offhand, armor, cursor, Ender Chest, disconnect `LAST_CONFIRMED`, reconnect `CONFIRMED_NOW`, same instance continuity.
- `ACC-TRACK-002` allowed-mode/lifecycle portion PASS — ordinary chest and hopper movement, nested shulker structural path `/shulker:0`, nested bundle structural path `/bundle:0`, natural chunk unload to `LAST_CONFIRMED`, natural chunk reload/reopen to `CONFIRMED_NOW`, with chunk unload/load sources.
- `ACC-TRACK-002` remains incomplete because the matrix also requires restricted mode with `shared-containers-allowed: false`; ordinary player insertion into shulker and bundle must be rejected and verified live.
- `ACC-TRACK-003` remains incomplete. The normal drop was recorded as `DROPPED_ITEM`, but the bot had not actually picked that same item back up when the shell advanced. The first frame helper call therefore failed with `no tracked item in player storage`. Fresh later copies were placed for glow frame and armor stand, but the intended three-holder lifecycle assertion did not complete. Do not treat this as a product defect.

## Exact-head companion workflows
For `50633f1...`:
- Public API `31303340903` — success.
- Exact Removal `31303340925` — success.
- Editor Contract `31303340916` — success.
- Java Identity/Core `31303340947` — success.
- ACC-CORE-005 Full Inventory `31303340907` — success.
- Floodgate Identity `31303340901` — failure during setup because the external Floodgate artifact download returned HTTP 403. Product behavior was not reached.
- CI `31303340889` — Verify, test reports, repository tooling, and new-code complexity succeeded. Exact-head Codacy failed because the required independent `[wp04-code-quality]` review marker is absent; downstream deterministic profile/release stages were skipped. No gate was bypassed or fabricated.
- PR #18: no submitted reviews; zero unresolved inline review threads at stop.

## Remaining acceptance / release boundary
All 35 cases must ultimately PASS on the exact final JAR after the last code change. Incomplete or not-yet-final-head areas include at least `ACC-ENV-001`, `ACC-EDIT-003`, restricted `ACC-TRACK-002`, `ACC-TRACK-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`. Existing successful cases remain traceability only until the final complete matrix rerun.

The package also still requires complete exact-head WP-04 automated gates, final `1.0.0` packaging/release evidence, RC-to-final upgrade rehearsal, backup/restore and rollback rehearsal, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge commit, post-merge live-main verification, and verified production `v1.0.0` tag/release/assets.

## Blocker
No repository blocker verified. The Floodgate exact-head run currently has an external HTTP 403 dependency-fetch problem; rerun or use an approved pinned source when available. WP-05 itself remains resumable.

## Exact next action
Resume PR #18 from live GitHub and finish Tracking Contract on one exact head without weakening acceptance criteria:
1. Make natural pickup deterministic by moving the ordinary bot to the actual dropped entity and wait/assert that the same tracked instance returns to `PLAYER_INVENTORY` before any display placement.
2. Place frame, glow frame, and armor stand fixtures in an intentionally unloadable test chunk; prove holder observations, natural unload to `LAST_CONFIRMED`, then natural reload to `CONFIRMED_NOW`.
3. Add a `shared-containers-allowed: false` phase using ordinary player inventory interactions to prove tracked-item insertion into a shulker box and bundle is rejected and the tracked item remains outside the shared container.
4. Rerun `ACC-TRACK-001..003`; persist exact-head artifacts and case output.
5. If tracking is fully green, continue the consolidated `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002` block.

Do not begin WP-06.
