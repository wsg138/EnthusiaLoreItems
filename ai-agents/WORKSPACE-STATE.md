# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `PARTIAL`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Pull request: draft PR #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Session starting package head: `c1795e0fd11646e450e234616fa6fdcace9c71a7`
- Durable claim commit: `760f04f162b934d7a0f21ba8c354548aeb8cffbf`
- Initial IN_PROGRESS checkpoint: `5825c2ddc284300ec323a47d5d62b6bb9a8ac853`
- Prior blocker/review head: `ed869117dc449c0c96c824cf2668725ea711662b`
- Resume handoff commit: `a88bc75c289209f2b855eca7e80f77b7515ca111`
- Owner-approved account-auth scope amendment: `dc6d6dc01c9a678316e6a0120dd115551f9ca491`
- Account-auth probe removal: `3bd8326c8c9280d338831cfc4c3cc143d41278b3`
- This session resume checkpoint: `283b7ec5cbb24fe00672264f49aad946e593f2dc`
- Coherent identity/core evidence head: `c2e47825adca172db095ee9869b4cf0b0999f752`
- Identity/core checkpoint: `324c8dc3e74b0309520939350c803a7305a5bda1`
- Dependency satisfied by: verified WP-04 RC `v1.0.0-rc.1`
- WP-04 implementation merge: `89399db2d92fd7197479a8803e920c02f5bec490`
- WP-04 release-recovery merge: `e4b7968adea1357e7307815a5a5ef7f456f16ad1`
- Exact RC JAR SHA-256: `3c7b6aa74ee63a4e049c5e09f2bebffe78bf50ea88caaaa3d03b55e941f427c8`

## Owner-approved WP-05 scope amendment
On 2026-08-07 the project owner explicitly removed real Minecraft account authentication from WP-05 acceptance. WP-05 no longer requires Microsoft/Xbox credentials, device-code sign-ins, entitlement verification, or authenticated Java/Bedrock account sessions.

Identity-sensitive acceptance remains required at the server boundary: Java UUID/name semantics, Geyser/Floodgate `*`-prefixed Bedrock identity handling, cached-offline/never-joined resolution, commands/GUI, delivery, audit, and distribution behavior. Disposable protocol clients and deterministic server-side test identities are acceptable. Evidence must disclose authentication mode and must not claim Microsoft/Xbox authentication coverage.

This amendment removed the prior external blocker. It does not convert unexecuted cases to PASS and does not waive implementation defects.

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| WP-04 | 15% | COMPLETE | PR #15 and release-recovery PR #16 normally merged; post-merge CI and RC prerelease verified |
| WP-05 | 15% | PARTIAL | PR #18 retains the durable claim; exact-head identity/core block passes, remaining amended matrix/release work is unfinished |
| WP-06 | 10% | BLOCKED | WP-05 production release `v1.0.0` not verified |

## Progress
- Fixed packages: 6
- Completed: 4 of 6
- Remaining: 2 of 6
- Weighted progress: `75 / 100 = 75%`
- WP-05 exact-head live PASS cases checkpointed: 7 of 35 (`ACC-ID-001`, `ACC-ID-002`, `ACC-CORE-001..005`).
- Historical `ACC-ENV-001` and `ACC-OPS-001` remain traceability evidence only until the required final-head rerun.
- Partial case completion does not award package weight.
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
- Executable WP-05 manual acceptance cases and evidence requirements are complete at `docs/wp-05-manual-acceptance-matrix.md`, with account-auth prerequisites superseded by the WP-05 owner-approved scope amendment.

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

## WP-05 completed live acceptance evidence
Historical traceability evidence:
- `ACC-ENV-001` — historical RC PASS at `be8a3a4832dc6a78e918b39963a946731c22f624`, run `31217633117`.
- `ACC-OPS-001` — historical RC PASS at `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`, corrected run `31218811889`.
- These must be rerun on the final fixed JAR and are not counted in the 7 current exact-head PASS cases.

Coherent exact-head live block at `c2e47825adca172db095ee9869b4cf0b0999f752`:
- `ACC-ID-001` and `ACC-CORE-001..004` — PASS, Java identity/core run `31240091014`.
- `ACC-CORE-005` — PASS, full-inventory run `31240091001`.
- `ACC-ID-002` — PASS, real Geyser/Floodgate server-boundary identity run `31240091010` under the owner-approved no-account-auth interpretation.
- Repository CI `31240091012` — PASS, including repository tooling, full tests, complexity, exact-head Codacy, deterministic WP-04 profile, package validation, and reproducibility.
- Permanent workflows build the exact PR head, record source/JAR hashes and auth mode, and upload GitHub Actions evidence.

