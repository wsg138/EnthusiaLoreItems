# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Durable claim commit: `760f04f162b934d7a0f21ba8c354548aeb8cffbf`
- Dependency satisfied by: verified WP-04 RC `v1.0.0-rc.1`
- WP-04 implementation merge: `89399db2d92fd7197479a8803e920c02f5bec490`
- WP-04 release-recovery merge: `e4b7968adea1357e7307815a5a5ef7f456f16ad1`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Latest package handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-05-claim.md`

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | COMPLETE | PR #15 and release-recovery PR #16 normally merged; post-merge CI and RC prerelease verified |
| WP-05 | 15% | IN_PROGRESS | canonical branch and draft PR #18 established; exact RC verified; live acceptance prerequisite/evidence verification underway |
| WP-06 | 10% | BLOCKED | WP-05 production release not verified |

## Progress
- Fixed packages: 6
- Completed: 4 of 6
- Remaining: 2 of 6
- Weighted progress: `75 / 100 = 75%`

## WP-04 completed acceptance evidence
- Deterministic SQLite failure-injection/restart coverage exists for direct/API delivery crash boundaries, with fixed-seed stateful recovery coverage guarding no duplicate physical side effects after ambiguous or terminal outcomes.
- Historical schema V1 through V7 upgrade through the production migration runner is verified with identity/audit/pending/deleted-marker/campaign preservation, integrity/foreign-key checks, WAL/busy-timeout expectations, required indexes, interrupted migration rollback, and retry.
- Required queue/executor/cache/debounce/backlog surfaces have deterministic bounded-capacity, saturation, rejection/defer, and recovery coverage; natural-access debounce is production-wired and size-capped.
- Reload/shutdown lifecycle guards are automated: bounded lifecycle queue, stopping/unavailable service state, service unregister, pending-reload failure, runtime drain, executor shutdown, and atomic configuration replacement.
- Public Bukkit service V1 binary/source shape is pinned, implementation behavior is tested, and public API/operator documentation is present.
- The fixed profile covers 100 online players, 25,000 tracked instances, 5,000 loaded container/display scopes, 10,000 pending mixed mutations, 10 campaigns with 2,000 recipients each, and 100 simultaneous administrative queries. Its committed/released profile passed all configured queue and main-thread thresholds.
- Static analysis and complexity gates pass without broad suppression on the final package head.
- RC packaging produces version `1.0.0-rc.1`, shaded JAR, CycloneDX JSON SBOM, dependency manifest, SHA-256 checksum, normalized entry manifest, raw test reports, profile evidence, and release notes; two clean builds matched by full JAR checksum and normalized contents.
- Operator installation/upgrade/configuration/backup/restore/rollback/degraded recovery/incident guidance is complete at `docs/operator-guide.md`.
- Executable WP-05 manual acceptance cases and evidence requirements are complete at `docs/wp-05-manual-acceptance-matrix.md`.

## WP-05 completed acceptance criteria
None. Package claim, routing reconciliation, and RC metadata verification do not substitute for executed live acceptance.

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
- No WP-05 manual case is claimed as run or PASS.
- No local build result is claimed because this execution environment cannot reach GitHub for a dependency-capable checkout.

## Known findings
- No competing unfinished canonical package lock exists.
- No committed WP-05 executed-case evidence or WP-05 live-acceptance issue was found during repository reconciliation.
- The package contract requires a designated Java 21 Paper/Leaf 1.21.11-compatible live acceptance server with Geyser/Floodgate and Java, Bedrock, offline, and never-joined test accounts. This worker must directly verify access before any case can be executed or claimed.

## Blocker
None declared at this checkpoint. Lack of the required designated live acceptance environment, if confirmed, is an external dependency and must transition this same WP-05 package to `BLOCKED` rather than creating follow-up work.

## Exact next action
Verify live acceptance-server and test-account access. If available, execute `ACC-ENV-001` using the exact released RC and commit evidence. If unavailable and no durable executed evidence exists, commit WP-05 as `BLOCKED` with the exact required handoff and stop. Do not start WP-06.
