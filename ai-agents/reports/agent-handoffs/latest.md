# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Refreshed live `main`: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Exact implementation/evidence head being checkpointed: `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`
- Current resume checkpoint: `ai-agents/reports/agent-handoffs/2026-08-09-wp-05-tracking-contract-complete-resume.md`

## Current exact-head evidence
Tracking is now complete on `f2203d43...`:
- Tracking Contract run `31341725899` — success.
- Artifact `9046072606`, `wp05-tracking-contract-f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`, digest `sha256:2a3467dd44ee2bac38a494387845dc8c2a294c489ded964c021ac8f8a68f4e60`.
- PASS `ACC-TRACK-001`.
- PASS `ACC-TRACK-002` allowed-mode natural lifecycle.
- PASS `ACC-TRACK-002` restricted shulker/bundle insertion rejection with no item loss and restart integrity.
- PASS `ACC-TRACK-003`, including drop/pickup, ordinary frame/glow-frame/armor-stand placement, death/drop, natural entity unload to last-confirmed, natural entity reload to confirmed-now, exact-instance continuity, and restart durability.

Other exact-head runs on `f2203d43...` are green for Public API (`31341725879`), Exact Removal (`31341725885`), Editor Contract (`31341725924`), ACC-CORE-005 (`31341725882`), Java Identity/Core (`31341725888`), and Floodgate Identity (`31341725887`).

CI run `31341725881` passed Gradle Verify/test reports/repository tooling, then failed `Verify new-code complexity`; exact-head Codacy, profile/package stages, and Sentinel artifact publication were skipped. Those gates remain required.

## Production findings
Three confirmed WP-05 production defects are now fixed/regression-verified:
1. already-prefixed Floodgate recipient binding;
2. quit/InventoryClose tracking race;
3. entity lifecycle tracking gap: Paper entity unload/load can occur independently of the block-chunk lifecycle, so LoreItems now observes bounded `EntitiesUnloadEvent` / `EntitiesLoadEvent` surfaces for dropped/display entities in addition to chunk events. Fix: `f2203d43e37c1c23edebd4618cf4aa0b0f3e4626`.

## Remaining boundary
All 35 final acceptance cases and all package review/release gates remain indivisible. Remaining cases include `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`.

Final exact-head CI/Codacy/profile/package/Sentinel evidence, independent review, evidence audit, owner/operator sign-off, normal merge, post-merge `main` verification, and production `v1.0.0` release/assets also remain.

## Blocker
None verified. WP-05 is actionable.

## Exact next action
Fix the exact new-code complexity regression from the entity-lifecycle implementation without weakening the lifecycle coverage, rerun exact-head CI and Tracking Contract, then continue `ACC-ANOM-001..002`, `ACC-DEST-002..004`, and `ACC-LIFE-001..002`. Do not begin WP-06.