## WP-05 confirmed defect and fix
- Real Geyser/Floodgate exposed a Bedrock player to Bukkit as `*Wp05BedrockBot`.
- The original RC recipient-binding worker treated that already-prefixed valid Floodgate name as invalid because it assumed `Player#getName()` was unprefixed and attempted to prepend `*` itself.
- Production fix commit: `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
- Static-analysis cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`.
- Final historical live regression run `31222017554` succeeded; current exact-head `ACC-ID-002` also passes.
- Permanent defect report: `docs/wp-05-defects/floodgate-prefixed-recipient-binding.md`.

## Acceptance-harness findings fixed this session
- Mineflayer 4.35 tab-completion results are objects with `match` fields and no-permission empty results may time out; the workflow now normalizes results and treats that timeout as an empty completion set.
- Shell bot processes launched in command substitution were not children of the calling shell; completion handling was corrected.
- `bedrock-protocol` did not expose a `container_open` packet for the Java-side GUI despite the GUI opening; a test-only helper action now observes the real server-side Bukkit `InventoryView` instead.
- A negative `grep` for `topSize=5` incorrectly matched `topSize=54`; replaced with the exact positive `topType=CHEST topSize=54` assertion.
- These were harness defects only. No additional LoreItems production defect was confirmed by this block.

## WP-05 diagnostic evidence retained for future design
- Full-inventory historical diagnostic run `31222689118` passed on a fixed head and informed permanent `ACC-CORE-005`, which now passes exact-head.
- Protection/void exploration runs `31223151624` and `31223376860` remain inconclusive only. Fire/lava portions held, but synthetic `/damage` explosion was not a faithful matrix substitute; no LoreItems defect and no PASS is claimed.
- The removed authenticated-session probe is not acceptance evidence. Its device-code workflow was retired after the owner removed real-account authentication from scope.

## WP-05 remaining acceptance criteria
Current exact-head live cases still required:
- `ACC-ENV-001`
- `ACC-EDIT-001..003`
- `ACC-TRACK-001..003`
- `ACC-PROT-001..002`
- `ACC-ANOM-001..002`
- `ACC-DEST-001..004`
- `ACC-DIST-001..005`
- `ACC-API-001`
- `ACC-LIFE-001..002`
- `ACC-OPS-001..005`

Package-level gates still required:
- Fix and regression-test every future confirmed implementation defect in this same package.
- Repeat the complete 35-case in-scope matrix on the exact final JAR after the last code change, with every case PASS.
- Re-run full WP-04 automated migration/failure/saturation/profile/package/reproducibility/static-analysis/Codacy verification on final head.
- Finalize version `1.0.0`, release notes, SHA-256, CycloneDX SBOM, Gradle dependency manifest, acceptance index, upgrade/backup/restore/rollback rehearsal.
- Independent harsh code review and separate evidence audit; no requested changes; zero unresolved threads.
- Owner/operator sign-off recorded on GitHub.
- Normal merge of PR #18, post-merge live `main` verification, then `v1.0.0` tag/release from the merge commit with verified assets/checksums.

## Findings
- One LoreItems implementation defect has been confirmed in WP-05 and is fixed/regression-verified: already-prefixed real Floodgate names were rejected by the distribution recipient-binding adapter.
- First historical `ACC-OPS-001` run `31218454541` was a harness failure because it wrongly required zero `external_delivery_requests`; corrected production-aware assertion passed.
- Protection/void historical diagnostic failures are not plugin defects because the synthetic explosion stimulus was not faithful to the manual matrix.
- Historical account-auth probe lifecycle bug was test-only and the workflow is removed because account authentication is out of scope.
- The local container lacks ordinary outbound DNS; GitHub-hosted runners are the working disposable networked Paper acceptance environment.

## Current status
WP-05 is `PARTIAL`, not blocked and not complete. The same branch and draft PR retain the durable claim. Useful current-head acceptance automation/evidence is committed, but the remaining matrix, final-head rerun, review, operator sign-off, release, merge, and post-merge verification are unfinished. WP-06 stays `BLOCKED`.

## Routing consequence
The universal dispatcher must resume this `PARTIAL` WP-05 before selecting any new work. Do not create a follow-up package or begin WP-06.

## Exact next action
Resume PR #18 and build a consolidated disposable-server acceptance block for physical tracking, anomaly handling, destructive operations, stable API behavior, lifecycle recovery, and operational recovery/backup behavior. Drive real Bukkit/server events through the separate acceptance helper/protocol clients, keep database inspection read-only, and classify any real mismatch as WP-05 work. Then continue editor/protection/distribution/environment/operations cases, repeat all 35 on the exact final JAR, finish review/sign-off/release/merge/post-merge gates, and only then unlock WP-06.
