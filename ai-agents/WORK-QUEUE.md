# Fixed remaining-work queue

## Queue invariants

This queue contains exactly six fixed work packages. Package identity, order, dependencies, weight, scope, acceptance criteria, branch name, and PR title are immutable. Live GitHub outranks this snapshot.

## Ordered queue

| Order | Package | Fixed objective | Weight | Status | Exact dependency |
|---:|---|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | Complete the editor and template-management interface. | 20% | COMPLETE | PR #11 normally merged; live `main` is `50ac248b1583739c57b7dcb25b4e949436b736ce` |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | Complete destructive administration and queued-operation controls. | 20% | BLOCKED | Verified GitHub Actions incident prevents exact-head hosted CI from starting; resume the same branch and PR after service recovery |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | Complete one-use mass distributions. | 20% | BLOCKED | WP-02 COMPLETE |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | Complete automated production hardening and produce a release candidate. | 15% | BLOCKED | WP-03 COMPLETE |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | Process manual live-server acceptance evidence, fix every confirmed defect, and release EnthusiaLoreItems. | 15% | BLOCKED | WP-04 release candidate published |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | Complete the separate EnthusiaTags service-API integration after LoreItems is released. | 10% | BLOCKED | WP-05 production release published |

## Active package lock and blocker checkpoint

- Package: WP-02 — destructive administration
- Branch: `agent/wp-02-destructive-administration`
- Draft PR: #13, `WP-02: complete destructive administration`
- Starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Resume starting head: `956f8c9a433d2819bbec16f072f7a44149fbbbad`
- Harsh-review checkpoint before blocker record: `d2e34d2de3ebede3f38e196b3ab5e7b32ec00982`
- Status: `BLOCKED`
- Completed criteria: complete WP-02 implementation, focused automated coverage, operator documentation, and full-package harsh review
- Harsh-review fixes: two PMD naming collisions; late-copy pause preservation; durable ambiguous outcome recording; confirmation tokens bound to actual target snapshots
- Verified successful evidence: `da1bff45a82df8f5b0e855720c6eada7e1b5d016` passed Actions run `31116258795` and Codacy check `92666693317`; `d2e34d2de3ebede3f38e196b3ab5e7b32ec00982` passed Codacy check `92669719848` with zero annotations
- Verified blocker: GitHub Status opened an unresolved Actions degraded-performance incident at `2026-08-06T15:22:00Z`; run `31116665464` failed solely in `Set up job` with no logs, and run `31117144848` was stuck in `Set up job`
- Review state: PR remains draft; no submitted reviews, requested changes, or unresolved threads were present before the blocker checkpoint
- Remaining criteria: exact-head Actions success after service recovery, ready-for-review transition, review/thread reconciliation, final COMPLETE coordination commit, exact-head final gates, normal merge, and live-main/post-merge verification
- Exact next action: after confirmed GitHub Actions recovery, resume WP-02 on this same branch and PR and verify or rerun exact-head CI

## Automatic selection and resume rule

1. Reconcile live `main`, all open/draft PRs, recent merges, checks, reviews, threads, and canonical package branches.
2. Resume the single unfinished package before selecting another.
3. `IN_PROGRESS`, `PARTIAL`, `IN_REVIEW`, and `VERIFYING` receive resume priority.
4. A verified externally `BLOCKED` active package remains the durable claim and must be resumed after its dependency clears; do not select a later package.
5. When no unfinished or active blocked package exists, select the lowest-numbered eligible `READY` package.
6. Never begin more than one package.

## Completion count and weighted progress

- Total fixed packages: 6
- Completed: 1
- Remaining: 5
- Active durable claim: WP-02
- Weighted progress: `20 / 100 = 20%`

WP-02 receives no weighted credit until normal merge and required live verification make it `COMPLETE`.
