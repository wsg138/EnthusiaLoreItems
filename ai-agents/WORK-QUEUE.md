# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six remaining work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are fixed by the package contracts. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

The universal dispatcher automatically resumes or selects exactly one package. Live canonical branch and PR evidence outranks this snapshot.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | IN_PROGRESS | Resolve exact-head CI failure and all live review findings |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | BLOCKED | WP-01 COMPLETE |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Reconciled active lock

- Package: WP-01
- Branch: `agent/wp-01-editor-template-management`
- Pull request: #11, `WP-01: complete editor and template management`
- Exact resume parent: `f974c2d23a488d0e08d0902a37929e69e0456a57`
- Status: `IN_PROGRESS`
- Meaning: the existing package is resumed because exact-head CI failed and four unresolved review threads require remediation on the same branch. WP-02 stays blocked.

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
3. Resume the single unfinished package before selecting another. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
4. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
5. Never select `BLOCKED` or `COMPLETE`, and never begin more than one package.

## Status evidence

Only these statuses are valid:

- `BLOCKED`: verified external dependency prevents progress.
- `READY`: dependencies verified and no unfinished canonical lock exists.
- `IN_PROGRESS`: exact canonical branch or PR is actively claimed, resumed, or implemented.
- `PARTIAL`: useful committed and resumable work exists, but acceptance criteria remain and no external blocker exists.
- `IN_REVIEW`: all required implementation and test scope is present; review is unfinished.
- `VERIFYING`: review findings are resolved; exact-head or package-specific gates are running or being inspected.
- `COMPLETE`: normal merge and all required live `main`, release, and package-specific verification are complete.

## Completion count

- Total fixed packages: 6
- Completed: 0
- Remaining: 6
- Active: WP-01

## Weighted progress

Weights are immutable: WP-01 20, WP-02 20, WP-03 20, WP-04 15, WP-05 15, WP-06 10.

Current weighted progress is `0 / 100 = 0%`. Branch-local work and passing partial gates receive zero official credit.

## Advancement rule

The final commit of package N prepares package N as `COMPLETE`, unlocks only the exact next package as `READY`, updates counts/progress, and records final evidence. Those values remain prospective until the exact commit is normally merged and live verification succeeds. The completing worker verifies the merge and stops; it does not begin package N+1.