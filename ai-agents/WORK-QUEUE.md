# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged/verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged/verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | IN_PROGRESS | canonical draft PR #15 resumed at exact head `43b4092e…`; automated hardening/release gates in progress |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | WP-04 release candidate |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | WP-05 production release |

## WP-04 durable checkpoint
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting/verified live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Resumed from exact head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`
- Completed coherent sections: operator/recovery/backup/restore/rollback/incident documentation; executable WP-05 manual acceptance matrix.
- Dependency evidence: WP-03 merge commit is live `main`; GitHub Actions `verify` run `31174065679` completed successfully on that merge SHA.
- Resume-head evidence: Actions run `31179353021` completed successfully, including Gradle verification, repository tooling, new-code complexity, and exact-head Codacy; CodeRabbit status is successful; PR #15 has zero review threads and no submitted reviews/requested changes.
- Canonical-lock reconciliation: WP-01 through WP-03 branches are historical/contained in `main`; WP-04 is the only unfinished canonical lock; WP-05 and WP-06 package branches are absent.
- Blocker: none; status is `IN_PROGRESS` while automated hardening continues.

## Progress
- Completed: 3/6
- Remaining: 3/6
- Weighted progress: 60%

## Exact next action
Implement the deterministic failure-injection/restart harness and first complete durable state-machine failure group on draft PR #15. Continue WP-04 only; do not start WP-05.
