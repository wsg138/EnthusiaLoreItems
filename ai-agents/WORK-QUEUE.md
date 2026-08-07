# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

When sources conflict, resolve them in this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | IN_REVIEW | Independent review completed; findings are being resolved on PR #14 |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-03 review checkpoint

- Branch: `agent/wp-03-mass-distributions`
- Pull request: #14, `WP-03: complete one-use mass distributions` (open, non-draft)
- Verified live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Worker starting head: `10cb131e93c4758cfe9f1e174e1400cb8d0b5ffc`
- Independent-review head: `b31be671905ad71ed7ab114de074d9d547517335`
- Latest completed remediation code head: `07de3058e9f7c42f8457b31d7e34d15a0ff071c6`
- Independent review: CodeRabbit run `fc10c8bf-f61f-4009-bde2-54620c4792d7`, 17 inline threads; six already auto-resolved after fixes at checkpoint preparation.
- Verification: review head passed Actions run `31169832579` plus external Codacy; remediation-head CI is pending and final exact-head verification remains required.
- Completed criteria: complete WP-03 implementation and author harsh review; substantive independent review; confirmed cancellation-recovery, lifecycle/threading, recovery-visibility, workflow-evidence, executor-isolation, and recovery-temp bounds fixes with focused regressions.
- Remaining criteria: resolve all review threads/nitpicks; fresh final independent review; `VERIFYING`; final exact-head Actions/Codacy; prospective completion state; normal merge; live-main/post-merge verification.
- Blocker: none.
- Exact next action: resolve the ten remaining review threads with fixing commits or evidence, reconcile review-body nitpicks, then request a fresh independent review on the final remediation head.

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Apply the authority order above before trusting a snapshot or handoff state.
3. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
4. Resume the single unfinished canonical package before selecting another. Canonical branch/PR presence outranks stale `READY` or `BLOCKED` text.
5. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
6. Never begin more than one package, and do not begin the next package after a completion merge in the same chat.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 2
- Remaining: 4
- Active unfinished package lock: WP-03 (`IN_REVIEW`)
- Weighted progress: `40 / 100 = 40%`

No additional weighted credit is awarded until WP-03 is normally merged and all required live verification is complete.