# EnthusiaLoreItems testing guide

This document explains the LoreItems automated-test model, the owner-directed exhaustive test-hardening work, how to run/review the suite, and how live acceptance and Sentinel evidence fit alongside repository-local tests.

The central rule is:

> **LoreItems owns its handwritten unit, domain, adapter, architecture, repository, and acceptance-contract tests. Sentinel Sim is an additional artifact/runtime compatibility layer; it does not replace or store LoreItems' ordinary tests.**

## Current exhaustive test-hardening PR

The test-hardening branch is intentionally stacked on the deep-review product candidate rather than modifying that candidate directly.

It adds two architecture-test contracts:

- `architecture-tests/src/test/java/net/enthusia/loreitems/architecture/FullFeatureCoverageContractTest.java`
- `architecture-tests/src/test/java/net/enthusia/loreitems/architecture/PluginFeatureSurfaceContractTest.java`

These are **coverage contracts**, not substitutes for the existing behavioral/acceptance suites.

### `FullFeatureCoverageContractTest`

This test maps every production/recovery-critical feature family to durable automated evidence. If a required test or acceptance workflow disappears or is renamed without a reviewed update, CI fails instead of silently losing coverage.

The mapping currently includes:

- hidden definition/instance identity and persistence;
- deleted-definition tombstones and late-returning copies;
- direct give, offline queue, full inventory, and idempotency;
- definition creation and held-item adoption;
- editor component contract;
- template revision rollout to accessible/inaccessible instances;
- environmental protection (despawn/fire/lava/explosion/cactus/etc.);
- conversion protection (craft/smelt/grind/smith/rename/consume);
- intentional void loss;
- nested shulker/bundle protection and tracking;
- player/container/entity/display tracking;
- duplicate/malformed/conflicting anomaly handling;
- exact-instance removal;
- purge/delete/pause/resume destructive lifecycle;
- destructive target evidence review;
- one-use distribution campaign state machine;
- distribution pause/resume/cancel/reconcile/restart;
- Floodgate prefixed/unresolved future recipients;
- queued mutation transitions;
- ambiguous side effects requiring review rather than guessing;
- backup/restart/rollback recovery;
- validated atomic configuration reload;
- bounded load/backpressure behavior;
- environment/operational safety;
- public idempotent EnthusiaTags service API;
- hexagonal dependency boundaries.

It also checks that mapped acceptance workflows remain explicitly runnable (`workflow_dispatch`) and fail closed on shell errors.

### `PluginFeatureSurfaceContractTest`

This test freezes the reviewed operator-visible manifest surface:

- commands: `loreitems`, `loreitemsreview`, `loredistribution`;
- the full reviewed set of privileged `enthusia.loreitems.*` permissions;
- required usage/documentation terms for command routes;
- privileged permission defaults remaining `op`;
- the intentional outer command-permission boundary.

If commands, subcommands/usages, or permissions change, do not simply update the constants. First verify the product change is intentional and that behavioral/acceptance coverage exists for the new route.

## What these contracts do not prove

A green architecture contract does not prove every feature is bug-free. It proves the reviewed feature surface still has durable evidence attached to it.

Actual behavior is exercised across:

- domain tests;
- application tests;
- adapter/Paper tests;
- persistence/infrastructure tests;
- architecture tests;
- repository tooling tests;
- WP-05 automated/live acceptance workflows;
- release and recovery evidence.

The correct response to a missing coverage path is normally to restore/add meaningful behavior coverage, not to point the contract at an unrelated file.

## Before modifying LoreItems tests

Reconcile live GitHub and follow the repository's agent protocol. Read at least:

1. `ai-agents/AGENTS.md`;
2. `ai-agents/UNIVERSAL-AGENT-PROMPT.md` when operating as an implementation worker;
3. `WORK-QUEUE.md` and all active package state required by the protocol;
4. the deep-review candidate/open PRs and all changed-path ownership;
5. this test-hardening PR and this document;
6. `ai-agents/SENTINEL-OPERATING-POLICY.md` before claiming Sentinel evidence;
7. `docs/wp-05-acceptance/index.md` when acceptance evidence is relevant.

The test-hardening branch must not be used to mutate or bypass the frozen product candidate. Product defects found by these tests belong in the proper product/deep-review branch according to the live protocol.

## Focused commands

Run the architecture-test module:

```bash
gradle --no-daemon :architecture-tests:test
```

Run only the two exhaustive contracts while iterating:

```bash
gradle --no-daemon :architecture-tests:test \
  --tests net.enthusia.loreitems.architecture.FullFeatureCoverageContractTest \
  --tests net.enthusia.loreitems.architecture.PluginFeatureSurfaceContractTest
```

On Windows PowerShell, the same Gradle command can be issued through the checked-in wrapper if present/required by the repository environment.

