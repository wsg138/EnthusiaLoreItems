# Latest agent handoff

## Current package state
- WP-04 — automated production hardening and release candidate: `COMPLETE`.
- WP-05 — live acceptance and production release: `IN_PROGRESS` on canonical draft PR #18.
- WP-06 — EnthusiaTags integration: `BLOCKED` on WP-05 production release.
- Current WP-05 handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-05-claim.md`.

## Authoritative WP-05 claim facts
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Durable claim commit: `760f04f162b934d7a0f21ba8c354548aeb8cffbf`.
- No competing LoreItems or EnthusiaTags open PR existed at claim time, and the WP-05/WP-06 canonical lock branches were absent before claim.
- Historical WP-01 through WP-04 package heads were verified as ancestors of current live `main`.
- Pre-claim live `main` CI run `31215810485` passed; Release RC run `31215904779` passed.
- `v1.0.0-rc.1` is published as a GitHub prerelease targeting WP-04 implementation merge `89399db2d92fd7197479a8803e920c02f5bec490`.
- Released JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.
- No WP-05 manual case has been executed or claimed as PASS by this package.
- Repository/issue search found no pre-existing GitHub-backed executed WP-05 case evidence.

## Completed acceptance criteria
None. Claim/reconciliation and RC metadata verification do not satisfy manual live acceptance.

## Remaining acceptance criteria
The complete WP-05 contract remains: full live matrix with permanent evidence, same-package confirmed-defect remediation and regressions, final exact-jar all-PASS matrix, full automated/review/evidence gates, operator sign-off, version `1.0.0`, normal merge/main verification, and verified production release/assets.

## Current boundary
The contract requires a designated Java 21 Paper/Leaf 1.21.11-compatible live acceptance server with Geyser/Floodgate and required Java, Bedrock, offline, and never-joined accounts. Access to that external environment must be verified before `ACC-ENV-001` or any subsequent case can be executed. No case may be waived, inferred from WP-04 automation, or marked PASS from documentation alone.

## Exact next action
Verify live acceptance-server/test-account access. If available, execute `ACC-ENV-001` against the exact RC and commit its complete evidence. If unavailable and no durable executed evidence exists, transition this same WP-05 package to `BLOCKED`, record the exact external dependency and resume condition on PR #18, and stop without starting WP-06.
