# Latest agent handoff

## Current package state
- WP-04 — automated production hardening and release candidate: `COMPLETE`.
- WP-05 — live acceptance and production release: `IN_PROGRESS` on draft PR #18 after resuming through GitHub-hosted disposable acceptance infrastructure.
- WP-06 — EnthusiaTags integration: `BLOCKED` on WP-05 production release.
- Current WP-05 handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-05-resumed-actions-harness.md`.

## Authoritative WP-05 facts
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Durable claim commit: `760f04f162b934d7a0f21ba8c354548aeb8cffbf`.
- Prior blocker/review head: `ed869117dc449c0c96c824cf2668725ea711662b`.
- Resume handoff commit: `a88bc75c289209f2b855eca7e80f77b7515ca111`.
- `v1.0.0-rc.1` is published as a GitHub prerelease targeting WP-04 implementation merge `89399db2d92fd7197479a8803e920c02f5bec490`.
- Released JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.
- The exact RC was materialized from GitHub Actions evidence and independently re-hashed to that digest.
- Prior exact-head CI/Codacy on `ed869117dc449c0c96c824cf2668725ea711662b` passed.
- No WP-05 manual/live case is credited PASS yet.

## Resume strategy
The original remote Minecraft/SSH connector blocker does not prevent all live execution: GitHub-hosted runners can provide a disposable Java 21 Paper 1.21.11 server and download the exact RC and required server plugins. WP-05 is therefore resumed as `IN_PROGRESS`.

`ACC-ENV-001` is the first executable case. The harness must use the exact RC, pin Paper 1.21.11 build 116, enable Geyser/Floodgate, capture exact dependency hashes/build metadata, startup/config/schema/integrity/WAL evidence, baseline durable counts, logs, and queue/admin evidence, and preserve raw artifacts for audit.

Real Java/Bedrock player-session cases remain unclaimed until faithful clients/accounts are established. No console-only shortcut may be treated as equivalent to a real player interaction where the matrix requires one.

## Completed acceptance criteria
None yet. Resume infrastructure is not acceptance evidence.

## Remaining acceptance criteria
The complete WP-05 contract remains: every live matrix case with permanent evidence, same-package defect fixes/regressions, final exact-jar all-PASS matrix, full automated/review/evidence gates, operator sign-off, `1.0.0`, normal merge/main verification, and verified production release/assets.

## Exact next action
Run and audit `ACC-ENV-001` against the exact RC on the GitHub-hosted disposable acceptance server, commit its evidence/result, and continue WP-05 only. Do not begin WP-06.
