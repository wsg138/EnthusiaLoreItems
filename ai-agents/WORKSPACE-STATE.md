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
- Initial IN_PROGRESS checkpoint: `5825c2ddc284300ec323a47d5d62b6bb9a8ac853`
- Prior blocker/review head: `ed869117dc449c0c96c824cf2668725ea711662b`
- Resume handoff commit: `a88bc75c289209f2b855eca7e80f77b7515ca111`
- Checkpointed implementation/evidence head before this blocker metadata: `5d64fba0451c0dd99f51e76d6f392b74109ba370`
- Dependency satisfied by: verified WP-04 RC `v1.0.0-rc.1`
- Current external blocker: no worker-controlled authenticated Java/Microsoft + Bedrock/Xbox acceptance sessions for the identity-sensitive live matrix.
- WP-04 implementation merge: `89399db2d92fd7197479a8803e920c02f5bec490`
- WP-04 release-recovery merge: `e4b7968adea1357e7307815a5a5ef7f456f16ad1`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`
- Latest package handoff: `ai-agents/reports/agent-handoffs/2026-08-07-wp-05-authenticated-session-blocker.md`

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | COMPLETE | PR #15 and release-recovery PR #16 normally merged; post-merge CI and RC prerelease verified |
| WP-05 | 15% | BLOCKED | authenticated Java/Microsoft and Bedrock/Xbox live sessions are externally unavailable; complete final matrix remains required |
| WP-06 | 10% | BLOCKED | WP-05 production release `v1.0.0` not verified |

## Progress
- Fixed packages: 6
- Completed: 4 of 6
- Remaining: 2 of 6
- Weighted progress: `75 / 100 = 75%`
- WP-05 accepted case count so far: 2 historical RC PASS cases. Partial case completion does not award package weight.
- WP-05 implementation defects found: 1; fixed and live-regression verified: 1.

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
- `ACC-ENV-001` — PASS against exact published RC. Durable evidence: `docs/wp-05-acceptance/ACC-ENV-001/`; evidence commit `be8a3a4832dc6a78e918b39963a946731c22f624`; run `31217633117`.
- `ACC-OPS-001` — PASS against exact published RC. Durable evidence: `docs/wp-05-acceptance/ACC-OPS-001/`; evidence commit `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`; corrected run `31218811889`.
- Those two cases are historical RC evidence only after the Floodgate defect fix; the WP-05 contract still requires the entire matrix to be repeated against the exact final fixed JAR before release approval.

## WP-05 confirmed defect and fix
- Real Geyser/Floodgate exposed a Bedrock player to Bukkit as `*Wp05BedrockBot`.
- The original RC recipient-binding worker treated that already-prefixed valid Floodgate name as invalid because it assumed `Player#getName()` was unprefixed and attempted to prepend `*` itself.
- Production fix commit: `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
- Static-analysis cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`.
- Live fixed-head regression readiness head: `9928e1f6f818c9c42fdbaa9778b475a0280d0f18`.
- Final live regression run `31222017554` completed successfully and the old recipient-binding rejection warning was absent for the real `*`-prefixed Bukkit join.
- Permanent defect report: `docs/wp-05-defects/floodgate-prefixed-recipient-binding.md`.

## WP-05 diagnostic-only evidence
- Java network-client diagnostic exercised create, adopt, online give, offline queued give, restart delivery, and second restart replay on the real command/inventory path; corrected run passed. Because the client used offline authentication, this is defect-finding evidence only and earns no acceptance credit.
- Full-inventory diagnostic run `31222689118` passed on fixed head: 36 occupied storage slots kept delivery pending, no overflow item entity was created, one opened slot/rejoin delivered exactly once, and another restart preserved one instance/one completed delivery with no duplicate or review-required state. Permanent diagnostic checkpoint commit: `02c78f6536678cea06f0dfc9a4692320726b14d9`. No acceptance credit because the client was offline-authenticated.
- Protection/void exploration runs `31223151624` and `31223376860` were inconclusive harness diagnostics. Real fire/lava portions held; the synthetic `/damage` explosion shortcut was not a faithful substitute for the required physical TNT/cactus case. No implementation defect and no acceptance PASS is claimed. The temporary automatic protection diagnostic workflow is removed in the blocker checkpoint.

