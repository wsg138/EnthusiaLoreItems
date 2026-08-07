# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

When sources conflict, resolve them in this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | IN_PROGRESS | Existing canonical PR resumed; temporary independent-review capacity window has elapsed |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-03 resume checkpoint

- Branch: `agent/wp-03-mass-distributions`
- Pull request: #14, `WP-03: complete one-use mass distributions`
- Verified live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Resume base head: `10cb131e93c4758cfe9f1e174e1400cb8d0b5ffc`
- Exact implementation/review-ready head before coordination commits: `895f0e9f9e3160db1dde255c997cebf3cf19090e`
- Latest exact runtime head with complete successful verification: `45e0ea43cf0034ce87098ae0945a319149929a48`
- CI evidence: run `31159954396` passed full Gradle verification, repository tooling, new-code complexity, and exact-head Codacy on `45e0ea43cf0034ce87098ae0945a319149929a48`.
- Prior blocker evidence: CodeRabbit comment last updated `2026-08-07T08:12:33Z` reported `Next review available in: 46 minutes`; the current worker resumed after that window elapsed.
- Completed criteria: complete bounded group-file lifecycle; immutable DB-first campaign snapshots; replay fencing; Java/Floodgate/UUID identity; durable unresolved-name binding; exactly-once verified delivery; no overflow drop; pause/resume/cancel; exact state equations; recovery/marker repair; metrics/audit/permissions/messages; degraded/reload/shutdown behavior; required focused Paper/SQLite/end-to-end/restart tests; full-package harsh review and internal fixes.
- Remaining criteria: substantive independent review; review remediation; final exact-head CI/Codacy; prospective completion state; normal merge; live-main verification.
- Blocker: none at resume time.
- Exact next action: prove the resume head is stable, mark PR #14 ready, trigger `@coderabbitai review`, resolve all review findings on this same branch, then continue to `VERIFYING` if clean.

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
- Active unfinished package lock: WP-03 (`IN_PROGRESS`)
- Weighted progress: `40 / 100 = 40%`

No additional weighted credit is awarded until WP-03 is normally merged and all required live verification is complete.