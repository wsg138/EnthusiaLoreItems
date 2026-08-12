# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-05 — live acceptance and production release
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-05-live-acceptance-release`
- Prior package PR: PR #18, normally merged as `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- The same canonical package lock was fast-forwarded to that verified merge commit after post-merge release finalization failed. A continuation PR using the contract's exact title will be opened from this same branch after this recovery checkpoint creates a diff.
- Live `main`: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- WP-06 is `BLOCKED` again because WP-05 production `v1.0.0` is not verified. Do not begin WP-06.

## Package registry
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | normally merged and verified |
| WP-04 | 15% | COMPLETE | normally merged; RC prerelease verified |
| WP-05 | 15% | IN_PROGRESS | post-merge production Release workflow is reproducibly failing before publication; same package resumed for the confirmed finalization defect |
| WP-06 | 10% | BLOCKED | requires verified WP-05 production `v1.0.0` release |

- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- No WP-05 weight is globally awarded until production `v1.0.0` is verified.

## Verified retained evidence
- PR #18 merged normally to live `main` as merge commit `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Final PR head `1243bd354d351d7e22947d51dc9068e54df88190` had canonical CI `31558740616`, all 22 dedicated WP-05 acceptance workflows, external Codacy, review/thread gates, and exact production Sentinel startup/restart PASS.
- Final-head Sentinel startup: source `5261781037`, response `5261784315`, check `93998041138`, job `138`, terminal `PAPER_SMOKE_OK`, artifact `9126982043` / `enthusialoreitems-plugin` / `build/libs/EnthusiaLoreItems.jar`.
- Final-head Sentinel restart: source `5261831748`, response `5261834047`, job `139`, terminal `PAPER_RESTART_OK`, same exact CI/artifact binding.
- Live push-to-main CI `31559889210`, job `93999879800`, exact merge SHA `82429ec2...`: `completed/success`, including Gradle verification, repository tooling, deterministic profile, final artifact validation, immutable release evidence, reproducibility, and artifact publication.
- `v1.0.0` tag is absent and `v1.0.0` release is absent.

## Confirmed defect
Production Release workflow run `31560031191` failed twice on exact merge SHA `82429ec2...` before any tag/release mutation:

- attempt 1 job `94000290257`: `Resolve publication state` failed;
- unchanged retry attempt 2 job `94000725832`: same step failed identically;
- all later release steps were skipped on both attempts;
- live `main` remained exactly the event target, while both final tag and release remained absent.

Root cause is the missing-tag probe in `.github/workflows/release.yml`: it intentionally suppresses the Git-ref API error but discards the command exit status and then treats any non-empty captured filtered output as proof the tag exists. The reproducible missing-tag path therefore can enter the tag-exists branch with a non-tag value and fail the target-SHA equality test before state outputs are emitted. Tag existence must be decided by the API command's success status instead.

This is a WP-05 release-finalization defect, not an external blocker. The package contract requires confirmed defects to remain in WP-05 and receive automated regression coverage.

## Remaining acceptance criteria
1. Fix the release resolver on this same canonical branch so a missing production tag is identified by API command success/failure, while preserving exact-tag recovery and immutable existing-release behavior.
2. Add repository-native automated regression coverage for missing-tag, existing-exact-tag, and immutable existing-release publication-state decisions.
3. Re-run exact-head repository gates/review and, because the final package head changes, refresh required exact-head Sentinel startup/restart evidence before the continuation merge.
4. Normally merge the continuation PR from the same canonical branch.
5. Verify the new final WP-05 merge commit on live `main` and successful push-to-main CI.
6. Verify automatic production Release succeeds, `v1.0.0` targets that verified final merge commit, and every required asset/checksum is present.
7. Record global WP-05 `COMPLETE`, WP-06 `READY`, 5/6 packages / 90%, then stop without starting WP-06.

## Blocker
None.

## Exact next action
Open the continuation PR from this same canonical branch after this checkpoint, inspect existing workflow-validation tooling, fix only the release publication-state resolver with regression coverage, and run the exact-head package gates. Do not begin WP-06.
