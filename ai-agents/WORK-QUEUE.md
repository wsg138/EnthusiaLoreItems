# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged/verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged/verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | PARTIAL | canonical draft PR #15; operator/recovery docs and WP-05 executable matrix committed; automated hardening/release gates remain |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | WP-04 release candidate |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | WP-05 production release |

## WP-04 durable checkpoint
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting/verified live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Claim commit: `3413b3304779518a2913a1d372bef61bd8115a2f`
- Documentation/matrix implementation head: `c9784b744271384aac32ea7e145f38df1476cd49`
- Durable handoff checkpoint: `6b7c50d9cc1e7d5b5c3db8b41966b806595838d6`
- Completed coherent sections: operator/recovery/backup/restore/rollback/incident documentation; executable WP-05 manual acceptance matrix.
- Dependency evidence: WP-03 merge commit is live `main`; GitHub Actions `verify` run `31174065679` completed successfully on that merge SHA.
- Exact-head CI for the documentation/matrix head was running as Actions run `31179217336` when the checkpoint was prepared.
- Blocker: none; status is `PARTIAL` because required WP-04 work remains.

## Progress
- Completed: 3/6
- Remaining: 3/6
- Weighted progress: 60%

## Exact next action
Resume draft PR #15, refresh exact head/check/review state, then implement the deterministic failure-injection/restart harness and first complete durable state-machine failure group. Continue WP-04 only; do not start WP-05.
