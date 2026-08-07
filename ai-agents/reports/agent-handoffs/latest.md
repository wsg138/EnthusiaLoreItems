# Latest agent handoff

## Active package
- Package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- Draft PR: create immediately from the durable claim commit
- Starting live `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Checkpointed implementation/evidence head: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`

## Reconciliation evidence
- WP-03 PR #14 is normally merged into live `main` as `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`.
- GitHub Actions `verify` run `31174065679` succeeded on that merge SHA.
- No open LoreItems PR existed at routing time.
- Historical canonical branches for WP-01 through WP-03 remain; WP-04/WP-05 and WP-06 auxiliary LoreItems/Tags branches were absent before claim.
- WP-04 is the lowest-numbered dependency-verified READY package and is now exclusively claimed.

## Completed
- Read universal prompt, workflow rules, queue/state/handoff, all six package contracts, requirements, architecture, and implementation plan.
- Reconciled recent merges and canonical branches with live GitHub.
- Atomically created `agent/wp-04-production-hardening` from verified live `main`.
- Began durable `IN_PROGRESS` checkpoint.

## Remaining
All WP-04 acceptance criteria remain: complete deterministic failure matrix, saturation/backpressure tests, all-version migration/upgrade tests, reload/shutdown hardening, stable service API tests/docs, fixed-scenario performance harness/results, static-analysis cleanup, operator/recovery docs, WP-05 acceptance matrix, RC packaging/reproducibility/artifacts, harsh and independent review, exact-head CI/Codacy, normal merge/post-merge verification, and `v1.0.0-rc.1` prerelease verification.

## Tests
- No WP-04 implementation tests yet.
- Dependency verification only: post-merge Actions success on WP-03 merge SHA.
- No local build claim; no dependency-capable checkout is available in this runtime.

## Findings
None yet.

## Blocker
None.

## Exact next action
Create the exact draft PR from this claim commit, verify the branch/PR/head did not move, then inventory and implement the WP-04 production-hardening surfaces on this branch only.
