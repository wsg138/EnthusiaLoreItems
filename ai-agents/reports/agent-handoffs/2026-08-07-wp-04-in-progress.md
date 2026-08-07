# WP-04 IN_PROGRESS claim checkpoint — 2026-08-07

- Active package: WP-04 — automated production hardening and release candidate
- Status: `IN_PROGRESS`
- Branch: `agent/wp-04-production-hardening`
- PR: exact draft PR to be opened immediately after this first branch commit
- Starting `main`: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`
- Checkpointed implementation/evidence head: `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`

## Completed criteria
- Live GitHub reconciled before selection.
- All fixed package contracts and required authority documents read.
- WP-03 normal merge verified on live `main`.
- WP-03 post-merge GitHub Actions `verify` run `31174065679` succeeded on the merge SHA.
- No competing open/draft LoreItems PR or unfinished later canonical lock existed.
- WP-04 selected by fixed routing and canonical branch atomically created from refreshed live `main`.

## Remaining criteria
Every substantive WP-04 criterion remains, including failure injection, saturation/backpressure, migrations/upgrades, reload/shutdown, stable API, performance/profile thresholds and committed results, static analysis, operator docs, WP-05 manual matrix, RC packaging/reproducibility, review, exact-head checks, merge/post-merge verification, and RC prerelease publication/assets.

## Tests/results
- WP-04 implementation tests: not run yet.
- Dependency gate: GitHub Actions `verify` run `31174065679` — success on `d8a9b0055fd8e71e6a25b82364ebb625aa75ae9b`.
- Local build: not claimed; runtime has no outbound GitHub network and no mounted checkout.

## Findings
None yet.

## Blocker
None.

## Exact next action
Open the exact draft PR, re-fetch branch/PR/head to confirm the durable claim, inventory the current hardening/release surfaces, then implement and verify the complete WP-04 contract on this branch only.
