# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18 — `WP-05: complete live acceptance and release LoreItems`
- Refreshed live `main`: `70a636a25d12d755342d90d6846b86a0e56e865b`
- Exact implementation/evidence head being checkpointed: `6028b1d6cd5b28866376aaf07f35b726052b10a6`
- Resume basis: the sole unfinished canonical package lock. WP-01 through WP-04 canonical branches are contained in live `main`; LoreItems WP-06 finalization/API-blocker branches are absent; EnthusiaTags has no WP-06 canonical branch and no open PR.

## Completed acceptance criteria / evidence
- Previously durable and exact-head verified: Java identity/core, Floodgate server-visible identity, public API, full-inventory delivery, editor contract, exact removal, and tracking `ACC-TRACK-001..003` on prior stable heads.
- `ACC-ANOM-001..002` deterministic live acceptance workflows are implemented and had passed on the immediately preceding stable acceptance head.
- `a5855cda261daf7abcda520efb8d12b21056b175` added deterministic Paper coverage for `ACC-DEST-002`, `ACC-DEST-004`, `ACC-LIFE-001`, and `ACC-LIFE-002`.
- `6028b1d6cd5b28866376aaf07f35b726052b10a6` corrected the native Paper reload invocation used by that new lifecycle acceptance workflow.

## Remaining acceptance criteria
- Diagnose and resolve the exact-head failures listed below before extending acceptance scope.
- Complete still-missing destructive/lifecycle coverage, including `ACC-DEST-003` where not yet proven by the new workflow.
- Complete remaining in-scope matrix areas including `ACC-ENV-001`, `ACC-EDIT-003`, `ACC-PROT-001..002`, `ACC-DIST-001..005`, and `ACC-OPS-001..005`.
- Repeat the complete in-scope matrix on the final post-code-change JAR.
- Obtain applicable final Sentinel startup/restart evidence, independent harsh code review, separate evidence audit, owner/operator sign-off, normal merge, post-merge `main` verification, and verified production `v1.0.0` release/assets.

## Tests run / exact results at checkpointed head `6028b1d6...`
- `CI` `31349550196`: success.
- `WP-05 Public API Acceptance` `31349550210`: success.
- `WP-05 Java Identity and Core Acceptance` `31349550171`: success.
- `WP-05 Exact Removal Acceptance` `31349550170`: success.
- `WP-05 Editor Contract Acceptance` `31349550158`: success.
- `WP-05 ACC-CORE-005 Full Inventory Acceptance` `31349550177`: success.
- `WP-05 Floodgate Identity Acceptance` `31349550168`: success.
- `WP-05 Anomaly Contract Acceptance` `31349550157`: success.
- `WP-05 Mutation Review Contract Acceptance` `31349550167`: success.
- `WP-05 Ambiguous Mutation Recovery Acceptance` `31349550162`: failure.
- `WP-05 Tracking Contract Acceptance` `31349550199`: failure.
- `WP-05 Destructive Lifecycle Acceptance` `31349550174`: failure.
- Commit status `CodeRabbit`: success.
- PR #18: no submitted reviews; zero unresolved review threads.

## Known findings
- Exact head `6028b1d6...` is not green. Three acceptance workflows failed, including two previously passing regression surfaces plus the new destructive/lifecycle workflow.
- Failure ownership is not yet proven. Inspect job/step evidence before changing product code.
- No second active package lock or concurrent claimant was observed during reconciliation.

## Blocker
None verified. WP-05 remains actionable.

## Exact next action
Inspect failed workflow jobs for runs `31349550162`, `31349550199`, and `31349550174`; classify each failure owner; fix only the owning workflow/product component; rerun affected regressions; then continue the remaining WP-05 matrix. Do not begin WP-06.