## Full repository validation

The existing repository gate remains authoritative. The production-hardening documentation records the full Java gate as:

```bash
gradle --no-daemon clean check
```

Do not treat the two coverage-contract tests as a substitute for the full suite, repository-tooling tests, complexity/static analysis, reproducibility/package checks, Codacy, or package-specific acceptance required by the live work-package contract.

## Where results are written

Gradle test results are module-local, including:

- `architecture-tests/build/test-results/test/` — JUnit XML;
- `architecture-tests/build/reports/tests/test/` — HTML report;
- corresponding `build/test-results/test/` and `build/reports/tests/test/` directories in other test modules.

For WP-05/live acceptance, the durable evidence index is `docs/wp-05-acceptance/index.md` plus the exact workflow run/job/artifact evidence referenced there.

GitHub Actions is the durable exact-head source for CI evidence. Any commit after a green run makes that run stale for final-head claims.

## How to interpret failures

### Missing coverage file/workflow

A feature's mapped regression/acceptance evidence disappeared or moved.

Investigate whether:

- the test was accidentally removed;
- a workflow was renamed/refactored;
- product behavior changed and a replacement test is needed;
- the coverage contract legitimately needs a reviewed mapping update.

Do not make the guard green by mapping a feature to an unrelated test.

### Acceptance workflow no longer dispatchable/fail-closed

Treat this as loss of executable evidence. Restore a safe explicit dispatch/fail-closed contract or document and implement the approved replacement evidence mechanism.

### Manifest surface mismatch

A command/permission/usage route changed. Review `plugin.yml`, command executors, authority checks, docs, and behavior tests together. A manifest update without runtime/permission tests is incomplete.

### Product behavior test failure

Fix the product in the owning product/package branch, add/keep the regression, and rerun the exact-head gates. Do not conceal the defect in the test-hardening branch.

### Environment/tooling failure

Differentiate setup/runner failures from product failures. A zero-step/no-runner workflow is not a product test result. A missing live acceptance environment is not equivalent to a pass.

## How to add coverage for a new LoreItems feature

For each new or materially changed feature:

1. identify the owning domain/application/adapter/persistence layer;
2. add deterministic behavioral unit/integration tests at that layer;
3. include destructive/failure/rollback/restart paths where relevant;
4. cover duplicate/idempotency and ambiguous-outcome behavior;
5. cover bounded queues/backpressure for batch/destructive/distribution work;
6. cover persistence/recovery for durable state;
7. add/update live or workflow acceptance when behavior cannot be honestly proved in-process;
8. update `FullFeatureCoverageContractTest` with the feature and exact durable evidence paths;
9. update `PluginFeatureSurfaceContractTest` only if operator-visible commands/permissions/usages changed;
10. update this guide when commands, evidence locations, or the test architecture changes;
11. run focused tests and the complete repository gate;
12. record exact-head run/job/evidence according to the active package protocol.

## Review checklist for Lore tests

Reviewers should explicitly ask:

- Does the test prove behavior rather than merely file/class presence?
- Is destructive administration fail-closed and reviewable when outcomes are ambiguous?
- Can item loss or duplicate creation occur on retry/restart?
- Are inaccessible/offline/nested instances represented where relevant?
- Are queues/batches bounded and resumable?
- Are stale revisions/templates handled correctly?
- Do reload/shutdown/restart preserve or reconcile state correctly?
- Are permissions operator-only where required?
- Are Floodgate/Bedrock recipient cases represented where the feature supports them?
- Are architecture boundaries still enforced?
- Does the test use sanitized/fake data rather than production data?
- Does exact-head CI/acceptance evidence belong to the final reviewed SHA?

## Sentinel Sim versus LoreItems tests

Use LoreItems repository tests for deterministic product behavior and regressions.

Use Sentinel when evidence requires:

- loading the finished LoreItems artifact with dependency provenance;
- generic long-sequence/fuzz exploration;
- cross-plugin compatibility;
- real-Paper startup/restart/config/database checks;
- production-stack compatibility observation.

A Sentinel MockBukkit boundary must remain visible. If MockBukkit emits a false/severe incompatibility for a modern Paper behavior, move that evidence to real Paper rather than weakening the test.

Sentinel result claims must follow `ai-agents/SENTINEL-OPERATING-POLICY.md` and record exact artifact/head/profile/backend evidence.

## Maintenance rule

When LoreItems gains, removes, or renames a production capability, command route, permission, workflow, acceptance case, or public integration API:

- add/update the actual behavioral evidence first;
- update the appropriate coverage contract;
- update this document if the worker/reviewer procedure changes;
- preserve the deep-review/package concurrency rules;
- reconcile the Sentinel profile separately if the cross-plugin/runtime surface changed.
