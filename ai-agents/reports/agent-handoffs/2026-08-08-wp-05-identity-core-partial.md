# WP-05 identity/core live-acceptance partial checkpoint — 2026-08-08

## Package and routing
- Selected package: WP-05 — live acceptance and production release.
- Starting status: `IN_PROGRESS`.
- Ending status for this checkpoint: `PARTIAL`.
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Draft PR: #18, `WP-05: complete live acceptance and release LoreItems`.
- Starting live `main`: `476f9e5bbfa8155ab76b23bde0681ac35b92f177`.
- Session starting package head: `c1795e0fd11646e450e234616fa6fdcace9c71a7`.
- Coherent tested acceptance head: `c2e47825adca172db095ee9869b4cf0b0999f752`.
- WP-01..04 remain `COMPLETE`; WP-06 remains `BLOCKED`. No competing WP-06 lock existed in LoreItems or EnthusiaTags when reconciled.

## Completed acceptance criteria in this checkpoint
Seven of the 35 in-scope cases have permanent exact-head live PASS evidence on coherent head `c2e47825adca172db095ee9869b4cf0b0999f752`:
- `ACC-ID-001`
- `ACC-ID-002`
- `ACC-CORE-001`
- `ACC-CORE-002`
- `ACC-CORE-003`
- `ACC-CORE-004`
- `ACC-CORE-005`

Historical `ACC-ENV-001` and `ACC-OPS-001` PASS evidence is retained for traceability but is not counted toward the required final-head rerun after the WP-05 production fix.

## Permanent acceptance automation added
- `.github/workflows/wp05-java-core-acceptance.yml`: Java server-visible identity, permission surface, browse GUI, create, adopt, self give, online-target give, cached-offline queue, restart delivery, replay no-duplicate, physical PDC identity/max-stack observations, and SQLite integrity.
- `.github/workflows/wp05-core-005-acceptance.yml`: full inventory pending/no-overflow, one-slot recovery delivery, restart replay no-duplicate, SQLite integrity.
- `.github/workflows/wp05-floodgate-fix-regression.yml`: real Geyser/Floodgate connection with server-visible `*Wp05BedrockBot`, stable server-visible UUID within fixture, pregrant permission denial, granted browse GUI server-side view, exact-prefixed group recipient validation, and the original recipient-binding regression.
- `acceptance-harness`: added read-only `view` observation of Bukkit `InventoryView`; helper remains a separate test-only artifact and is not part of the production jar.

## Exact-head verification
For coherent acceptance head `c2e47825adca172db095ee9869b4cf0b0999f752`:
- Repository CI: run `31240091012` — `success`.
- Java identity/core acceptance: run `31240091014` — `success`.
- Full-inventory acceptance: run `31240091001` — `success`.
- Floodgate identity acceptance: run `31240091010` — `success`.
- The CI run includes repository tooling/tests, complexity, exact-head Codacy, deterministic WP-04 profile, package validation, and reproducibility.

## Harsh-review findings and fixes
Confirmed LoreItems production finding retained from earlier WP-05 work:
- Valid already-prefixed Floodgate names were rejected by recipient binding.
- Production fix: `e00035d937d8a7d51eb00484689c74dd1d6d394a`.
- Static cleanup: `ed52a32688329be931bc6fdfc5008b393a0f2ffb`.
- Historical live regression `31222017554` and current exact-head `ACC-ID-002` both pass.

Harness-only findings fixed during this checkpoint:
1. Mineflayer 4.35 tab completions return objects with `match` fields, and empty nonadmin completions can time out.
2. Shell command substitution made bot PIDs non-child processes, so invalid `wait` ownership caused a false workflow failure.
3. `bedrock-protocol` did not expose `container_open` for the Java-side GUI despite the GUI opening; the acceptance helper now observes the real Bukkit `InventoryView`.
4. `grep -v topSize=5` accidentally matched the prefix of `topSize=54`; replaced with an exact positive CHEST/54 assertion.

None of those four was a LoreItems production defect.

## Review state
At session resume PR #18 had no submitted reviews and zero inline review threads; CodeRabbit/status and Codacy were clean on the resume head. A final independent code review and separate evidence audit are still mandatory after the entire matrix is complete and must be rechecked on the final head.

## Remaining acceptance criteria
Exact-head live cases still required:
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
- fix/regression-test every future confirmed defect in this same package;
- rerun all 35 in-scope cases on the exact final jar after the last code change;
- rerun the full WP-04 automated migration/failure/saturation/profile/package/reproducibility/static-analysis/Codacy gates on final head;
- finalize `1.0.0`, release notes, SHA-256, CycloneDX SBOM, Gradle dependency manifest, acceptance index, upgrade/backup/restore/rollback rehearsal;
- independent harsh code review and separate evidence audit with no requested changes and zero unresolved threads;
- owner/operator sign-off recorded on GitHub;
- normal merge of PR #18, verify live `main`, publish `v1.0.0` from the merge commit, and verify tag/assets/checksums.

## Progress
- Completed packages: 4/6.
- Remaining packages: 2/6.
- Weighted progress: 75%.
- WP-05 package weight remains zero until the entire package contract completes.

## Precise reason for PARTIAL rather than COMPLETE/BLOCKED
Useful live acceptance automation and evidence is committed, but the remaining in-scope matrix and final release gates are not completed. No verified external dependency prevents further engineering work, so `BLOCKED` would be incorrect. The same fixed WP-05 branch/PR remains the durable claim.

## Exact next action
Resume this same `PARTIAL` WP-05 on PR #18. Build a consolidated disposable-server block for physical tracking, anomaly handling, destructive operations, stable API behavior, lifecycle recovery, and operational backup/recovery using real Bukkit/server event paths and read-only database assertions. Then complete editor, protection, distribution, environment/operations cases; rerun all 35 on the exact final jar; finish review/operator sign-off/release/merge/post-merge verification. Do not create a follow-up package and do not begin WP-06.
