# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | every known review finding is fixed and exact-head CI/Codacy pass, but CodeRabbit refused the mandatory fresh review of the latest one-file security-test fix at its review quota |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | blocked until the WP-05 production `v1.0.0` release is verified |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no global credit while the independent-review, final-state, Sentinel, merge, and production-release gates remain incomplete.

## WP-05 canonical lock
- Branch: `agent/wp-05-live-acceptance-release`.
- Continuation PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Live `main`: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact latest implementation/evidence head: `755b4ad5739fe8375930789a57cbfe617bbe01f8`.
- Production `v1.0.0` tag/release remain absent.

## Completed latest-head evidence — `755b4ad573...`
- Fresh CodeRabbit review run `3e0daa80-66f8-4027-bee1-9cc96856b002` covered the continuation through `8ab5eda7...` and reported no merge-blocking risk plus one actionable minor security/test-hardening follow-up.
- `755b4ad573...` implements that follow-up by explicitly asserting the trusted `workflow_run` trigger, successful conclusion, push event, main branch, exact `head_sha` assignment, and no `actions/checkout` inside the privileged release job.
- Canonical CI `31647453359`, job `94284217594`: `completed/success`.
- Exact CI includes Gradle `clean check`, repository tooling, release publication-state regression, complexity, exact-head Codacy, deterministic profile, immutable release evidence, clean rebuild/reproducibility, and artifact publication.
- Plugin artifact: `9161310864`, `enthusialoreitems-plugin`, digest `sha256:ed681f2949eff906f9e8c09de82bcbf31092ecd0d99c889b4c24f945fbdd2d55`, manifest JAR path `build/libs/EnthusiaLoreItems.jar`.
- Verification artifact: `9161310010`, `wp04-verification-755b4ad5739fe8375930789a57cbfe617bbe01f8`, digest `sha256:ea683d9ec1033e5b7a33abb87e9c6c7e71bb83488d27c363a7cc03f7bbf7fc21`.
- All visible inline review threads are resolved; no submitted `CHANGES_REQUESTED` review is known.

## External review blocker
CodeRabbit attempted the required incremental review of `8ab5eda7... → 755b4ad573...` as run `fdfd8d03-610f-4087-b45b-d35450d40aee`, selecting only `tools/test_release_publication_state.py`. Its live summary comment `5261960978` reports `Review limit reached` and says the next review is available in 53 minutes.

The WP-05 contract requires independent review of every code fix. The older completed review, CI/Codacy success, a CodeRabbit commit status, or this worker's own inspection cannot substitute for the refused review of the latest fix.

## Remaining boundary
1. After CodeRabbit review capacity materially changes, obtain a fresh review covering exact latest implementation head `755b4ad573...`; resolve any new actionable finding and require zero unresolved threads.
2. Only after review is terminal clean, commit prospective WP-05 `COMPLETE` / WP-06 `READY` as the final source-state commit.
3. Verify that final source head with fresh exact-head CI/Codacy/review and zero unresolved threads.
4. Re-read live Sentinel policy, final-head manifest, LoreItems staging docs, and Staff-Staging command contract; run final-head production `startup` then `restart` to their required terminal PASS codes.
5. Reconcile live `main`; if it moved, merge it non-destructively and repeat stale exact-head gates.
6. Normally merge PR #26 with GitHub merge-commit method only and verify post-merge main CI.
7. Verify automatic production `v1.0.0` targets the exact merge, is non-draft/non-prerelease, and contains every required asset/checksum/source binding.
8. Record durable global completion and stop without beginning WP-06.

## Blocker
Verified external dependency: CodeRabbit review quota refused the required review of the latest one-file fix (`755b4ad573...`), after all other currently actionable exact-head gates passed.

## Exact next action
Re-fetch PR #26 after CodeRabbit review capacity materially changes. If the canonical branch still checkpoints `755b4ad573...` as the latest implementation head, request a fresh review of that delta. Do not create prospective completion state, merge, run final Sentinel, or begin WP-06 before that review is terminal clean.
