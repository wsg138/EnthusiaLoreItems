# Fixed remaining-work queue

## Queue invariants
Exactly six immutable packages. Live GitHub outranks snapshots. Resume the single unfinished canonical lock before new work. Never split packages or begin the next package in the same completion chat.

| Order | Package | Weight | Status | Dependency |
|---:|---|---:|---|---|
| 1 | [WP-01](work-packages/WP-01-editor-and-template-management.md) | 20% | COMPLETE | merged/verified |
| 2 | [WP-02](work-packages/WP-02-destructive-administration.md) | 20% | COMPLETE | merged/verified |
| 3 | [WP-03](work-packages/WP-03-mass-distributions.md) | 20% | COMPLETE | PR #14 normally merged; live merge and post-merge Actions verified |
| 4 | [WP-04](work-packages/WP-04-production-hardening.md) | 15% | IN_PROGRESS | draft PR #15; direct-delivery failure/restart and V1–V7 migration gates verified; remaining hardening gates in progress |
| 5 | [WP-05](work-packages/WP-05-live-acceptance-and-release.md) | 15% | BLOCKED | WP-04 release candidate |
| 6 | [WP-06](work-packages/WP-06-enthusiatags-integration.md) | 10% | BLOCKED | WP-05 production release |

## WP-04 durable checkpoint
- Branch: `agent/wp-04-production-hardening`
- Draft PR: #15
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Resumed from exact head: `43b4092e6dc8c873ed7c77ccee4b87e5e5a44f34`
- Latest verified implementation head: `d42684074788ebfbf9dbfaef6111a03254f99bdd`
- Completed coherent sections: operator/recovery docs; WP-05 acceptance matrix; direct/API-delivery crash/restart failure group; every V1–V7 migration/upgrade and interrupted-migration recovery gate.
- Exact-head evidence: Actions run `31182331548` passed Gradle verification, repository tooling, new-code complexity, and exact-head Codacy; CodeRabbit combined status is successful.
- Harsh findings fixed on the same package: Java lambda capture compilation; overlong failure-matrix tests; Codacy harness naming/null lifecycle; migration fixture duplicate literals/version literal. No broad suppressions.
- Blocker: none.

## Progress
- Completed: 3/6
- Remaining: 3/6
- Weighted progress: 60%

## Exact next action
Implement deterministic saturation/backpressure verification for every bounded-work surface named by WP-04, then commit the section and continue WP-04 only. Do not start WP-05.
