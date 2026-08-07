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
- Initial IN_PROGRESS checkpoint: `5825c2ddc284300ec323a47d5d62b6bb9a8ac853`
- Prior blocker/review head: `ed869117dc449c0c96c824cf2668725ea711662b`
- Resume handoff commit: `a88bc75c289209f2b855eca7e80f77b7515ca111`
- Latest audited evidence head before this checkpoint: `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`
- Dependency satisfied by: verified WP-04 RC `v1.0.0-rc.1`
- WP-04 implementation merge: `89399db2d92fd7197479a8803e920c02f5bec490`
- WP-04 release-recovery merge: `e4b7968adea1357e7307815a5a5ef7f456f16ad1`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Latest package handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-05-live-baselines.md`

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | COMPLETE | PR #15 and release-recovery PR #16 normally merged; post-merge CI and RC prerelease verified |
| WP-05 | 15% | IN_PROGRESS | exact RC live baselines `ACC-ENV-001` and `ACC-OPS-001` audited PASS; remaining full matrix still required |
| WP-06 | 10% | BLOCKED | WP-05 production release not verified |

## Progress
- Fixed packages: 6
- Completed: 4 of 6
- Remaining: 2 of 6
- Weighted progress: `75 / 100 = 75%`
- WP-05 accepted case count so far: 2. Partial case completion does not award package weight.

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

## Verification and release record
- Final WP-04 PR head `063ad63ee7341cc42a4f20c51883d5c34abd25a7`: Actions run `31204122398` completed `success`, including Gradle verification, repository tooling, new-code complexity, exact-head Codacy, deterministic profile, RC validation, immutable evidence packaging, and reproducibility. CodeRabbit status was `success`; PR #15 had zero review threads and no requested changes.
- PR #15 was normally merged as `89399db2d92fd7197479a8803e920c02f5bec490`.
- Post-merge `main` CI run `31204427939` completed `success` on that exact implementation merge.
- Initial release workflow validated the exact CI bundle and created `v1.0.0-rc.1` at `89399db2d92fd7197479a8803e920c02f5bec490`, but release creation hit immediate tag-visibility lag.
- Recovery PR #16 head `c0be8bf9755e7038f6a8a9f1feb715322136f3a4`: Actions run `31204825737` completed `success`, including exact-head Codacy; CodeRabbit was `success` and review threads were zero.
- PR #16 was normally merged as `e4b7968adea1357e7307815a5a5ef7f456f16ad1`; post-merge CI run `31205231097` completed `success`.
- Release workflow run `31205326905` completed `success`, detected the existing exact tag, verified it remained on `main`, selected the successful CI run for the tagged SHA, revalidated the immutable artifact bundle, and published without moving the tag or executing repository code.
- GitHub release `v1.0.0-rc.1` is `prerelease: true` and has `target_commitish` `89399db2d92fd7197479a8803e920c02f5bec490`.
- Verified assets: `EnthusiaLoreItems.jar`, `EnthusiaLoreItems.jar.sha256`, `bom.cyclonedx.json`, `gradle-dependencies.txt`, `normalized-entry-manifest.txt`, `wp04-profile.json`, `EnthusiaLoreItems-test-reports.tar.gz`.
- Released JAR digest: `sha256:3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`.

## WP-05 completed acceptance criteria
- `ACC-ENV-001` — PASS against exact RC. Durable evidence: `docs/wp-05-acceptance/ACC-ENV-001/`; evidence commit `be8a3a4832dc6a78e918b39963a946731c22f624`; run `31217633117`.
- `ACC-OPS-001` — PASS against exact RC. Durable evidence: `docs/wp-05-acceptance/ACC-OPS-001/`; evidence commit `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`; corrected run `31218811889`.

## WP-05 remaining acceptance criteria
- Every other case in `docs/wp-05-manual-acceptance-matrix.md` remains uncredited.
- Fix and regression-test every confirmed implementation defect in this same package.
- Repeat the entire matrix against the exact final WP-05 JAR with every case PASS.
- Re-run full automated WP-04 CI/profile/migration/package/static-analysis gates on the final head.
- Complete independent code review and separate evidence audit with no requested changes or unresolved threads.
- Record owner/operator sign-off.
- Finalize `1.0.0`, merge normally, verify live `main`, publish `v1.0.0`, and verify tag target/assets/checksums.

## Tests and verification
- Pre-claim live `main` `476f9e5bbfa8155ab76b23bde0681ac35b92f177`: CI run `31215810485` successful; Release RC run `31215904779` successful.
- Prior blocker-review head `ed869117dc449c0c96c824cf2668725ea711662b`: CI run `31216903570` successful, including exact-head Codacy, profile, package validation, and reproducibility; external Codacy successful with no issues.
- `ACC-ENV-001`: Paper 1.21.11 build 116 + Java 21 + Geyser/Floodgate/ViaVersion + exact RC; WAL/integrity/FK/schema/baseline/clean-stop evidence audited PASS.
- `ACC-OPS-001`: exact RC degraded read-only failure injection through invalid SQLite path; public V1 consumer received `SERVICE_UNAVAILABLE`; healthy restart returned `UNKNOWN_DEFINITION`; WAL/integrity/FK clean; zero definitions/instances/direct deliveries; audited PASS.

## Findings
- No LoreItems implementation defect has been confirmed by the two accepted cases.
- Acceptance-harness defect: first `ACC-OPS-001` run `31218454541` incorrectly required zero `external_delivery_requests`. Production behavior intentionally persists `UNKNOWN_DEFINITION` outcomes for idempotency. Corrected by `c00271761e60446f4611706f6b70f3d00ccfde03`; corrected run passed.
- Earlier checkpoint-quality finding: initial blocker-state edits condensed WP-04 history; fixed at `ed869117dc449c0c96c824cf2668725ea711662b`.
- The local container still lacks ordinary outbound DNS. GitHub-hosted runners successfully provide a disposable networked Paper acceptance environment for server-only cases.
- No accessible authenticated Java/Microsoft or Bedrock/Xbox test-account credentials were found in the repository or available connectors. Real player-session cases must not be replaced by offline-mode bots, direct DB edits, or console-only simulation when the matrix requires actual identity/physical player behavior.

## Current execution strategy
Keep using the exact RC and GitHub-hosted disposable server for cases whose behavior can be faithfully exercised without a player. Separately investigate whether authenticated Java and Bedrock/Floodgate client sessions can be established without weakening the matrix. Use non-authenticated network bots only as diagnostic defect-finding tools if useful; they do not count as acceptance evidence.

## Exact next action
Continue WP-05 only. Establish a faithful path for the required Java and Bedrock player sessions or exhaust remaining server-only cases. If an external authenticated-account/session dependency is then the sole barrier to further required work, record it precisely on this same branch/PR. Do not begin WP-06.
