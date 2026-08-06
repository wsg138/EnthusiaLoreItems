# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Live GitHub outranks this snapshot.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged; live `main` is `50ac248b1583739c57b7dcb25b4e949436b736ce` |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | IN_PROGRESS | Resumed on the same canonical branch and draft PR #13; two exact-head Codacy naming findings remain |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Active package lock and resume checkpoint

- Package: WP-02 — destructive administration
- Branch: `agent/wp-02-destructive-administration`
- Draft PR: #13, `WP-02: complete destructive administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Resume starting head: `956f8c9a433d2819bbec16f072f7a44149fbbbad`
- Latest implementation head before resume: `98bceb74a3b25e827c546b85db16f28820d223c3`
- Exact-head CI run: `31104783506` on `956f8c9a433d2819bbec16f072f7a44149fbbbad`
- Status: `IN_PROGRESS`
- Completed criteria: durable operation state; exact/purge/delete semantics; bounded destructive-first execution; exact-reference and fingerprint verification; review-required divergence; preview-confirm commands; operation/target inspection; metrics; pause/resume; evidence review; GUI actions; permissions; completion; reload cleanup; worker wakeups; documentation; focused domain, SQLite, migration, Paper, command, GUI, and recovery tests
- Remaining criteria: rename the two confirmed colliding identifiers, rerun exact-head Actions/Codacy, perform full-package harsh review, reconcile review state, prepare COMPLETE transition, mark ready, normally merge, and verify live `main`
- Review state: no submitted reviews, no requested changes, and no unresolved review threads at resume
- Blocker: no external blocker; exact-head Codacy is not yet clean
- Exact next action: apply the two naming fixes without suppressions and verify the resulting exact head

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and canonical package branches.
2. Resume the single unfinished package before selecting another.
3. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
4. When no unfinished package exists, select the lowest-numbered eligible `READY` package.
5. Never select `BLOCKED` or `COMPLETE`, and never begin more than one package.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 1
- Remaining: 5
- Active: WP-02
- Weighted progress: `20 / 100 = 20%`

WP-02 receives no weighted credit until normal merge and required live verification make it `COMPLETE`.
