# LoreItems Deep Audit Protocol V3

Status: authoritative for PR #30 (`fix/full-plugin-deep-review`).

This file supersedes the earlier `LOREITEMS_DEEP_AUDIT_V2` global-reset routing rules in PR comments. Historical V2 findings remain valid evidence, but V2 routing instructions such as “any candidate change restarts at SLICE-01” are no longer authoritative.

## Goal

Finish pre-production hardening with high confidence without creating an endless audit loop.

No process can prove literal zero bugs. Release readiness requires:

1. one complete **remediation sweep** covering all eight slices and fixing every credible defect found;
2. a frozen candidate that passes repository quality/build/runtime gates;
3. **two consecutive clean verification rounds** on the same frozen production candidate.

Workers must prefer durable progress over repeating already-completed work.

## Current routing baseline

At adoption of V3, PR #30 had already performed repeated deep implementation reviews of:

- SLICE-01 — lifecycle, threading, runtime coordination;
- SLICE-02 — SQLite, persistence, migrations;
- SLICE-03 — item identity, tracking, physical-world lifecycle.

Those reviews found and fixed numerous real defects. The latest pre-V3 head change (`95ccbff1be336c005e20ada73afa1ee0d71a8eaf`) corrected a regression-test helper and did not change production source.

Therefore the V3 remediation sweep carries forward SLICES 01–03 as covered and routes the next worker to:

`REMEDIATION / SLICE-04 — Delivery and mass distributions`

Live GitHub state overrides the SHA snapshot above, but a later documentation/test-only commit does not erase remediation coverage.

## The eight slices

1. Lifecycle, threading and runtime coordination.
2. SQLite, persistence and migrations.
3. Item identity, tracking and physical-world lifecycle.
4. Delivery and mass distributions.
5. Editor, templates, revisions and configuration.
6. Destructive admin, anomalies and recovery.
7. Commands, permissions, identity and external APIs/EnthusiaTags.
8. Build, release, dependencies and test integrity.

Use the detailed scope descriptions from prior audit comments/prompts when useful, but do not spend the session reconstructing old chat history. Inspect the actual current implementation.

# Phase A — Remediation sweep

The remediation sweep exists to find and fix bugs across the whole plugin once without losing forward progress.

## Routing

- Select the lowest-numbered remediation slice not yet completed under V3.
- Review exactly one slice deeply per worker unless the remaining work is trivially small.
- If the slice is partial, persist a precise checkpoint and the next worker resumes the same slice.
- If a bug is found, fix it, add focused regression coverage where practical, and continue/finish that slice as capacity permits.
- **A fix does not globally restart the remediation sweep.**
- After the slice is complete, advance to the next slice.

## Dirty-slice rule

When a fix materially changes code owned by an already-covered remediation slice, mark only that affected slice `DIRTY_RECHECK`.

Examples:

- a SLICE-04 delivery fix that changes SQLite transaction machinery may dirty SLICE-02;
- a SLICE-06 fix that changes shared lifecycle/executor ownership may dirty SLICE-01;
- a test-only assertion correction does not dirty production slices unless it exposes a previously untested guarantee that needs source re-review.

Dirty rechecks are performed after reaching SLICE-08, before candidate freeze. Do not restart unaffected slices.

## Remediation result values

Each V3 remediation worker posts one PR comment containing `LOREITEMS_DEEP_AUDIT_V3` and one of:

- `REMEDIATION_SLICE_CLEAN`
- `REMEDIATION_FINDINGS_FIXED`
- `REMEDIATION_SLICE_PARTIAL`
- `REMEDIATION_DIRTY_RECHECK_CLEAN`
- `REMEDIATION_DIRTY_RECHECK_FINDINGS_FIXED`

Record:

- phase and slice;
- audited starting SHA;
- resulting SHA if fixes were committed;
- concrete components/classes reviewed;
- important flows/invariants traced;
- findings and fixes;
- focused tests/evidence;
- dirty slices created, if any;
- exact next action.

Do not run Sentinel or full release finalization from every remediation slice. Focused verification is sufficient for a finding/fix worker; broad CI can run naturally but is not a reason to restart audit routing.

# Phase B — Freeze candidate

After SLICES 01–08 and all `DIRTY_RECHECK` items are complete:

1. reconcile all known findings and unresolved review threads;
2. ensure patch-release/version plumbing is correct and immutable `v1.0.0` is not retargeted;
3. run full Java 21 clean verification and repository tooling;
4. require complexity/static-analysis/Codacy gates to pass or classify/fix legitimate findings;
5. build the exact release candidate reproducibly;
6. verify artifact provenance and packaged JAR contents;
7. run required Sentinel startup/restart evidence only after the exact artifact exists;
8. require documented cleanup/resource safety.

Any credible defect found here is fixed and routed through a targeted dirty recheck of the affected slice(s). Do not automatically redo all eight remediation slices.

When these gates are green, publish a V3 ledger comment:

`CANDIDATE_FROZEN`

with exact source SHA, tree SHA when available, CI run, artifact ID/name, JAR SHA-256, version/tag target, Sentinel evidence, and zero unresolved blocker/high findings.

From this point onward, production-affecting candidate changes unfreeze the candidate and invalidate clean verification rounds.

# Phase C — Clean verification round 1

Only after `CANDIDATE_FROZEN`.

Eight fresh workers independently review SLICES 01–08 on the exact frozen production candidate.

Rules:

- no candidate-changing production/config/build/dependency/release changes are allowed without invalidating the frozen round;
- each worker performs one bounded slice and records `VERIFY_R1_SLICE_CLEAN` if no credible defect is found;
- if any credible defect is found, fix it, return to targeted remediation/dirty recheck, refreeze a new candidate, and restart verification round 1;
- test-only/documentation corrections that do not change production behavior do not automatically invalidate already-clean unrelated slices; rerun the affected evidence and document why production identity is unchanged.

When all eight slices are independently clean on the same frozen production candidate and final gates remain green:

`CLEAN_STREAK: 1/2`

# Phase D — Clean verification round 2

Repeat all eight slices with fresh workers on the same frozen production candidate.

Round-2 workers must independently inspect implementation and should vary attack order/failure models rather than merely confirming round-1 notes.

Any credible production defect resets clean verification to a newly fixed/refrozen candidate.

When all eight round-2 slices are clean and final gates remain green:

`CLEAN_STREAK: 2/2`

`READY FOR LIVE DEPLOYMENT — TWO CONSECUTIVE CLEAN DEEP REVIEWS`

No worker may claim literal zero bugs.

# Context-safety rules

- One bounded slice per worker.
- Do not reread the entire 2,000+ comment history; locate the latest `LOREITEMS_DEEP_AUDIT_V3` control/result comments and inspect current source.
- Publish partial progress before context pressure becomes a failure.
- `SLICE_PARTIAL` is resumable success, not an apology/failure.
- Do not rerun expensive full gates unless required by the current phase or a fix.
- Do not create a new hardening PR while #30 is legitimate and open.
- Do not rewrite WP-01 through WP-06 completion history.
- Do not merge PR #30 until V3 reaches `CLEAN_STREAK: 2/2` and final gates are green.
- Do not deploy to the live production server without separate owner authorization.

# Worker startup algorithm

1. Fetch live PR #30 info/head.
2. Read this file from PR #30 head.
3. Find the latest PR comments containing `LOREITEMS_DEEP_AUDIT_V3`.
4. Determine the current phase and exact next slice/recheck.
5. Perform that bounded work only.
6. Persist a V3 result/checkpoint comment.
7. Stop cleanly.

If no V3 result exists after this protocol commit, start at `REMEDIATION / SLICE-04`.