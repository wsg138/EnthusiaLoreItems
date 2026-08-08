# Latest agent handoff

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`
- Session starting package head: `c1795e0fd11646e450e234616fa6fdcace9c71a7`
- Completed coherent acceptance-block head: `c2e47825adca172db095ee9869b4cf0b0999f752`

## Live reconciliation
- Live `main` at resume remained `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- PR #18 is the only open canonical work-package PR. WP-01 through WP-04 are `COMPLETE`; WP-06 remains `BLOCKED` on verified WP-05 production release.
- Cross-repository lock check found no open EnthusiaTags PR and no `agent/wp-06-loreitems-integration` branch.
- No submitted PR reviews and zero inline review threads were present at resume.

## Owner-approved scope
Real Microsoft/Xbox account authentication is out of scope. WP-05 must not request credentials/device-code sign-ins or claim authenticated Microsoft/Xbox coverage. Identity acceptance remains required at the server-visible boundary for Java UUID/name behavior, real Geyser/Floodgate `*`-prefixed server-visible identity, cached-offline/never-joined resolution, commands/GUI, delivery, audit, and distribution behavior. Disposable offline protocol clients and deterministic server-side test identities are permitted.

## Completed acceptance/evidence
Historical RC evidence retained for traceability but does not satisfy the final exact-head release gate:
- `ACC-ENV-001`: historical RC PASS; commit `be8a3a4832dc6a78e918b39963a946731c22f624`; run `31217633117`.
- `ACC-OPS-001`: historical RC PASS; commit `7a1a2a63a50cfe16905955e59d8f7fdcce035a59`; corrected run `31218811889`.

Exact-head coherent live block at `c2e47825adca172db095ee9869b4cf0b0999f752`:
- `ACC-ID-001` and `ACC-CORE-001` through `ACC-CORE-004`: PASS in Java identity/core run `31240091014`.
- `ACC-CORE-005`: PASS in full-inventory run `31240091001`.
- `ACC-ID-002`: PASS in real Geyser/Floodgate server-boundary identity run `31240091010` under the owner-approved no-account-auth interpretation.
- Repository CI run `31240091012`: PASS, including repository tooling, tests, complexity, exact-head Codacy, WP-04 profile/package/reproducibility checks.

The permanent acceptance workflows build the exact PR-head plugin, record source/JAR hashes and authentication mode, upload durable evidence, and rerun on every later PR-head commit. The complete 35-case in-scope matrix still must pass against the exact final WP-05 JAR after the last code change.

## Confirmed production findings
- One production defect has been confirmed in WP-05: valid already-prefixed Floodgate names were rejected by recipient binding. It was fixed at `e00035d937d8a7d51eb00484689c74dd1d6d394a`, static cleanup at `ed52a32688329be931bc6fdfc5008b393a0f2ffb`, with historical fixed-head regression run `31222017554`; current exact-head `ACC-ID-002` also passes.
- No additional LoreItems production defect was confirmed in this coherent acceptance block.

## Acceptance-harness findings fixed in this block
- Mineflayer 4.35 tab completion returns objects with `match` fields and can time out when a no-permission query has zero suggestions; normalized and treated empty timeout as empty suggestions.
- Bot processes started inside shell command substitution were not child processes of the calling shell; completion logic was changed so the test no longer attempts invalid `wait` ownership.
- The Bedrock protocol client did not emit a `container_open` packet for the Java-side inventory GUI even though the GUI opened; the test now observes the real server-side Bukkit `InventoryView` through the separate acceptance helper.
- A negative `grep` for `topSize=5` incorrectly matched the prefix of `topSize=54`; replaced with the positive exact expected `topType=CHEST topSize=54` assertion.

## Remaining acceptance criteria
Current exact-head live cases still required: `ACC-ENV-001`, `ACC-EDIT-001..003`, `ACC-TRACK-001..003`, `ACC-PROT-001..002`, `ACC-ANOM-001..002`, `ACC-DEST-001..004`, `ACC-DIST-001..005`, `ACC-API-001`, `ACC-LIFE-001..002`, and `ACC-OPS-001..005`.

Package-level gates still required:
- Execute all remaining in-scope cases and fix every confirmed LoreItems defect in this same package.
- Repeat the complete 35-case matrix against the exact final JAR with all cases PASS.
- Re-run the complete WP-04 automated verification/migration/failure/saturation/profile/package/reproducibility/static-analysis/Codacy gates on the final head.
- Finalize version `1.0.0`, release notes, checksums, CycloneDX SBOM, Gradle dependency manifest, upgrade/backup/restore/rollback rehearsal and acceptance index.
- Complete independent harsh code review and separate evidence audit, with no requested changes and zero unresolved threads.
- Record owner/operator sign-off.
- Normally merge PR #18, verify live `main`, publish `v1.0.0` from the merge commit, and verify tag/assets/checksums.

## Progress
- Packages complete: 4/6.
- Packages remaining: 2/6.
- Weighted progress: 75%.
- WP-05 package credit remains zero until full completion.

## Exact next action
Continue WP-05 on PR #18 with a consolidated disposable-server acceptance block for physical tracking, anomaly handling, destructive operations, stable API behavior, lifecycle recovery, and operational recovery/backup behavior. Use the separate helper only to drive/observe real Bukkit state, keep database access read-only for acceptance assertions, and keep any confirmed implementation defect in WP-05. Do not begin WP-06.
