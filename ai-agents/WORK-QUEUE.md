# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

When sources conflict, resolve them in this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | IN_REVIEW | Fixed branch `agent/wp-03-mass-distributions` and PR #14 contain the complete implementation and harsh-review fixes; review/verification gates remain |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-03 active checkpoint

- Branch: `agent/wp-03-mass-distributions`
- Pull request: #14, `WP-03: complete one-use mass distributions`
- Starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Latest fully verified implementation/restart-test head: `67f4d1cba9c0d0cf34c54827f7f106085394401c`
- CI evidence: run #979 passed full Gradle verification, repository tooling, new-code complexity, and exact-head Codacy.
- Latest documentation head entering review: `21f1f001e29c01787c976d750d901e4653bf2a25`; exact-head CI is refreshing after documentation only.
- Completed criteria: safe bounded YAML discovery; immutable one-use source/recipient snapshots; DB-first atomic campaign start; cached/unresolved/Floodgate identity handling; exactly-once durable delivery; offline/full-inventory persistence; bounded retries and wakeups; pause/resume/cancel; exact status counts; WP-02 recovery integration; active/completed/cancelled marker reconstruction and recovery; metrics-port instrumentation; permissions/messages/audit/docs; degraded startup and ordered shutdown; targeted Paper/SQLite/end-to-end/restart regressions.
- Harsh review: traced source replay, wrong-recipient/duplicate delivery, cancellation races, DB/filesystem split-brain, identity binding, state equations, threading/bounds, degraded mode, startup/shutdown, audit, metrics, and recovery. Confirmed findings were fixed on the same package branch; no follow-up package was created.
- Review state: implementation is ready for substantive PR review. No requested changes or unresolved review threads were present before making the PR reviewable.
- Remaining criteria: substantive PR review; resolve every requested change/thread; final exact-head full CI/Codacy after the last review/state change; normal merge; live-main verification; authoritative COMPLETE/READY state publication.
- Blocker: none; WP-03 is the only active package.
- Exact next action: mark PR #14 ready for review, reconcile substantive review feedback, then move WP-03 to `VERIFYING` only when review has no requested changes and zero unresolved threads.

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
- Active package: WP-03 (`IN_REVIEW`)
- Weighted progress: `40 / 100 = 40%`

No additional weighted credit is awarded until WP-03 is normally merged and all required live verification is complete.
