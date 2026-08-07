# Latest agent handoff

## Purpose

This is the current GitHub-backed handoff for the fixed remaining-work program. Resolve conflicts using this authority order: live GitHub state; the selected package contract; workflow documents; requirements; architecture; implementation plan; then state or handoff records.

## Active package

- Package: WP-03 — one-use mass distributions
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-03-mass-distributions`
- Draft pull request: #14, `WP-03: complete one-use mass distributions`
- Verified starting live `main`: `d77ec61032e5583783694ae349f785495cbf8f31`
- Initial claim commit: `b544ddb3bdb24c6ca95cdd2867af6b90d987ee46`
- Exact next package after authoritative completion: WP-04 — automated production hardening and release candidate

## Reconciliation evidence

- Live `main` is the normal WP-02 merge commit `d77ec61032e5583783694ae349f785495cbf8f31`.
- WP-02 head `a372372cf13fd22f1b7136b67c25c604af9d5275` is contained in that merge and is historical, not an unfinished lock.
- WP-01 is also historical and complete.
- Before the WP-03 claim there were no open/draft LoreItems PRs, no WP-03/WP-04/WP-05/LoreItems-WP-06 canonical branches, and no open EnthusiaTags PR or WP-06 integration branch.
- PR #14 and `agent/wp-03-mass-distributions` are therefore the single durable active package lock.

## Completed acceptance criteria

- Startup reconciliation across live `main`, recent merges, open PRs, canonical branches, WP-06 cross-repository lock, committed queue/state/handoff, and all six package contracts.
- WP-02 dependency verified complete from live merge evidence rather than stale prospective text.
- WP-03 selected automatically as the first eligible `READY` package.
- Canonical WP-03 branch created from exact live `main`.
- Draft PR #14 created with the exact contract title and complete package checklist.

## Remaining acceptance criteria

Every WP-03 requirement remains, including group-directory initialization; safe YAML discovery and validation; one-use source fingerprinting; immutable durable campaign/recipient snapshot; marker lifecycle and repair; Java/Floodgate/UUID identity handling; unresolved name binding; durable exactly-once physical delivery; offline/full-inventory handling; pause/resume/cancel; exact mutually exclusive counts; queue/review integration; metrics; degraded/reload/shutdown behavior; documentation; all mandated automated tests; regressions; full-package harsh review; exact-head Actions and Codacy; no requested changes or unresolved threads; normal merge; and post-merge live-main verification.

## Tests and findings

- Tests run: none yet; only coordination/claim state has changed.
- Known findings: live-main WP-02 coordination text was stale after PR #13 merged. Live GitHub correctly promotes WP-03 to the active package.
- Blocker: none.

## Queue state

- WP-01: `COMPLETE`
- WP-02: `COMPLETE`
- WP-03: `IN_PROGRESS`
- WP-04 through WP-06: `BLOCKED`
- Completed packages: 2 of 6
- Remaining packages: 4 of 6
- Weighted progress: 40%

## Exact next action

Inspect and reuse the existing campaign persistence/application foundations, then implement the complete WP-03 contract on PR #14. Commit GitHub-backed checkpoints after each major coherent section and keep all findings in this same package.
