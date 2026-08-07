# Workspace state

## Snapshot warning

This file is a committed coordination snapshot. Live GitHub remains authoritative. Resolve conflicts using this order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Publication state

- Repository: `wsg138/EnthusiaLoreItems`
- Verified starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Active package: WP-03 — one-use mass distributions
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-03-mass-distributions`
- Draft pull request: #14, `WP-03: complete one-use mass distributions`
- Initial claim commit: `b544ddb3bdb24c6ca95cdd2867af6b90d987ee46`
- Exact next package after authoritative WP-03 completion: WP-04 — automated production hardening and release candidate

## Live reconciliation at claim

- WP-01 branch `agent/wp-01-editor-template-management` is historical; its head is contained in live `main` through verified PR #11.
- WP-02 branch `agent/wp-02-destructive-administration` is historical; its head `a372372cf13fd22f1b7136b67c25c604af9d5275` is the second parent of live merge commit `d77ec61032e5583783694ae349f785495cbf8f31` from PR #13.
- No unfinished WP-03, WP-04, WP-05, or LoreItems-side WP-06 canonical branch existed before the WP-03 claim.
- No open LoreItems pull request existed before the WP-03 claim.
- No open EnthusiaTags pull request or `agent/wp-06-loreitems-integration` branch existed at reconciliation time.
- PR #14 and its branch are now the single active package lock.

## Package status

| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| WP-02 | 20% | COMPLETE | PR #13 normally merged at `d77ec61032e5583783694ae349f785495cbf8f31` and live `main` verified |
| WP-03 | 20% | IN_PROGRESS | Canonical branch and draft PR #14 claimed from live `main` |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

## Counts and weighted progress

- Fixed package count: 6
- Completed packages: 2 of 6
- Remaining packages: 4 of 6
- Weighted progress: `40 / 100 = 40%`
- Active incomplete work receives zero official weighted completion credit.

## WP-03 checkpoint

- Active package: WP-03 — one-use mass distributions
- Status: `IN_PROGRESS`
- Branch: `agent/wp-03-mass-distributions`
- PR: #14
- Exact implementation/evidence head being checkpointed: `b544ddb3bdb24c6ca95cdd2867af6b90d987ee46`
- Completed acceptance criteria: startup reconciliation, automatic routing, canonical branch claim, and draft PR creation
- Remaining acceptance criteria: all group-file, durable campaign, recipient identity, exactly-once delivery, marker lifecycle, controls, status, recovery, threading, bounds, documentation, automated-test, harsh-review, exact-head verification, merge, and post-merge criteria in the WP-03 contract
- Tests run: none yet; claim changes are coordination-only
- Known findings: the prior WP-02 coordination snapshot on live `main` was stale after its normal merge; live GitHub superseded it and selected WP-03
- Blocker: none
- Exact next action: inspect existing campaign/domain/SQLite/Paper foundations and implement the complete WP-03 contract on PR #14
