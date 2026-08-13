# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | COMPLETE | prospective package-branch state only; global completion still requires exact-head verification, Sentinel, normal merge, post-merge main CI, and verified production `v1.0.0` |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | READY | prospective unlock only; do not begin until WP-05's exact final state is merged and production `v1.0.0` is verified |

## Progress
- Prospective completed: 5/6 packages.
- Prospective weighted completed progress: 90%.
- Globally verified completed remains 4/6 packages / 75% until this exact final-state commit passes all remaining WP-05 gates and production release verification.

## WP-05 final package checkpoint
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Canonical PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Live `main` before this prospective-completion commit: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact implementation/evidence predecessor: `755b4ad5739fe8375930789a57cbfe617bbe01f8`.
- Exact predecessor CI: `31647453359`, job `94284217594`, `completed/success`.
- Exact predecessor plugin artifact: `9161310864`, `enthusialoreitems-plugin`, JAR `build/libs/EnthusiaLoreItems.jar`.
- Exact predecessor verification artifact: `9161310010`, `wp04-verification-755b4ad5739fe8375930789a57cbfe617bbe01f8`.
- All visible inline review threads are resolved and no submitted review is in `CHANGES_REQUESTED` state.
- Production `v1.0.0` tag/release remain absent before finalization.

## Owner review-gate override
The repository dispatcher normally requires an additional independent review after every fix. On 2026-08-12, the repository owner explicitly instructed the active worker to continue WP-05 without waiting for additional CodeRabbit capacity. This prospective state records that owner-authorized exception rather than claiming the refused incremental review passed.

The substantive CodeRabbit review through `8ab5eda7...` completed, its only latest finding was implemented in `755b4ad573...`, and exact-head CI/Codacy/reproducibility/artifact gates passed on that implementation head. No known unimplemented defect remains.

## Remaining boundary before global completion
1. Verify this prospective final-state head with exact-head CI/Codacy and all applicable repository-native checks.
2. Re-read live Sentinel policy, this exact head's `.enthusia-test.yml`, LoreItems staging docs, and current Staff-Staging command contract.
3. Run exact-head production Sentinel `startup` and require terminal `PAPER_SMOKE_OK`; then run sequential `restart` and require terminal `PAPER_RESTART_OK`.
4. Reconcile live `main`; if it moved, integrate it non-destructively and repeat any stale exact-head gates.
5. Normally merge PR #26 with GitHub's merge-commit method only.
6. Verify the resulting merge commit is live `main` and push-to-main CI succeeds.
7. Verify automatic production `v1.0.0` targets that exact merge, is non-draft/non-prerelease, and contains every required release asset/checksum/source binding.
8. Record durable global completion and stop without beginning WP-06.

## Blocker
None. The owner explicitly waived waiting for additional CodeRabbit review capacity for this package.

## Exact next action
Fast-forward this prospective-completion state from exact predecessor checkpoint `58d58e9201...`, re-fetch branch/PR for concurrency safety, then verify the newly triggered exact-head CI/Codacy gates. Do not begin WP-06.
