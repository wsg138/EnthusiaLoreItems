# WP-05 resume checkpoint — tracking contract complete

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Refreshed live `main`: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Exact implementation/evidence head being checkpointed: `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`

## Tracking contract completion
Exact-head Tracking Contract run `31341725899` completed `success` on `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`.

Evidence artifact:
- Artifact ID: `9046072606`
- Name: `wp05-tracking-contract-f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`
- Digest: `sha256:2a3467dd44ee2bac38a494387845dc8c2a294c489ded964c021ac8f8a68f4e60`
- Retention expiry: `2026-09-08T23:26:19Z`

Verified results from the artifact:
- PASS `ACC-TRACK-001` — storage/offhand/armor/cursor/Ender Chest/offline/rejoin identity continuity.
- PASS `ACC-TRACK-002` allowed mode — chest/hopper/nested shulker+bundle plus natural unload/reload retention.
- PASS `ACC-TRACK-002` restricted mode — ordinary client shulker and bundle insertion rejected with `shared-containers-allowed: false` and no item loss; restart retained SQLite integrity.
- PASS `ACC-TRACK-003` — natural drop/pickup, ordinary item frame/glow item frame/armor stand placement, controlled death/drop, natural unload/reload, and exact-instance pickup continuity.
- PASS tracking restart durability/integrity.

## Confirmed defect and fix
The prior Track-3 failure is now classified as a production tracking defect rather than a harness-only failure. Paper can unload/load entity state on an entity lifecycle that is distinct from the block-chunk `ChunkUnloadEvent` / `ChunkLoadEvent` path used by LoreItems. As a result, display entities and dropped entities could remain durably `CONFIRMED_NOW` after their entity state became inaccessible.

Fix commit `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626` adds bounded `EntitiesUnloadEvent` and `EntitiesLoadEvent` reconciliation for dropped items and display entities while preserving the existing no-force-load, bounded-scan behavior. The exact-head live contract above verifies `chunk-unload-entities-*` last-confirmed evidence and `chunk-load-entities-*` reconfirmation.

## Other exact-head workflow evidence
On `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`:
- Public API run `31341725879` — success.
- Exact Removal run `31341725885` — success.
- Editor Contract run `31341725924` — success.
- ACC-CORE-005 Full Inventory run `31341725882` — success.
- Java Identity/Core run `31341725888` — success.
- Floodgate Identity run `31341725887` — success.
- Tracking Contract run `31341725899` — success.
- CI run `31341725881` — failure only after `Verify`, test-report preservation, and repository tooling succeeded; `Verify new-code complexity` failed. Exact-head Codacy, profile/package stages, and Sentinel artifact publication were therefore skipped and remain required.

## Remaining acceptance criteria
WP-05 remains indivisible and incomplete. Remaining work includes the rest of the 35-case final matrix, including `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`, followed by the complete exact-head automated/review/evidence/sign-off/merge/post-merge/release gates in the package contract.

The final head still requires clean exact-head CI, Codacy, profile/package evidence, successful `enthusialoreitems-plugin` publication, applicable Sentinel startup/restart evidence under the live Sentinel policy, independent code review, separate evidence audit, owner/operator sign-off, normal merge, verified `main`, and production `v1.0.0` release/assets.

## Known findings / blocker
- Confirmed WP-05 production defects fixed so far: already-prefixed Floodgate recipient binding; quit/InventoryClose tracking race; entity lifecycle tracking gap fixed by `f2203d43...`.
- Current exact-head failure: new-code complexity gate in CI run `31341725881`.
- Blocker: none verified. WP-05 remains actionable.

## Exact next action
Remediate the exact new-code complexity failure caused by the entity-lifecycle change without removing or weakening `EntitiesLoadEvent` / `EntitiesUnloadEvent` coverage. Rerun exact-head CI and the full Tracking Contract. After the tracking fix is green under all repository gates, continue the next unexecuted in-package acceptance cases, starting with `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Do not begin WP-06.
