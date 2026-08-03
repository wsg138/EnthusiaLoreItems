# Handoff 0014 — PR 2 codec foundation completion and full review

Date: 2026-08-02 America/Indiana/Indianapolis

## Scope completed

This work completed the remaining implementation scope for **Implementation PR 1 — Foundation and durable core** on PR #2 and branch `agent/loreitems-pr1-foundation`.

The completed logical work item was the versioned item-template and hidden-identity codec foundation required by `docs/implementation-plan.md`.

## Implementation

### Platform-free application contracts

Added:

- `ItemTemplateCodec<T>`;
- `EncodedItemTemplate` with a positive codec version, a bounded payload, defensive byte-array copies, and value equality;
- `ItemCodecFailure` and `ItemCodecException` for fail-closed version, corruption, invalid-item, and platform failures;
- `ItemIdentityCodec<T>`;
- `LoreItemIdentity` containing definition ID, instance ID, and applied template revision;
- `ItemIdentityReadResult` distinguishing untracked, tracked, and invalid evidence;
- `ItemIdentityFailure` distinguishing partial, unsupported-version, malformed, and stacking-violation evidence.

The application module remains free of Bukkit, Paper, JDBC, filesystem, and YAML implementation types.

### Paper 1.21.11 adapters

Added:

- `PaperItemCodecThreadGuard`, which rejects Paper item access away from the primary server thread;
- `PaperItemIdentityCodec`, which stores versioned hidden identity in namespaced PDC fields, clones caller-owned stacks, preserves unrelated metadata, forces amount and maximum stack size to one, and fails closed without silently repairing malformed evidence;
- `PaperItemTemplateCodec`, which clones and normalizes templates, strips per-instance identity before serialization, uses Paper's byte serialization, rejects unsupported versions and corrupt payloads, and returns a new unstackable item.

The codecs do not retain mutable `ItemStack` references across boundaries and do not implement item creation, adoption, inventory delivery, commands, listeners, protection, reconciliation, editing, campaign execution, or deletion execution.

### Tests

Added focused tests for:

- encoded payload immutability and validation;
- hidden identity round trips;
- visible name and lore preservation;
- unrelated PDC preservation;
- definition ID, instance UUID, and applied revision preservation;
- caller-stack non-mutation;
- identity clearing without deleting foreign metadata;
- partial, malformed, unsupported-version, and stacking-violation handling;
- forced amount and maximum stack size of one;
- arbitrary item component preservation across template serialization, including custom name, lore, unbreakable state, damage, and foreign PDC;
- stripping instance identity from reusable templates;
- corrupt template payload rejection;
- primary-thread ownership enforcement.

MockBukkit `4.110.0` is used only by the focused Paper adapter tests.

## CI defect found and fixed

The first implementation head `e6b74d327809e484d704f257e370ff67fbf05502` failed GitHub Actions CI run 165 (`30781591645`), job `91587185882` during `:adapters-paper:compileTestJava` because Paper API was compile-only and therefore absent from the test compile classpath.

The build was corrected by adding Paper API as an explicit `testImplementation` dependency while preserving `compileOnly` for production.

Implementation head `ea043d441d0e9cb2bdd59af7f02ea87980a38d15` then passed GitHub Actions CI run 166 (`30781691179`), job `91587469792`:

- checked out PR merge ref `6709f15962160bfb976e32db6bb64b152b5729da` containing exact head `ea043d441d0e9cb2bdd59af7f02ea87980a38d15`;
- ran `gradle --no-daemon clean check`;
- `BUILD SUCCESSFUL in 1m 7s`;
- 34 actionable tasks: 26 executed and 8 up-to-date;
- domain, application, SQLite, plugin, Paper adapter, and architecture tests passed.

The workflow emitted only GitHub-hosted action runtime deprecation warnings; no build or test step failed.

## Separate full-PR harsh review

A separate production-oriented review covered the complete PR rather than only the codec files. It inspected:

- plugin startup, degraded mode, stable service registration, reload, shutdown, and bounded lifecycle waits;
- bounded database execution and connection ownership;
- migration immutability and transactional migration execution;
- direct-delivery idempotency and claim-token/lease fencing;
- pending mutation and campaign-recipient claim fencing;
- transactional unit-of-work rollback;
- definition, revision, instance, observation, current-state, anomaly, deleted-marker, audit, campaign, and recipient persistence;
- state-machine transition validation and compare-and-set behavior;
- bounded paging, batch sizes, queues, shutdown drains, and expired-claim cleanup;
- module dependency direction and prohibited core imports;
- server-thread filesystem/database risks, asynchronous Paper access, retained mutable platform objects, force-loaded chunks, and gameplay scope leakage;
- misleading CI, Codacy, review, and live-server claims.

The only confirmed defect introduced by the codec slice was the Paper test-classpath omission described above, and it was fixed with a regression-proven green build. No additional confirmed merge-blocking runtime defect was found in the full PR review.

## Pull-request state and automated review evidence

PR #2 was marked ready for review at implementation head `ea043d441d0e9cb2bdd59af7f02ea87980a38d15`.

A full CodeRabbit review was explicitly requested. CodeRabbit verified the exact comparison from main `42aac09129c4fbda2756d30ee034c27ed1cf85b4` to implementation head `ea043d441d0e9cb2bdd59af7f02ea87980a38d15`, but skipped review because the PR contains 140 files, exceeding its 100-file limit, and because sufficient review credits or metered capacity were unavailable. A second request scoped to `adapters-paper` was also attempted; CodeRabbit still selected all 140 files and returned the same external limit/capacity blocker.

At the time this report was created:

- no submitted pull-request review existed;
- no unresolved review thread existed;
- CodeRabbit had produced no code finding because review could not start;
- Codacy's GitHub summary still showed its recurring intermediate aggregate of 100 issues (`32 high`, `68 medium`) and did not expose file-level findings through the available GitHub connector;
- merge was therefore not yet permitted without a stable final-head Codacy result or detailed attributable findings.

## Missing live evidence

No live Paper/Leaf server was started. Real-server item serialization/PDC behavior, plugin reload/shutdown behavior, corrupt-database recovery, backup/rollback, and future physical inventory behavior remain unverified. The current tests are automated Paper/MockBukkit and SQLite tests, not a live-server deployment.

## Preserved phase boundary

This PR still does not implement:

- held-item definition creation or adoption;
- physical inventory insertion or offline/full-inventory delivery workers;
- commands, permissions, GUIs, or chat editing;
- protection listeners;
- tracking or reconciliation execution;
- group-file or campaign execution;
- physical deletion execution;
- EnthusiaTags integration;
- any later implementation phase.

## Exact next action

After the report, `handoffs/CURRENT.md`, and `handoffs/INDEX.md` commits land, obtain the new exact branch head and verify:

1. GitHub Actions `clean check` on the exact head;
2. a stable Codacy result attributable to that exact head;
3. PR ready state, mergeability, submitted reviews, and unresolved threads.

If Codacy returns up to standards and no valid finding or thread remains, merge PR #2 with a normal merge commit using the exact expected head SHA, verify the resulting main SHA, and delete the feature branch only if an available connector action supports branch deletion.

If Codacy remains red, do not suppress or guess. Obtain its detailed file-level findings through Codacy UI/API access, fix only validated issues, and repeat exact-head validation. CodeRabbit's file-limit/credit failure is external review evidence and contains no code finding to resolve.
