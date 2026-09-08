# LoreItems Deep Audit Protocol V3

Status: authoritative for PR #30 (`fix/full-plugin-deep-review`).

This file supersedes the earlier `LOREITEMS_DEEP_AUDIT_V2` global-reset routing rules in PR comments. Historical V2 findings remain valid evidence, but V2 routing instructions such as “any candidate change restarts at SLICE-01” are no longer authoritative.

## Goal

Finish pre-production hardening with high confidence without creating an endless audit loop.

No process can prove literal zero bugs. Release readiness requires:

1. one complete remediation sweep covering all eight slices and fixing every credible defect found;
2. a frozen candidate that passes repository quality/build/runtime gates;
3. two consecutive clean verification rounds on the same frozen production candidate.

Workers must prefer durable progress over repeating already-completed work.

# Worker execution contract

Routing is setup, not a deliverable. A worker given the standard continuation prompt MUST execute the routed GitHub work in the same session; it must not merely restate, rewrite, or hand back the prompt or tell the owner to start another worker.

A worker may stop only after one of these outcomes is durably published on PR #30:

1. the routed slice/sub-slice/recheck/gate was substantively performed and a V3 clean result was posted;
2. confirmed defects were fixed with appropriate regression coverage and a V3 result/checkpoint was posted;
3. meaningful partial work was completed and a precise resumable `PARTIAL` checkpoint was posted because the bounded unit could not be responsibly finished in the current session;
4. a genuine external blocker outside the repository workflow prevents further work, and the blocker plus the exact already-attempted evidence is posted durably.

The following are not valid worker outcomes:

- repeating the user's continuation prompt;
- replying primarily with a suggested prompt for the next worker;
- only stating which slice is next without inspecting/working that slice;
- only summarizing prior V3 comments;
- stopping because another worker moved the head when live reconciliation can route useful non-conflicting work;
- asking the owner to assign work that the protocol can route automatically;
- saying context/time ended without first publishing the meaningful work already completed.

Every worker must use live GitHub state and inspect the actual current implementation/evidence relevant to its routed action. A clean result still requires substantive review; “no code changes” does not mean “no work.”

If another worker already completed the routed action, immediately re-reconcile and take the newly routed action in the same session when doing so does not violate a fresh-worker boundary.

The final chat response must summarize concrete work completed, durable GitHub evidence, current phase/head, and remaining state. It must not consist primarily of instructions for launching another worker.

# Durable working checkpoint rule

For any bounded unit that has previously exhausted a worker session, the next worker must create a top-level PR comment containing `LOREITEMS_DEEP_AUDIT_V3` and `WORKING_CHECKPOINT` immediately after routing, before beginning a long investigation.

That comment must record the phase, exact bounded unit, starting SHA, and concrete scope. The worker should update the same comment after each meaningful component group is completed. If execution ends unexpectedly, the next worker resumes from the last concrete completed component recorded there instead of restarting the bounded unit.

A worker that completed meaningful review but made no repository code change still MUST update/publish the checkpoint. “No changes made” is not a reason to discard review progress.

# Current routing baseline

At adoption of V3, repeated deep reviews had already covered and fixed defects in:

- SLICE-01 — lifecycle, threading, runtime coordination;
- SLICE-02 — SQLite, persistence, migrations;
- SLICE-03 — item identity, tracking, physical-world lifecycle.

V3 subsequently completed remediation slices 04–07 through durable PR comments. Live V3 ledger comments are authoritative for exact completed state and head.

Documentation/protocol-only commits do not erase remediation coverage.

## The eight slices

1. Lifecycle, threading and runtime coordination.
2. SQLite, persistence and migrations.
3. Item identity, tracking and physical-world lifecycle.
4. Delivery and mass distributions.
5. Editor, templates, revisions and configuration.
6. Destructive admin, anomalies and recovery.
7. Commands, permissions, identity and external APIs/EnthusiaTags.
8. Build, release, dependencies and test integrity.

# Phase A — Remediation sweep

The remediation sweep exists to find and fix bugs across the whole plugin once without losing forward progress.

## General routing

