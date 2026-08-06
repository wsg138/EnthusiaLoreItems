# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Workers may update status, evidence, counts, and progress under the committed rules but may not split, merge, rename, reorder, or redefine a package.

Live GitHub outranks this snapshot. The universal dispatcher automatically resumes or selects exactly one package.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged; live `main` is `50ac248b1583739c57b7dcb25b4e949436b736ce` |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | PARTIAL | Durable operation core is green at checkpoint `00071255cf34221876cf041fe2c0dda487fc317b`; Paper execution and administration remain |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Active package lock and checkpoint

- Package: WP-02 — destructive administration
- Branch: `agent/wp-02-destructive-administration`
- Draft PR: #13, `WP-02: complete destructive administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Last exact-head green implementation checkpoint: `00071255cf34221876cf041fe2c0dda487fc317b`
- Status: `PARTIAL`
- Completed criteria: durable idempotent intent, exact/purge/delete snapshot acceptance, immediate definition deletion marker behavior, paginated parent/target inspection, pause/resume fencing, metrics, evidence-gated review, expired-claim recovery, verified completion, late-copy handling, V5 migration, and SQLite integration tests
- Remaining criteria: Paper physical execution across required scopes, commands/GUI/permissions/messages, lifecycle/reload/shutdown, documentation, full-package tests and harsh review, final exact-head verification, normal merge, and post-merge verification
- Exact-head verification: GitHub Actions run `31077567338` passed Gradle verification, tooling, complexity, coverage, and Codacy for implementation head `2e6bedfb8bcbc949739ea2ff7b514edbc1b1bf34`
- Blocker: Paper execution and operator-administration sections remain incomplete and unverified; no external dependency exists
- Exact next action: resume WP-02 and add destructive-first natural-access Paper execution using the existing reload-safe scanner and references

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and every canonical package branch.
2. Stop with an inconsistency report if multiple packages or duplicate PRs have conflicting unfinished canonical locks.
3. Resume the single unfinished package before selecting another. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
4. When no unfinished package exists, select the lowest-numbered `READY` package whose dependencies are verified complete.
5. Never select `BLOCKED` or `COMPLETE`, and never begin more than one package.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 1
- Remaining: 5
- Active: WP-02
- Weighted progress: `20 / 100 = 20%`

WP-02 receives no weighted credit until normal merge and required live verification make it `COMPLETE`.

## Advancement rule

The final commit of package N prepares package N as `COMPLETE`, unlocks only the exact next package as `READY`, updates counts/progress, and records final evidence. Those values remain prospective until the exact commit is normally merged and live verification succeeds. The completing worker verifies the merge and stops; it does not begin package N+1.