## WP-05 remaining acceptance criteria
- Every other case in `docs/wp-05-manual-acceptance-matrix.md` remains uncredited.
- Establish faithful authenticated Java/Microsoft and Bedrock/Xbox player sessions and the cached-offline/never-joined identity conditions required by the matrix.
- Fix and regression-test every future confirmed implementation defect in this same package.
- Repeat the entire matrix against the exact final WP-05 JAR with every case PASS.
- Re-run full automated WP-04 CI/profile/migration/package/static-analysis/reproducibility gates on the final head.
- Complete independent code review and separate evidence audit with no requested changes or unresolved threads.
- Record owner/operator sign-off.
- Finalize `1.0.0`, merge normally, verify live `main`, publish `v1.0.0`, and verify tag target/assets/checksums.

## Tests and verification
- Pre-claim live `main` `476f9e5bbfa8155ab76b23bde0681ac35b92f177`: CI run `31215810485` successful; Release RC run `31215904779` successful.
- Prior blocker-review head `ed869117dc449c0c96c824cf2668725ea711662b`: CI run `31216903570` successful, including exact-head Codacy, profile, package validation, and reproducibility; external Codacy successful with no issues.
- `ACC-ENV-001`: Paper 1.21.11 build 116 + Java 21 + Geyser/Floodgate/ViaVersion + exact RC; WAL/integrity/FK/schema/baseline/clean-stop evidence audited PASS.
- `ACC-OPS-001`: exact RC degraded read-only failure injection through invalid SQLite path; public V1 consumer received `SERVICE_UNAVAILABLE`; healthy restart returned `UNKNOWN_DEFINITION`; WAL/integrity/FK clean; zero definitions/instances/direct deliveries; audited PASS.
- Floodgate fix: full Gradle suite, repository tooling, complexity, exact-head Codacy, deterministic profile, package validation and reproducibility passed around the fixed implementation; final live Geyser/Floodgate regression run `31222017554` passed.
- Full-inventory fixed-head diagnostic run `31222689118` passed; diagnostic-only for authentication reasons.

## Findings
- One LoreItems implementation defect has been confirmed in WP-05 and is fixed/regression-verified: already-prefixed real Floodgate names were rejected by the distribution recipient-binding adapter.
- Acceptance-harness defect: first `ACC-OPS-001` run `31218454541` incorrectly required zero `external_delivery_requests`. Production behavior intentionally persists `UNKNOWN_DEFINITION` outcomes for idempotency. Corrected by `c00271761e60446f4611706f6b70f3d00ccfde03`; corrected run passed.
- Earlier checkpoint-quality finding: initial blocker-state edits condensed WP-04 history; fixed at `ed869117dc449c0c96c824cf2668725ea711662b`. This workspace record preserves that history.
- Protection/void diagnostic failures are not classified as plugin defects because the synthetic explosion stimulus was not faithful to the manual matrix and the run was intentionally diagnostic-only.
- The local container lacks ordinary outbound DNS, but GitHub-hosted runners successfully provide the disposable networked Paper acceptance environment.
- No accessible authenticated Java/Microsoft or Bedrock/Xbox acceptance-account/session mechanism exists in the repository or available connectors. Real identity-sensitive cases must not be replaced by offline-mode bots, direct DB edits, or console-only simulation when the matrix requires actual player identity/physical behavior.

## Current blocker
WP-05 is blocked on an external acceptance-session dependency, not on source implementation work currently known to be broken. To resume, a future worker needs an approved mechanism to establish:
- one authenticated Java/Microsoft test session;
- one authenticated Bedrock/Xbox session through Geyser/Floodgate with the real `*`-prefixed server-visible identity;
- cached-offline and never-joined identity conditions required by the matrix.

Credentials must not be committed to the repository. Once those sessions are available, the same canonical branch/PR resumes and the status returns to `IN_PROGRESS`.

## Routing consequence
WP-05 remains the single unfinished canonical package, so the universal dispatcher must resume it before selecting new work. WP-06 remains `BLOCKED` until WP-05 is normally merged, verified on live `main`, and the production `v1.0.0` release/tag/assets are verified.

## Exact next action
On the next worker start, re-check live GitHub and the authenticated-session dependency. If faithful authenticated Java and Bedrock sessions are available, resume WP-05 on PR #18 and continue the full matrix. If the dependency is still unavailable, leave WP-05 `BLOCKED` and stop. Do not begin WP-06.