- Select the lowest-numbered remediation slice not yet completed under V3.
- Review one bounded unit per worker unless the remaining work is trivially small.
- If partial, publish a precise checkpoint and the next worker resumes that same bounded unit.
- If a bug is found, fix it, add focused regression coverage where practical, and continue/finish the bounded unit as capacity permits.
- A fix does not globally restart the remediation sweep.
- After a unit is complete, advance to the next routed unit.

## Dirty-slice rule

When a fix materially changes code owned by an already-covered remediation slice, mark only that affected slice `DIRTY_RECHECK`.

Examples:

- a delivery fix that changes shared SQLite transaction machinery may dirty SLICE-02;
- a destructive fix that changes shared lifecycle/executor ownership may dirty SLICE-01;
- a test-only assertion correction does not dirty production slices unless it exposes a production guarantee requiring source re-review.

Dirty rechecks occur after remediation coverage reaches the end, before candidate freeze. Do not restart unaffected slices.

# SLICE-08 micro-routing — mandatory

SLICE-08 proved too large for a single worker and MUST NOT be attempted as one monolithic review.

It is split into four independently resumable sub-slices. The V3 ledger must complete all four before SLICE-08 is complete.

## SLICE-08A — Build graph and dependency provenance

Review only:

- root/settings/module Gradle build logic;
- Java/toolchain and Paper/API compatibility declarations;
- dependency versions and provenance, including SQLite;
- shading/runtime dependency inclusion;
- plugin metadata/version wiring;
- dependency locking/checksum/provenance contracts where present;
- build inputs that could make the shipped JAR differ from reviewed source.

Do not perform the release-workflow review or full test-integrity inventory in 08A.

## SLICE-08B — Release/version/tag/workflow correctness

Review only:

- `.github/workflows/ci.yml` release-relevant portions;
- `.github/workflows/release.yml` and release helper scripts;
- `gradle.properties`/version derivation;
- immutable `v1.0.0` behavior;
- correct next patch-release identity;
- tag/commit/version validation;
- release asset provenance;
- workflow race/freshness behavior;
- rollback/release documentation where it defines executable release behavior.

Try to prove a release cannot overwrite/retarget an old immutable release or publish an artifact from the wrong source/version.

## SLICE-08C — Artifact reproducibility and packaged-JAR integrity

Review only:

- JAR assembly inputs;
- reproducible-build settings/scripts;
- artifact naming/path contracts;
- plugin descriptor packaged values;
- expected libraries/resources/classes;
- exclusion of secrets/test-only/development artifacts;
- CI artifact upload identity;
- checksums and source-SHA binding;
- scripts/tests that validate artifact contents or reproducibility.

Actual expensive freeze/Sentinel evidence still belongs to Phase B; 08C reviews whether the machinery is correct.

## SLICE-08D — Test and quality-gate integrity

Review only:

- repository-native test/quality tooling;
- architecture/complexity/static-analysis contracts;
- Codacy integration logic;
- important test fixtures/mocks with risk of false positives;
- acceptance scripts that could report PASS without proving intended behavior;
- arbitrary sleeps/non-deterministic race tests;
- assertions that cannot fail or skip production failure paths;
- gaps specifically capable of hiding bugs in the production-hardening fixes.

Do not reread all production implementation from slices 01–07. Trace production code only where needed to validate test fidelity.

## SLICE-08 result values

Each sub-slice publishes/updates one V3 checkpoint with one of:

- `REMEDIATION_08A_CLEAN`
- `REMEDIATION_08A_FINDINGS_FIXED`
- `REMEDIATION_08A_PARTIAL`
- corresponding `08B`, `08C`, or `08D` values.

Record exact files/components completed and exact remaining work if partial.

After 08A completes, route 08B. After 08B, route 08C. After 08C, route 08D.

When all four are complete, the worker finishing 08D (or the next lightweight reconciler) posts:

`REMEDIATION_SLICE_08_COMPLETE`

and routes any explicit `DIRTY_RECHECK`; otherwise route Phase B candidate freeze.

A worker MUST NOT restart 08A because a later 08B/08C/08D fix moved the SHA unless that fix materially changed 08A-owned build/dependency behavior. Use the targeted dirty rule.

