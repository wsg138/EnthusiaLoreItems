# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `BLOCKED`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Durable claim commit: `760f04f162b934d7a0f21ba8c354548aeb8cffbf`
- IN_PROGRESS checkpoint: `5825c2ddc284300ec323a47d5d62b6bb9a8ac853`
- Dependency satisfied by: verified WP-04 RC `v1.0.0-rc.1`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Latest package handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-05-live-environment-blocker.md`

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | COMPLETE | PR #15 and release-recovery PR #16 normally merged; post-merge CI and RC prerelease verified |
| WP-05 | 15% | BLOCKED | contract-required designated live acceptance environment/test accounts are not accessible to this worker; no executed GitHub-backed matrix evidence exists |
| WP-06 | 10% | BLOCKED | WP-05 production release not verified |

## Progress
- Fixed packages: 6
- Completed: 4 of 6
- Remaining: 2 of 6
- Weighted progress: `75 / 100 = 75%`

## WP-05 completed acceptance criteria
None. Package routing, claim, RC metadata verification, and external-dependency verification do not count as manual live acceptance.

## WP-05 remaining acceptance criteria
- Execute every manual case in `docs/wp-05-manual-acceptance-matrix.md` against the exact RC with complete GitHub-backed evidence.
- Fix and regression-test every confirmed defect in this same package.
- Repeat the entire matrix against the exact final WP-05 JAR with every case PASS.
- Re-run full automated WP-04 CI/profile/migration/package/static-analysis gates on the final head.
- Complete independent code review and separate evidence audit with no requested changes or unresolved threads.
- Record owner/operator sign-off.
- Finalize `1.0.0`, merge normally, verify live `main`, publish `v1.0.0`, and verify tag target/assets/checksums.

## Tests and verification
- Pre-claim live `main` `476f9e5bbfa8155ab76b23bde0681ac35b92f177`: CI run `31215810485` successful; Release RC run `31215904779` successful.
- GitHub prerelease `v1.0.0-rc.1` directly verified with target `89399db2d92fd7197479a8803e920c02f5bec490` and JAR digest `sha256:3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.
- PR #18/head `5825c2ddc284300ec323a47d5d62b6bb9a8ac853` started Actions CI run `31216625563`; it was still in progress when the external blocker was confirmed and is superseded by the blocker checkpoint commit.
- No WP-05 manual case is claimed as run or PASS.
- No local build result is claimed because this execution environment cannot reach GitHub for a dependency-capable checkout.

## External blocker
WP-05 requires a designated Java 21 Paper/Leaf 1.21.11-compatible live acceptance server with Geyser/Floodgate and Java, Bedrock, offline, and never-joined test accounts. The available runtime exposes no remote-server/SSH/deployment tool, the repository contains no server-access handoff, repository/issue search contains no executed WP-05 matrix evidence, and plugin discovery found no matching installable remote-server connector. The physical, restart, Bedrock, backup/restore, rollback, saturation, and player-interaction cases therefore cannot be executed or honestly marked PASS from this worker.

## Resume condition
Make the designated acceptance environment and required test accounts operable by this worker, or commit durable exact-RC executed-case evidence that can be independently audited. Resume the same canonical branch and draft PR #18; do not create a new package or subdivision.

## Exact next action
When the external dependency is available, re-reconcile live GitHub and PR #18, verify the exact RC digest again, execute `ACC-ENV-001`, commit its full evidence, and continue WP-05 only. Do not begin WP-06.
