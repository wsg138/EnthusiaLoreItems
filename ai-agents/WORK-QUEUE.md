# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Live GitHub outranks this snapshot.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged; live `main` is `50ac248b1583739c57b7dcb25b4e949436b736ce` |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | PARTIAL | Same canonical branch and draft PR #13; durable core and destructive-first Paper execution are green, operator administration remains |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Active package lock and checkpoint

- Package: WP-02 — destructive administration
- Branch: `agent/wp-02-destructive-administration`
- Draft PR: #13, `WP-02: complete destructive administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Resumed from branch head: `9b3a622e4b1b1ae27bc74fde5ee191fe5d40875b`
- Verified implementation head: `b9729a2735c737ea625e2d20277bd109132f624a`
- Exact-head CI run: `31082380710` — success
- Status: `PARTIAL`
- Completed criteria: durable idempotent operation core; exact/purge/delete acceptance; deletion markers; pagination, pause/resume, metrics, evidence review, recovery, verified completion, late-copy behavior; V5 migration; reload-safe destructive-first physical execution across the existing natural-access scanner; exact-reference removal for inventories, nested shulkers/bundles, dropped items, frames, displays, and armor stands; shared lifecycle recovery and focused Paper tests
- Remaining criteria: operator commands and GUI, operation-specific confirmation sessions, permissions/messages, duplicate and malformed evidence administration, documentation, broader command/GUI/reload tests, full-package harsh review, final exact-head verification, review resolution, normal merge, and post-merge verification
- Review state: no submitted reviews and no unresolved review threads at checkpoint
- Blocker: no external blocker; useful verified work is committed but WP-02 is not complete
- Exact next action: implement the privileged destructive-administration command surface and operation-specific confirmation sessions against `DestructiveAdministrationUseCase`, then wire GUI actions and worker wakeups

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