# General remediation result requirements

Every remediation result/checkpoint records:

- phase and slice/sub-slice;
- audited starting SHA;
- resulting SHA if fixes were committed;
- concrete components/classes/files reviewed;
- important flows/invariants traced;
- findings and fixes;
- focused tests/evidence;
- dirty slices/sub-slices created, if any;
- exact next action.

Do not run Sentinel or full release finalization from every remediation worker. Focused verification is sufficient during remediation; broad CI can run naturally but is not a reason to restart routing.

# Phase B — Freeze candidate

After SLICES 01–08 and all targeted dirty rechecks are complete:

1. reconcile all known findings and unresolved review threads;
2. ensure patch-release/version plumbing is correct and immutable `v1.0.0` is not retargeted;
3. run full Java 21 clean verification and repository tooling;
4. require complexity/static-analysis/Codacy gates to pass or classify/fix legitimate findings;
5. build the exact release candidate reproducibly;
6. verify artifact provenance and packaged JAR contents;
7. run required Sentinel startup/restart evidence only after the exact artifact exists;
8. require documented cleanup/resource safety.

Any credible defect found here is fixed and routed through a targeted dirty recheck. Do not automatically redo all eight remediation slices.

When green, publish:

`CANDIDATE_FROZEN`

with exact source SHA, tree SHA when available, CI run, artifact ID/name, JAR SHA-256, version/tag target, Sentinel evidence, and zero unresolved blocker/high findings.

Production-affecting candidate changes after this point unfreeze the candidate and invalidate clean verification rounds.

# Phase C — Clean verification round 1

Only after `CANDIDATE_FROZEN`.

Eight fresh workers independently review SLICES 01–08 on the exact frozen production candidate. SLICE-08 verification may use the same 08A–08D micro-boundaries if one worker cannot responsibly cover Slice 08, but the combined evidence must constitute one independent Slice-08 verification for the round.

If a credible defect is found, fix it, return to targeted remediation, refreeze, and restart verification round 1.

When all eight slices are clean on the same frozen candidate and final gates remain green:

`CLEAN_STREAK: 1/2`

# Phase D — Clean verification round 2

Repeat all eight slices with fresh workers on the same frozen candidate, varying attack order/failure models rather than merely confirming round-1 notes.

Any credible production defect resets clean verification to a newly fixed/refrozen candidate.

When all eight round-2 slices are clean and final gates remain green:

`CLEAN_STREAK: 2/2`

`READY FOR LIVE DEPLOYMENT — TWO CONSECUTIVE CLEAN DEEP REVIEWS`

No worker may claim literal zero bugs.

# Context-safety rules

- One bounded unit per worker.
- Never attempt monolithic SLICE-08; use 08A–08D.
- Do not reread the entire PR timeline; locate latest V3 control/results and current source.
- For historically context-heavy units, create/update a durable `WORKING_CHECKPOINT` before deep work.
- Publish partial progress before context pressure becomes failure.
- `PARTIAL` is resumable success when it contains concrete completed scope and remaining work.
- Do not rerun expensive full gates unless required by current phase or a fix.
- Do not create a new hardening PR while #30 is legitimate and open.
- Do not rewrite WP-01 through WP-06 completion history.
- Do not merge PR #30 until V3 reaches `CLEAN_STREAK: 2/2` and final gates are green.
- Do not deploy to the live production server without separate owner authorization.

# Worker startup algorithm

1. Fetch live PR #30 info/head.
2. Read this file from live PR #30 head.
3. Locate latest PR comments containing `LOREITEMS_DEEP_AUDIT_V3`.
4. Determine current phase and exact next bounded unit.
5. If that unit has previously exhausted a worker or is SLICE-08, create/update its `WORKING_CHECKPOINT` immediately.
6. Perform the bounded work itself using GitHub.
7. Persist/update the V3 result/checkpoint before stopping.
8. Stop only under the worker execution contract.

For the current remediation campaign, once SLICES 01–07 are complete and no explicit dirty recheck is routed first, begin with `REMEDIATION / SLICE-08A — Build graph and dependency provenance` rather than attempting all of SLICE-08 at once.
