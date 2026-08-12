# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | IN_PROGRESS | canonical PR #18 resumed after the trusted Sentinel host recovered admission capacity |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | requires verified WP-05 production `v1.0.0` release |

## Progress
- Completed: 4/6 packages.
- Remaining unfinished: 2/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no official weight until the full package, normal merge, post-merge verification and production release are verified.

## WP-05 live lock
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Canonical PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Status: `IN_PROGRESS`.
- Resume checkpoint predecessor head: `7830a35821727dc8e98802e9793571497a564c5f`.
- Live `main` at reconciliation: `70a636a25d12d755342d90d6846b86a0e56e865b`.
- Exact production JAR SHA-256 before this state-only checkpoint: `7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063`.
- Owner-approved identity scope amendment remains in force: real Microsoft/Xbox authentication is out of scope; server-visible Java/Floodgate identity behavior remains required.

## Resume evidence
On predecessor head `7830a358...`, canonical CI `31550935452`, all 22 dedicated WP-05 acceptance workflows, and external Codacy check `93973432101` completed successfully; Codacy reported zero annotations and combined CodeRabbit status is successful. PR #18 is open, non-draft and mergeable with zero unresolved inline review threads and no observed requested-changes review.

The former trusted Sentinel host memory blocker has cleared: automatic exact-head Sentinel reviewable/startup check `93973398929` completed successfully with `PAPER_SMOKE_OK` on `7830a358...`. That result proves a materially changed external condition. It does not replace the explicit production command/job/artifact evidence still required by the package contract.

This resume checkpoint creates a new exact head, so predecessor checks are historical for the successor and must be regenerated/verified before production Sentinel commands count.

## Remaining boundary
- Fresh exact-head full acceptance/CI/Codacy/release-binding/artifact/configuration evidence and review/thread reconciliation after this resume checkpoint.
- Explicit production Sentinel startup terminal `PAPER_SMOKE_OK`, then sequential restart terminal `PAPER_RESTART_OK`, both with complete exact-head evidence.
- Required prospective WP-05 `COMPLETE` / WP-06 `READY` state commit in PR #18 and fresh final-head verification.
- Current-main reconciliation, normal merge, post-merge main verification, automatic `v1.0.0` release verification, then durable completion and stop.

## Blocker
None.

## Exact next action
Re-fetch the canonical branch after the resume claim. Continue only if the exact head matches the claimed successor. Verify the successor's fresh exact-head repository gates and artifacts, then re-read live Sentinel policy/manifest/commands and execute the required production startup and restart command path sequentially. Do not begin WP-06.
