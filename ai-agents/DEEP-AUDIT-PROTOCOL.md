# LoreItems Deep Audit Protocol V3.1

Status: authoritative for PR #30 (`fix/full-plugin-deep-review`).

Goal: finish production hardening without losing real review progress to context churn. No process can prove literal zero bugs. Release readiness still requires complete remediation, a frozen green candidate, and two consecutive clean verification rounds.

## Canonical control state

The single authoritative routing/checkpoint comment is PR #30 issue comment **5590694632**.

Workers MUST fetch it directly from:

`https://api.github.com/repos/wsg138/EnthusiaLoreItems/issues/comments/5590694632`

Startup MUST NOT scan/fetch the full PR discussion, issue-comment history, old V2 comments, old handoffs, or unrelated audit history. Historical evidence is fetched only when a concrete current finding requires it.

## Minimal startup

1. Fetch live PR #30 metadata/head.
2. Read this file from that exact head.
3. Fetch canonical control comment 5590694632 directly.
4. Perform the exact routed bounded unit.

Routing is setup, not a deliverable.

## Execution-first rule

Do **not** require a checkpoint write before useful review begins.

For the routed bounded unit:

1. immediately inspect the first named file/component group;
2. once that concrete group is completed, update control comment 5590694632 with the completed scope/findings;
3. continue one component group at a time, updating the same comment after each meaningful completed group;
4. if a checkpoint write fails transiently, continue substantive review while tools remain available and retry the write later; a failed bookkeeping write is not a reason to stop before doing code work.

A worker may stop only after it has completed and durably recorded at least one concrete component group, fixed a confirmed defect with regression coverage, completed the routed unit, or encountered a genuine external blocker after useful attempted work.

Invalid outcomes include only apologizing, restating the prompt, reporting which unit is next, or stopping before inspecting the first routed code/config file merely because a checkpoint was not yet written.

## Remediation carry-forward

Remediation SLICES 01–07 are already covered under V3. Do not restart them unless the canonical control comment explicitly routes a targeted dirty recheck.

A later fix dirties only materially affected earlier behavior. No global reset.

## Slice 08 micro-routing

Never attempt all of Slice 08 in one worker.

### 08A — Build graph and dependency provenance

08A is itself split into tiny units:

- **08A1 Root graph/toolchain/version roots** — review exactly:
  - `settings.gradle.kts`
  - root `build.gradle.kts`
  - `gradle.properties`

- **08A2 Runtime module dependency declarations** — review exactly the `build.gradle.kts` files for:
  - `domain`
  - `application`
  - `api`
  - `adapters-sqlite`
  - `adapters-paper`
  - `plugin`

- **08A3 Non-runtime build modules + plugin metadata wiring** — review exactly:
  - `architecture-tests/build.gradle.kts`
  - `acceptance-harness/build.gradle.kts`
  - `plugin/src/main/resources/plugin.yml`
  - any directly referenced dependency-verification/checksum file discovered from those files

08A is complete only when 08A1–08A3 are complete.

### 08B — Release/version/tag/workflow correctness

Use bounded units rather than one monolithic review:

- **08B1** `.github/workflows/release.yml` + scripts directly invoked by it.
- **08B2** release-relevant portions of `.github/workflows/ci.yml` + scripts directly invoked by them.
- **08B3** immutable tag/version/rollback contract and versioned release docs.

### 08C — Artifact reproducibility and packaged JAR integrity

Use bounded units:

- **08C1** JAR/shadow assembly inputs and packaged descriptor/resources.
- **08C2** CI artifact naming, SHA/source binding, upload/download provenance.
- **08C3** reproducibility/content-validation scripts/tests.

Actual Sentinel acceptance belongs to freeze, not remediation 08C.

### 08D — Test and quality-gate integrity

Use bounded units:

- **08D1** repository quality/complexity/static/Codacy gate logic.
- **08D2** regression tests/fixtures for PR #30 hardening fixes, looking for false positives or mocks diverging from production.
- **08D3** acceptance/recovery/race test integrity, nondeterminism, arbitrary sleeps, skipped failure paths.

Do not reread all production code from slices 01–07 unless needed to validate test fidelity.

## Result handling

For each tiny unit, update canonical comment 5590694632 with:

- exact live starting SHA;
- unit ID;
- exact files/components completed;
- invariants checked;
- findings/fixes/tests;
- status: `CLEAN`, `FINDINGS_FIXED`, or `PARTIAL`;
- exact next tiny unit or exact remaining files within the same unit.

If a confirmed defect is found, fix it on PR #30 with focused regression coverage where practical. Do not create a replacement hardening PR.

Protocol/comment-only commits do not erase completed remediation coverage.

## Freeze phase

After all remediation units and targeted dirty rechecks are complete:

1. reconcile unresolved actionable findings;
2. require Java 21 clean verification and repository tooling;
3. require legitimate complexity/static/Codacy gates green;
4. verify correct patch-release identity without retargeting immutable `v1.0.0`;
5. build reproducibly and verify exact artifact/JAR contents and SHA;
6. run required Sentinel startup/restart only after the exact artifact exists;
7. publish `CANDIDATE_FROZEN` with exact SHA/artifact/evidence.

A defect found during freeze gets fixed and only affected remediation units are dirty-rechecked before refreezing.

## Verification rounds

After `CANDIDATE_FROZEN`, perform two independent clean whole-plugin verification rounds on the same frozen production candidate. Use bounded slices/micro-units so no worker must consume the whole codebase. Any credible production defect requires fix/refreeze and resets verification to round 1.

After round 1: `CLEAN_STREAK: 1/2`.

After round 2: `CLEAN_STREAK: 2/2` and `READY FOR LIVE DEPLOYMENT — TWO CONSECUTIVE CLEAN DEEP REVIEWS`.

Do not merge PR #30 before 2/2 and final green gates. Do not deploy to the live production server without separate owner authorization.
