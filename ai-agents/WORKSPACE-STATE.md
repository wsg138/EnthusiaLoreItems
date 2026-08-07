# Workspace state

## Snapshot warning
Live GitHub is authoritative. Resolve conflicts in this order: live GitHub; selected package contract; workflow docs; requirements; architecture; implementation plan; state/handoffs.

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-04-production-hardening`
- Pull request: draft PR to be created immediately from this claim commit
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Checkpointed implementation/evidence head: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- WP-03 post-merge verification: merge SHA `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`; GitHub Actions check `verify` run `31174065679` completed successfully.

## Package status
| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | normally merged and verified |
| WP-02 | 20% | COMPLETE | normally merged and verified |
| WP-03 | 20% | COMPLETE | PR #14 normally merged; live merge SHA and post-merge Actions verified |
| WP-04 | 15% | IN_PROGRESS | canonical branch claimed from verified live `main` |
| WP-05 | 15% | BLOCKED | WP-04 release candidate not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release not verified |

## Progress
- Fixed packages: 6
- Completed: 3 of 6
- Remaining: 3 of 6
- Weighted progress: `60 / 100 = 60%`

## Completed acceptance criteria
- Startup reconciliation completed against live GitHub, including `main`, open PRs, canonical package branches, recent merge history, WP-06 auxiliary branches, and WP-03 post-merge check state.
- All six package contracts plus requirements, architecture, implementation plan, workflow rules, queue/state, and latest handoff were read before claim.
- WP-04 dependency on WP-03 is verified complete on live GitHub.
- Canonical WP-04 branch was atomically created from live `main` SHA `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`.

## Remaining acceptance criteria
All WP-04 functional, failure-injection, saturation, migration, reload/shutdown, stable API, performance/profile, static-analysis, operator documentation, WP-05 matrix, packaging/reproducibility, independent review, exact-head CI/Codacy, normal merge, post-merge verification, and `v1.0.0-rc.1` prerelease criteria remain.

## Tests and verification
- No implementation tests have been run yet for WP-04.
- Dependency verification: merge SHA `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b` has GitHub Actions `verify` success (run `31174065679`).
- No local build result is claimed; this runtime has no outbound GitHub network and no mounted checkout.

## Known findings
None yet; implementation and harsh review have not begun.

## Blocker
None.

## Exact next action
Create the exact draft PR from this claim commit, re-fetch branch/PR/head for concurrency, then inventory the current production-hardening/test/release surfaces and implement the full WP-04 contract on this branch only.
