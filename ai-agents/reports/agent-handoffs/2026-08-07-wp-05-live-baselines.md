# WP-05 live baseline checkpoint — 2026-08-07

## Package
- WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Latest audited evidence head before this coordination checkpoint: `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`

## Completed acceptance criteria
### ACC-ENV-001 — PASS
- Exact RC booted on Java 21 / Paper 1.21.11 build 116 with Geyser/Floodgate/ViaVersion.
- Exact release and server/plugin hashes recorded.
- LoreItems reached read/write state.
- SQLite schema V1–V7, WAL mode, integrity and foreign-key checks passed.
- Baseline durable counts were clean and no item entity was created by startup.
- Clean shutdown observed.
- Durable evidence: `docs/wp-05-acceptance/ACC-ENV-001/`.
- Evidence commit: `be8a3a4832dc6a78e918b39963a946731c22f624`.
- Run: `31217633117`.

### ACC-OPS-001 — PASS
- Reversible startup failure induced by replacing the expected SQLite file path with a directory.
- LoreItems entered degraded/read-only mode without announcing writable storage.
- Independent Bukkit consumer of `LoreItemsServiceV1` received `SERVICE_UNAVAILABLE`.
- No DB file, definition, instance, direct delivery, or item entity was created in the degraded phase.
- After removing the induced fault, the same exact RC restarted read/write and the public API returned the normal `UNKNOWN_DEFINITION` outcome.
- Healthy SQLite WAL/integrity/FK checks passed; zero definitions, instances, and direct deliveries remained.
- One external API result row was intentionally present with `UNKNOWN_DEFINITION` and `delivery_id=null`, preserving idempotent replay without physical delivery intent.
- Durable evidence: `docs/wp-05-acceptance/ACC-OPS-001/`.
- Evidence commit: `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`.
- Corrected run: `31218811889`.

## Findings and fixes
- No LoreItems implementation defect has been confirmed by the accepted live cases.
- Acceptance harness finding: first ops run `31218454541` failed because the harness incorrectly classified the durable `UNKNOWN_DEFINITION` API result as unintended work. Production `SQLiteDirectDeliveryRepository.rejectUnknownDefinition` intentionally persists that result for idempotency. The assertion was corrected in `c00271761e60446f4611706f6b70f3d00ccfde03`, and corrected run `31218811889` passed.
- Prior durable-state review finding remains fixed: WP-04 history accidentally condensed during the original blocker checkpoint was restored at `ed869117dc449c0c96c824cf2668725ea711662b`.

## Remaining acceptance criteria
Every other WP-05 matrix case remains uncredited. The package still requires full Java and Bedrock/Floodgate identity cases, creation/adoption/give/editor/tracking/protection/anomaly/destructive/distribution/API/lifecycle/backup/rollback/load cases, same-package remediation of any defects, the final all-PASS exact-JAR rerun, final automated/Codacy/review/evidence gates, operator sign-off, `1.0.0`, normal merge, post-merge verification, and production release.

## Current external boundary
The GitHub-hosted runner solves networked server execution but not authenticated player identity. No accessible Java/Microsoft or Bedrock/Xbox acceptance credentials were found in repository evidence or available connectors. Many remaining matrix cases require a real Bukkit `Player`, held item, inventory, Ender Chest, death/entity interaction, or Floodgate-bound identity. Direct DB edits, offline-mode identities, or console-only simulation are not accepted substitutes for those case requirements.

This does not yet close WP-05 as blocked because additional diagnostic/client and server-only paths should be exhausted first. Any non-authenticated bot work may be used only to discover defects, never to claim a production acceptance PASS where authenticated identity/physical player behavior is required.

## Progress
- Completed packages: 4/6.
- Remaining packages: 2/6.
- Weighted progress: 75%.
- WP-05 accepted live cases: 2.

## Exact next action
Continue this same WP-05 branch/PR. Investigate a faithful authenticated Java + Bedrock/Floodgate client route and exhaust any remaining server-only cases. If the authenticated account/session dependency becomes the verified sole barrier to further required work, record it precisely without starting WP-06.
