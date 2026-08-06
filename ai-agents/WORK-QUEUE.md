# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six remaining work packages. Package scope and weights are fixed. Implementation workers may update status, evidence, counts, and progress after satisfying the committed rules, but may not split, merge, reorder, rename, or redefine packages.

A package is assigned as a whole. Commits and internal checklists are implementation details, not smaller work items. Failure or interruption continues the same package and relevant PR.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | VERIFYING | Final WP-01 exact-head gates and normal merge |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | BLOCKED | WP-01 COMPLETE |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Advancement rule

Only the first non-complete package whose dependencies are verified may be `READY` or `IN_PROGRESS`. Later packages remain `BLOCKED`. A worker assigned package N never advances itself to package N+1. After package N is complete, a separate assignment may activate the exact next package named in package N's contract.

## Completion count

- Total fixed packages: 6
- Completed: 0
- Remaining: 6

`completed = count(status == COMPLETE)`

`remaining = 6 - completed`

## Weighted progress

Weights are immutable:

- WP-01: 20
- WP-02: 20
- WP-03: 20
- WP-04: 15
- WP-05: 15
- WP-06: 10

`weighted_progress = sum(weight for every package with status COMPLETE)`

Initial weighted progress is `0 / 100 = 0%`. No fractional credit is awarded for partial scope, an open PR, passing local tests, review approval, or a merge that has not completed its package-specific post-merge verification.

## Evidence required to change status

- `READY`: all dependencies verified on live GitHub.
- `IN_PROGRESS`: fixed branch/PR is active for the package.
- `IN_REVIEW`: every required scope and test item is implemented and checked in the PR body.
- `VERIFYING`: all review findings are resolved and exact-head checks are running or being inspected.
- `MERGED`: GitHub reports a normal merge commit for the package PR.
- `COMPLETE`: live `main` contains the merge, all package-specific post-merge gates are satisfied, and committed/reporting state identifies the evidence without unsupported claims.
