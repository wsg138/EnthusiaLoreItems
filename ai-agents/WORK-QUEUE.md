# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged/verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged/verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | IN_PROGRESS | canonical branch claimed from verified WP-03 `main` |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | WP-04 release candidate |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | WP-05 production release |

## WP-04 claim checkpoint
- Branch: `agent/wp-04-production-hardening`
- Draft PR: create immediately from this claim commit
- Starting/verified live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Dependency evidence: WP-03 merge commit is live `main`; GitHub Actions `verify` run `31174065679` completed successfully on that merge SHA.
- Checkpointed implementation/evidence head: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Blocker: none.

## Progress
- Completed: 3/6
- Remaining: 3/6
- Weighted progress: 60%

## Exact next action
Open the exact WP-04 draft PR, re-fetch the claim lock, inventory current hardening/release surfaces, and complete the entire WP-04 contract on the same branch.
