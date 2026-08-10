# WP-05 stable tracking and quality checkpoint

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Refreshed live `main`: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Exact implementation/evidence head: `031855c7bc6e7ad27c6bb8d839bcdfea8acb8b31`

## Confirmed tracking defect and production remediation
Paper can unload/load entity state independently from the block-chunk lifecycle. Relying only on `ChunkUnloadEvent` / `ChunkLoadEvent` left dropped/display entity locations falsely `CONFIRMED_NOW` after the entity state became inaccessible.

Production fix `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626` added bounded `EntitiesUnloadEvent` / `EntitiesLoadEvent` reconciliation for dropped items and display entities while preserving no-force-load behavior and bounded scans.

The follow-up quality refactor extracted bounded physical entity scanning and deferred scan requests without changing the lifecycle semantics:
- `6d2d83b16a9d2f104d06dce9ac24bf34a16cc662` — factor bounded physical entity scanning and add lifecycle regression coverage.
- `eb49a39258790e378208d5c849af0537fac4e126` — extract deferred player/chunk scan requests; preserve `isChunkLoaded` guard before chunk access.

Codacy remediation remained narrow:
- `3c80b007206b95e38d6f9c302a73598bafe71d0e` excludes only the two Node-only Mineflayer runtime drivers from incompatible browser/legacy JavaScript analyzer policies; production Java, Java tests, acceptance Java, and shell harness remain analyzed.
- `6c69bd7452c1dbfde945225c1ee6a470ecc05358` resolves the final real Codacy Java-test duplicate-literal finding normally.
- `031855c7bc6e7ad27c6bb8d839bcdfea8acb8b31` hardens only the live bot's display-fixture visibility wait after one client-side visibility timeout; acceptance semantics remain ordinary client placement into real empty display entities.

## Exact-head automated and acceptance evidence
All currently configured exact-head workflows are green on `031855c7bc6e7ad27c6bb8d839bcdfea8acb8b31`:
- CI `31344195749` — success, including Gradle verify/tests, test-report preservation, repository tooling, new-code Lizard complexity, exact-head Codacy with zero annotations, deterministic WP-04 profile, RC artifact validation, immutable release evidence, reproducibility, and publication of the Sentinel-consumable plugin artifact.
- Public API `31344195727` — success.
- Java Identity/Core `31344195754` — success.
- Exact Removal `31344195726` — success.
- Editor Contract `31344195752` — success.
- ACC-CORE-005 Full Inventory `31344195762` — success.
- Floodgate Identity `31344195724` — success.
- Tracking Contract `31344195728` — success.

CI artifacts:
- `enthusialoreitems-plugin` artifact `9046795430`, digest `sha256:2fa1663813c44b12e53498ed6901b76b72bafdcf9fbd0c5ca2f9c3ebf1ca608a`, expires `2026-09-09T00:23:47Z`.
- `wp04-verification-031855c7bc6e7ad27c6bb8d839bcdfea8acb8b31` artifact `9046794871`, digest `sha256:6f66f3a2d8ab1d5d38f9b2b4aa86ea3204cac982a89e762d3564ef358e73be19`, expires `2026-09-09T00:23:45Z`.

Tracking artifact:
- `wp05-tracking-contract-031855c7bc6e7ad27c6bb8d839bcdfea8acb8b31` artifact `9046809567`, digest `sha256:3180f12720dce027e6cf0fee85f880a23cfd03cef04ca9c8294413d22feb2336`, expires `2026-09-09T00:24:46Z`.
- PASS `ACC-TRACK-001`.
- PASS `ACC-TRACK-002` allowed mode and restricted shulker/bundle rejection/no-loss phase.
- PASS `ACC-TRACK-003`, including ordinary drop/pickup, ordinary frame/glow-frame/armor-stand placement, controlled death/drop, natural entity unload -> `LAST_CONFIRMED`, natural entity reload -> `CONFIRMED_NOW`, exact-instance continuity, and restart integrity.

## Review reconciliation
PR #18 currently has no submitted reviews and no inline review threads. This means there is no hidden requested-change blocker, but it does not satisfy WP-05's eventual independent harsh review or separate evidence-audit requirements.

## Confirmed WP-05 production defects fixed so far
1. Already-prefixed Floodgate recipient binding — `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
2. Quit/InventoryClose tracking race — `1d144111d88a1c481e231bd1ba329c58a0fddc20`, regression `39e4892562bc441d90c046c79b84d1a1004a2034`.
3. Paper entity lifecycle tracking gap — `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`, live-regression verified through the exact-head Tracking Contract above.

## Remaining package boundary
WP-05 remains indivisible and is not ready for review or merge. The final 35-case matrix must all PASS on the exact final JAR after the last code change. Remaining live cases include at minimum:
- `ACC-ENV-001`
- `ACC-EDIT-003`
- `ACC-PROT-001..002`
- `ACC-ANOM-001..002`
- `ACC-DEST-002..004`
- `ACC-DIST-001..005`
- `ACC-LIFE-001..002`
- `ACC-OPS-001..005`

The final head also still requires applicable Sentinel staging startup/restart evidence under the live shared-service policy, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge commit, post-merge `main` verification, and production `v1.0.0` tag/release/assets.

## Blocker
None verified. WP-05 remains actionable.

## Exact next action
Continue within WP-05 by implementing and executing the next missing deterministic live acceptance cluster, beginning with `ACC-ANOM-001..002`, then `ACC-DEST-002..004` and `ACC-LIFE-001..002`. Treat any mismatch as a WP-05 defect and fix/retest it in this package. Do not begin WP-06.
