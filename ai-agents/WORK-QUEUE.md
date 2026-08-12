# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency / routing |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | normally merged and verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | normally merged and verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | normally merged and verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | COMPLETE | normally merged and verified; RC prerelease verified |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | IN_PROGRESS | post-merge release publication failed reproducibly; same canonical package resumed for the release-resolver defect |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | requires verified WP-05 production `v1.0.0` release |

## Progress
- Globally verified completed: 4/6 packages.
- Weighted completed progress: 75%.
- WP-05 remains incomplete despite the first normal merge because its required production release is absent.

## WP-05 recovery lock
- Canonical branch: `agent/wp-05-live-acceptance-release`.
- Prior canonical PR #18 normally merged as `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Canonical branch was non-force fast-forwarded to that merge commit for same-package recovery.
- Live `main`: `82429ec2c4e7309ae6f11adbacab5e8386cc1ece`.
- Push-to-main CI `31559889210`: success.
- Production Release run `31560031191`: failure on attempts 1 and 2 before tag/release creation.
  - attempt 1 job `94000290257` failed `Resolve publication state`;
  - attempt 2 job `94000725832` failed the same step unchanged.
- `v1.0.0` tag: absent.
- `v1.0.0` release: absent.

## Confirmed release-finalization defect
`.github/workflows/release.yml` uses a missing-tag `gh api` probe whose error is suppressed with `|| true`, then infers tag existence from whether the captured filtered output is non-empty. The failed API command's exit status is therefore lost, so the missing-tag path can be misclassified and fail the tag SHA assertion. The resolver must branch on the API command success status directly and keep exact-tag/release immutability checks intact.

The package contract requires this confirmed defect and its automated regression coverage to remain in WP-05. It is not a reason to begin WP-06.

## Exact next action
Create the recovery checkpoint on the canonical branch, open a continuation PR with the contract's exact title, add the smallest release-resolver fix plus regression coverage, and regenerate exact-head gates. WP-06 remains blocked.
