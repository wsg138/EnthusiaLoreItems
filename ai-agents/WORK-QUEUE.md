# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | canonical PR #18 is complete through repository-native final verification; required production Sentinel startup is blocked by trusted host memory admission |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | requires verified WP-05 production `v1.0.0` release |

## Progress
- Completed: 4/6 packages.
- Remaining unfinished: 2/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no official weight until the full package, normal merge, post-merge verification and production release are verified.

## WP-05 live lock
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Canonical PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Status: `BLOCKED`.
- Latest fully verified implementation/evidence head before the blocker checkpoint: `7345f4c12d7820fb1af773b98cccd4d3289611a2`.
- Exact production JAR SHA-256: `7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`.
- Permanent handoff: `ai-agents/reports/agent-handoffs/2026-08-11-wp-05-sentinel-resource-blocked.md`.
- Owner-approved identity scope amendment remains in force: real Microsoft/Xbox authentication is out of scope; server-visible Java/Floodgate identity behavior remains required.

## Current WP-05 evidence summary
On `7345f4c1...`, all applicable WP-05 acceptance workflows, canonical CI and exact-head external Codacy completed successfully. The separate 35-case evidence audit is committed, PR #25 independent final-delta review is fully dispositioned/resolved, the hardened configuration-evidence gate is directly proven, and the release evidence binds the exact source/JAR identity. PR #18 has no requested changes and zero unresolved inline review threads.

The required explicit Sentinel startup command was posted in PR #18 comment `5260542762`. Sentinel job `130` / check `93971143685` remained `AUTHORIZED — QUEUED` at queue position 1 because the trusted host had less than the configured 700 MB available-memory threshold for roughly ten minutes; last observed available memory was approximately 596 MB. The test never reached Paper startup and does not count as PASS. The resource threshold was not bypassed or weakened and no duplicate command was issued.

The blocker checkpoint commit itself changes the exact branch SHA, so `7345f4c1...` verification is historical evidence after the checkpoint. Resume must reconcile and re-run/verify the applicable exact-head gates before Sentinel is retried.

## Remaining boundary
- Restore trusted Sentinel host admission availability.
- Fresh exact-head acceptance/CI/Codacy/release-binding verification after this checkpoint.
- Sequential production Sentinel startup PASS then restart `PAPER_RESTART_OK`.
- Required final prospective WP-05 `COMPLETE` / WP-06 `READY` state commit in PR #18 and fresh final-head verification.
- Current-main reconciliation, normal merge, post-merge main verification, automatic `v1.0.0` release verification, then stop.

## Exact next action
Resume canonical PR #18 when the Sentinel host again satisfies trusted admission. Reconcile the live checkpoint head and fresh checks, re-read current Sentinel policy/manifest/commands, obtain exact-head startup and restart terminal success, then finish the prospective state/merge/release sequence. Do not begin WP-06.
