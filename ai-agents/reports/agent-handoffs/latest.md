# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Resume implementation/evidence head: `c1795e0fd11646e450e234616fa6fdcace9c71a7`

## Live reconciliation at resume
- Live `main` remains `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- PR #18 is the only open canonical work-package PR and is mergeable/draft at the observed head.
- WP-01 through WP-04 are recorded `COMPLETE`; their canonical branches are historical. WP-06 remains `BLOCKED` on the verified WP-05 production release.
- No submitted PR reviews and zero inline review threads were present at resume.
- Exact-head CI for `c1795e0fd11646e450e234616fa6fdcace9c71a7`: CI run `31235266874` completed `success`, including repository tooling, complexity, exact-head Codacy, WP-04 profile/package/reproducibility; WP-05 Floodgate regression run `31235266875` completed `success`; CodeRabbit commit status was `success`; Codacy PR summary reported zero new issues.

## Owner-approved scope
Real Microsoft/Xbox account authentication is out of scope. WP-05 must not request credentials/device-code sign-ins or claim authenticated Microsoft/Xbox coverage. Identity acceptance remains required at the server-visible boundary for Java UUID/name behavior, real Geyser/Floodgate `*`-prefixed server-visible identity, cached-offline/never-joined resolution, commands/GUI, delivery, audit, and distribution behavior. Disposable offline protocol clients and deterministic server-side test identities are permitted.

## Completed acceptance/evidence
- `ACC-ENV-001`: historical RC PASS; evidence commit `be8a3a4832dc6a78e918b39963a946731c22f624`; run `31217633117`.
- `ACC-OPS-001`: historical RC PASS; evidence commit `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`; corrected run `31218811889`.
- Confirmed Floodgate prefixed-name defect fixed at `e00035d937d8a7d51eb00484689c74dd1d6d394a` with static cleanup `ed52a32688329be931bc6fdfc5008b393a0f2ffb`; fixed-head live regression run `31222017554` passed.
- Full-inventory diagnostic run `31222689118` passed on a fixed head but remains diagnostic until rerun against the final candidate.
- Acceptance helper module is present and exact-head CI-clean at resume head `c1795e0fd11646e450e234616fa6fdcace9c71a7`.

Historical RC evidence does not satisfy the final release gate after the WP-05 code fix. The complete 35-case in-scope matrix must be rerun against the exact final WP-05 JAR after the last code change.

## Remaining acceptance criteria
- Execute every in-scope case in `docs/wp-05-manual-acceptance-matrix.md` under the owner-approved no-account-auth interpretation and commit permanent evidence.
- Fix every confirmed LoreItems defect in this package and add/rerun required regression coverage.
- Repeat the complete 35-case in-scope matrix against the exact final JAR with all cases PASS.
- Re-run the complete WP-04 automated verification/migration/failure/saturation/profile/package/reproducibility/static-analysis/Codacy gates on the final head.
- Finalize version `1.0.0`, release notes, checksums, CycloneDX SBOM, Gradle dependency manifest, upgrade/backup/restore/rollback rehearsal and acceptance index.
- Complete independent code review and separate evidence audit, with no requested changes and zero unresolved threads.
- Record owner/operator sign-off.
- Normally merge PR #18, verify live `main`, publish `v1.0.0` from the merge commit, and verify tag/assets/checksums.

## Known findings
- One production defect has been confirmed in WP-05 and is fixed/regression-verified: valid already-prefixed Floodgate names were rejected by recipient binding.
- Earlier protection/void diagnostic failures were harness-stimulus defects/inconclusive exploration, not confirmed LoreItems defects.
- The current container has no ordinary outbound DNS; GitHub-hosted runners remain the available disposable live Paper/Geyser/Floodgate environment.

## Progress
- Packages complete: 4/6.
- Packages remaining: 2/6.
- Weighted progress: 75%.
- WP-05 package credit remains zero until full completion.

## Exact next action
Continue WP-05 on PR #18 by turning the existing permanent acceptance helper and GitHub-hosted Paper/Geyser/Floodgate environment into durable coverage for the remaining matrix cases. Commit checkpoints after each coherent acceptance-harness/evidence section; any confirmed implementation defect stays in WP-05. Do not begin WP-06.
