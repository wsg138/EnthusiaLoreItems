# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

When sources conflict, resolve them in this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | COMPLETE | PR #13 normally merged and live `main` verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | Required independent PR review cannot currently run because CodeRabbit reports the review quota is exhausted |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## WP-03 blocked checkpoint

- Branch: `agent/wp-03-mass-distributions`
- Pull request: #14, `WP-03: complete one-use mass distributions`
- Starting and current verified live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Exact implementation/review-ready head before blocker-state commits: `895f0e9f9e3160db1dde255c997cebf3cf19090e`
- Latest exact runtime head with complete successful verification: `45e0ea43cf0034ce87098ae0945a319149929a48`
- CI evidence: run #985 (`31159954396`) passed full Gradle verification, repository tooling, new-code complexity, and exact-head Codacy on `45e0ea43cf0034ce87098ae0945a319149929a48`.
- The following `895f0e9f9e3160db1dde255c997cebf3cf19090e` commit is documentation-only and records already-implemented recipient-health metrics; blocker/state commits after it require the normal final exact-head refresh after review.
- Completed criteria: safe bounded YAML discovery; immutable one-use source/recipient snapshots; DB-first atomic campaign start; cached/unresolved/Floodgate identity handling; exactly-once durable delivery; offline/full-inventory persistence; bounded retries and wakeups; pause/resume/cancel; exact status equations; WP-02 recovery integration; DB-authoritative active/completed/cancelled marker reconstruction; metrics-port instrumentation; permissions/messages/audit/docs; degraded startup, reload semantics, ordered shutdown; targeted Paper/SQLite/end-to-end/restart regressions.
- Harsh review: traced source replay, wrong-recipient/duplicate delivery, cancellation races/failure reconciliation, DB/filesystem split-brain, identity binding, state equations, threading/bounds, degraded mode, startup/shutdown, audit, metrics, and recovery. Every confirmed internal finding was fixed on this same package branch.
- Review state: PR #14 is open, non-draft, and mergeable. At blocker observation there were no submitted reviews, no requested changes, and zero unresolved review threads. CodeRabbit then refused to start the required independent review because its review quota was exhausted and reported the next review window in 56 minutes.
- Remaining criteria: completed independent review; resolve every review finding/thread; final exact-head full CI/Codacy after all review/state changes; prospective COMPLETE/next-READY state; normal merge; live-main verification.
- Blocker: verified external dependency — required independent CodeRabbit review capacity is unavailable. This is not a CI, implementation, or static-analysis failure.
- Exact next action: when review capacity is available, trigger `@coderabbitai review` on PR #14, resolve every finding/thread on this branch, then move WP-03 to `VERIFYING` and run the final exact-head verification gate. Do not begin WP-04.

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Apply the authority order above before trusting a snapshot or handoff state.
3. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
4. Resume the single unfinished canonical package before selecting another. Canonical branch/PR presence outranks a stale `READY` or `BLOCKED` snapshot, so an open WP-03 PR is rechecked and resumed when its external blocker clears.
5. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
6. Never select `BLOCKED` or `COMPLETE`, never begin more than one package, and do not begin the next package after a completion merge in the same chat.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 2
- Remaining: 4
- Active unfinished package lock: WP-03 (`BLOCKED` on external review capacity)
- Weighted progress: `40 / 100 = 40%`

No additional weighted credit is awarded until WP-03 is normally merged and all required live verification is complete.
