# WP-05 resume checkpoint — current Sentinel policy

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Resume base/head observed before claim: `4a5bff10709ade553b938f086f4c09317ec3e915`
- Current live `main` at reconciliation: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Starting package `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`

## Routing reconciliation
Live GitHub shows exactly one unfinished canonical package lock: WP-05 branch `agent/wp-05-live-acceptance-release` / draft PR #18. WP-01 through WP-04 are historical normally merged packages. No WP-06 primary branch exists in `wsg138/EnthusiaTags`; no WP-06 finalization or API-blocker branch exists in LoreItems; WP-06 remains blocked on the verified production `v1.0.0` release.

The `main` queue/state snapshots still say WP-05 `READY`, but live canonical branch/PR state outranks those stale snapshots. This checkpoint resumes WP-05 as `IN_PROGRESS` from exact observed head `4a5bff10709ade553b938f086f4c09317ec3e915`.

## Current-main reconciliation requirement
Since WP-05 began, live `main` advanced by nine commits through PRs #19 and #20. The net authoritative orchestration/Sentinel delta from package starting main changes exactly these files:
- add `.enthusia-test.yml`;
- update `.github/workflows/ci.yml` to publish the exact-SHA `enthusialoreitems-plugin` artifact;
- add `ai-agents/SENTINEL-OPERATING-POLICY.md`;
- update `ai-agents/UNIVERSAL-AGENT-PROMPT.md`;
- add `docs/sentinel-staging.md`.

Before any Sentinel-backed validation or final merge, the long-lived WP-05 branch must preserve those live-main files without reverting WP-05 work. No rebase or force-push is permitted.

## Completed acceptance criteria retained
- Two confirmed production defects are fixed and regression-covered: already-prefixed Floodgate recipient binding and quit/InventoryClose tracking race.
- `ACC-TRACK-001` has explicit live PASS evidence on implementation head `50633f1256aa2189f70219b7ebcca4a740e7acb0`.
- Allowed-mode/lifecycle portions of `ACC-TRACK-002` have explicit live PASS evidence on that head.
- Public API, exact removal, editor contract, Java identity/core, and full-inventory workflows have successful prior exact-head evidence.
- The newer exact-head rerun on `4a5bff10709ade553b938f086f4c09317ec3e915` also passed Floodgate Identity, Public API, Exact Removal, Editor Contract, Java Identity/Core, and ACC-CORE-005 Full Inventory.

## Remaining acceptance criteria
- Finish restricted-mode `ACC-TRACK-002` with ordinary-player shulker/bundle insertion rejection while `shared-containers-allowed: false`.
- Finish `ACC-TRACK-003`: deterministic natural pickup of the same instance, then item-frame/glow-frame/armor-stand lifecycle with natural unload to `LAST_CONFIRMED` and reload to `CONFIRMED_NOW`.
- Complete remaining/not-final WP-05 matrix areas including `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-002..004`, `ACC-DIST-001..005`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`.
- Repeat the complete 35-case matrix on the final release-candidate JAR after the last code change.
- Complete exact-head WP-04 automated gates, final 1.0.0 packaging/release evidence, RC-to-final upgrade, backup/restore and rollback rehearsal, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge main verification, and verified `v1.0.0` release/assets.

## Tests and exact results at resumed head
Exact head `4a5bff10709ade553b938f086f4c09317ec3e915`:
- `WP-05 Public API Acceptance` run `31303669551`: success.
- `WP-05 Java Identity and Core Acceptance` run `31303669574`: success.
- `WP-05 ACC-CORE-005 Full Inventory Acceptance` run `31303669541`: success.
- `WP-05 Editor Contract Acceptance` run `31303669555`: success.
- `WP-05 Exact Removal Acceptance` run `31303669571`: success.
- `WP-05 Floodgate Identity Acceptance` run `31303669561`: success.
- `WP-05 Tracking Contract Acceptance` run `31303669547`: failure in step `Execute ACC-TRACK-001 through ACC-TRACK-003`; evidence remains to be diagnosed/fixed without weakening criteria.
- `CI` run `31303669577`: failure; exact failing gate still requires diagnosis.
- Combined commit status: CodeRabbit success.
- Submitted PR reviews: none. Unresolved review threads: zero.

## Known findings
- Tracking remains a harness/acceptance sequencing problem unless new evidence proves a production defect; do not change production behavior without that proof.
- Live `main` Sentinel onboarding/policy must be reconciled into this branch before Sentinel-backed acceptance and before merge.
- No verified repository blocker exists.

## Blocker
None. WP-05 remains actionable.

## Exact next action
1. Reconcile the five authoritative live-main Sentinel/orchestration files into the WP-05 branch without losing WP-05 changes, using non-destructive history.
2. Re-fetch the claimed branch/PR and stop if the head moved concurrently.
3. Diagnose the exact Tracking Contract failure at `4a5bff1...`, fix only the proven acceptance-harness defect(s), and add the required restricted shulker/bundle phase.
4. Rerun `ACC-TRACK-001..003` on one exact head and persist GitHub-backed evidence.
5. Continue only within WP-05; do not begin WP-06.
