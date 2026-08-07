# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

When sources conflict, resolve them in this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | PR #13 normally merged at `d77ec61032e5583783694ae349f785495cbf8f31` and live `main` verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | IN_PROGRESS | Canonical branch `agent/wp-03-mass-distributions` and draft PR #14 are the active lock |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-03 claim checkpoint

- Package: WP-03 — one-use mass distributions
- Branch: `agent/wp-03-mass-distributions`
- Draft PR: #14, `WP-03: complete one-use mass distributions`
- Starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Initial claim commit: `b544ddb3bdb24c6ca95cdd2867af6b90d987ee46`
- Completed criteria: live reconciliation, dependency verification, automatic routing, branch claim, and draft PR establishment
- Remaining criteria: the complete WP-03 contract, including implementation, required automated tests, full-package harsh review, exact-head Actions/Codacy, review resolution, normal merge, and live-main verification
- Tests: none yet; coordination claim only
- Known findings: prior live-main WP-02 records were stale after PR #13 merged; live GitHub is authoritative and WP-03 is now the only unfinished package
- Blocker: none
- Exact next action: implement WP-03 on PR #14 and checkpoint every major coherent section

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Apply the authority order above before trusting a snapshot or handoff state.
3. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
4. Resume the single unfinished package before selecting another. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
5. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
6. Never select `BLOCKED` or `COMPLETE`, never begin more than one package, and do not begin the next package after a completion merge in the same chat.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 2
- Remaining: 4
- Active package: WP-03
- Weighted progress: `40 / 100 = 40%`

No additional weighted credit is awarded until WP-03 is normally merged and all required live verification is complete.
