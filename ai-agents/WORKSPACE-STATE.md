# Workspace state

## Snapshot warning

This file is a committed coordination snapshot, not an authority over live GitHub. Every agent must refresh live state before routing or relying on it. Canonical branch and PR presence outranks stale queue text.

## Active package claim

- Repository: `wsg138/EnthusiaLoreItems`
- Verified starting live `main`: `50ac248b1583739c57b7dcb25b4e949436b736ce`
- Active package: WP-02 — destructive administration
- Status: `IN_PROGRESS`
- Canonical branch: `agent/wp-02-destructive-administration`
- Draft pull request: #13, `WP-02: complete destructive administration`
- Initial claim checkpoint: `2612d40607916414f06d4d6a46aef3d887bafc89`
- Dependency verification: WP-01 is normally merged through live `main` merge commit `50ac248b1583739c57b7dcb25b4e949436b736ce`; historical WP-01 head `08f92b8b185e4022154bc34a01d30f3123c6043b` is contained in `main`

## Package status

| Package | Weight | Status | Reason |
|---|---:|---|---|
| WP-01 | 20% | COMPLETE | PR #11 normally merged and live `main` verified |
| WP-02 | 20% | IN_PROGRESS | Canonical branch and draft PR #13 are the active durable lock |
| WP-03 | 20% | BLOCKED | WP-02 is not COMPLETE |
| WP-04 | 15% | BLOCKED | WP-03 is not COMPLETE |
| WP-05 | 15% | BLOCKED | WP-04 release candidate is not verified |
| WP-06 | 10% | BLOCKED | WP-05 production release is not verified |

## Counts and weighted progress

- Fixed package count: 6
- Completed packages: 1 of 6
- Remaining packages: 5 of 6
- Active package: WP-02
- Weighted progress: `20 / 100 = 20%`

No weighted credit is assigned to WP-02 while it is `IN_PROGRESS`.

## Completed acceptance criteria in this checkpoint

- Reconciled live LoreItems `main`, recent merges, all open/draft PRs, all canonical LoreItems branches, the canonical EnthusiaTags WP-06 branch, commit status/workflow visibility, and historical WP-01 containment.
- Read the universal dispatcher, agent rules, workspace/queue/handoff records, all six fixed package contracts, requirements, architecture, and implementation plan from exact live `main`.
- Verified that no unfinished canonical package lock exists and WP-02 is the first eligible `READY` package.
- Atomically created the exact WP-02 branch from `50ac248b1583739c57b7dcb25b4e949436b736ce`.
- Committed claim head `2612d40607916414f06d4d6a46aef3d887bafc89` and opened exact draft PR #13 from that head.

## Remaining acceptance criteria

Every implementation, test, documentation, harsh-review, exact-head CI/Codacy, review-resolution, normal-merge, and post-merge criterion in `ai-agents/work-packages/WP-02-destructive-administration.md` remains.

## Tests and findings

- Tests run: none; these are routing and claim checkpoints.
- Known findings: no package implementation finding has been established yet.
- External blocker: none.
- Environment limitation: no dependency-capable local checkout is available because the runtime cannot resolve GitHub; GitHub connector evidence and repository-native CI remain authoritative.

## Exact next action

Inspect the current destructive-operation domain/application/SQLite/Paper/GUI implementation and tests, identify the precise delta to the WP-02 contract, and implement the first coherent section on the same branch and PR.