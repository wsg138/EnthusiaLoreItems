# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | IN_PROGRESS | fresh independent review ran through `6dcf8199...` and returned two actionable findings that are being remediated on the canonical branch |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | blocked until the WP-05 production `v1.0.0` release is verified |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 receives no global credit until the continuation is cleanly reviewed, final-head verified, normally merged, and production `v1.0.0` is verified.

## WP-05 canonical lock
- Branch: `agent/wp-05-live-acceptance-release`.
- Continuation PR: #26 — `WP-05: complete live acceptance and release LoreItems`.
- Live `main` at the review checkpoint: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Exact fresh-review predecessor: `6dcf8199cc8643b961d42f9cb36bf5e4d7a63ff5`.
- Exact retained implementation/evidence baseline: `2e8bc340e6e6d012c732889d50026da97f39d675`.
- Production `v1.0.0` tag and release were rechecked after resume and remain absent.

## Current review/fix state
- Fresh CodeRabbit review request comment: `5271038247`.
- Fresh review run `53b20eba-24bc-43fc-9440-ddf43834fc53` reviewed the previously uncovered range through `6dcf8199...` and reached terminal success status with two actionable findings.
- Finding 1: existing-release recovery must reject draft and prerelease releases before writing `released=true`.
- Finding 2: temporary resume handoff status was inconsistent with queue/workspace while the fresh review was still pending. Review is now terminal, so all records move consistently to `IN_PROGRESS` while its findings are fixed.
- Exact predecessor CI `31627583672`, job `94217890215`: `completed/success`, including Gradle verification, repository tooling, executable release-state regression, complexity, exact-head Codacy, deterministic profile, reproducibility, and artifacts.

## Remaining boundary
1. Verify this review-fix successor with exact-head CI/Codacy and executable draft/prerelease regression coverage.
2. Obtain a fresh independent review of the successor and resolve every actionable finding with zero unresolved threads.
3. Only when review is clean, commit prospective WP-05 `COMPLETE` / WP-06 `READY` as the final source-state commit.
4. Verify that final state SHA with exact-head CI/Codacy/review and explicit production Sentinel startup/restart.
5. Reconcile current `main`, normally merge PR #26, and verify post-merge main CI.
6. Verify automatic production `v1.0.0` publication from the exact final WP-05 merge with required production state, assets, checksums, and source binding.
7. Record global completion and stop without beginning WP-06.

## Blocker
None. The prior external review-quota blocker materially recovered; the package is actionable and remains `IN_PROGRESS` while fresh review findings are remediated.

## Exact next action
Fast-forward this review-fix checkpoint from exact predecessor `6dcf8199...`, re-fetch branch/PR for concurrency safety, then require exact-head CI and a fresh independent CodeRabbit review before any prospective completion state. WP-06 remains blocked.
