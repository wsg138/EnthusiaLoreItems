# Handoff 0019 — held-item definition creation

## Session metadata
- Date/time: 2026-08-03 03:03 America/Indiana/Indianapolis
- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Reported implementation head: `040a6aad8ccfb2ff48f1369755056280d17f3fef`
- Session status: in progress

## Objective

Reconcile the stale Foundation handoff with live GitHub, begin the next legitimate implementation phase, and complete exactly one logical work item: create a lore-item definition from an administrator's held item without adopting, replacing, or otherwise mutating the physical item.

## Work completed

- Confirmed live `main` was `62268c9197e28ff690fda095a4878aa0f0556721`, the normal merge commit for completed Foundation PR #2.
- Confirmed no relevant unfinished pull request existed, then created branch `agent/loreitems-pr2-creation-delivery-protection` from that exact `main` head and opened draft PR #3.
- Added the application contract and implementation for held-item definition creation:
  - validated definition key, display name, immutable encoded template, and actor UUID;
  - generated a fresh definition UUID and revision 1;
  - returned explicit `CREATED`, `ACTIVE_KEY_EXISTS`, or `SERVICE_UNAVAILABLE` outcomes.
- Extended the application unit of work and SQLite adapter so the definition row, initial revision, and `definition_created` audit event commit in one transaction.
- Added `/loreitems create <lookup-key> <display name>` with permission `enthusia.loreitems.admin.create`:
  - player-only and main-hand sourced;
  - snapshots Paper item data on the server thread;
  - sends only immutable values to asynchronous persistence;
  - returns to the server thread for player messages;
  - remains unavailable during startup, degraded storage, and shutdown.
- Added a Paper snapshot boundary that preserves arbitrary visible/foreign components, normalizes the persisted template to amount/max-stack one, strips any existing LoreItems identity from the template, rejects malformed LoreItems identity evidence, and never changes the supplied item.
- Added application, SQLite transaction/recovery, and Paper codec-focused tests.
- Updated `plugin.yml` and `docs/development.md` with the command, permission, durability boundary, and explicit limitations.
- Used a temporary branch-only source-export workflow to obtain an exact source archive for local inspection; the workflow file was removed from the PR diff before the implementation commit.

## Important decisions and invariants

- This command creates only a reusable definition and revision 1. It does not create a physical instance, assign an instance UUID, adopt the held item, normalize the held item in place, or queue a delivery.
- The held `ItemStack` is cloned and encoded on the Paper server thread. No live `Player`, `Inventory`, or `ItemStack` reference crosses the asynchronous boundary.
- The database remains authoritative. Success is reported only after definition, revision, and audit persistence commit together.
- An active lookup-key conflict creates no audit event and no partial revision.
- Storage-unavailable, degraded, or shutdown states fail closed and never claim successful creation.
- Internal definition UUIDs remain absent from normal player output.
- PR #3 remains draft because the broader Implementation PR 2 phase is not complete.

## Files or modules changed

- `application/src/main/java/net/enthusia/loreitems/application/CreateDefinitionRequest.java`
- `application/src/main/java/net/enthusia/loreitems/application/CreateDefinitionResult.java`
- `application/src/main/java/net/enthusia/loreitems/application/CreateDefinitionStatus.java`
- `application/src/main/java/net/enthusia/loreitems/application/CreateDefinitionUseCase.java`
- `application/src/main/java/net/enthusia/loreitems/application/PersistingCreateDefinitionUseCase.java`
- `application/src/main/java/net/enthusia/loreitems/application/UnitOfWork.java`
- `application/src/test/java/net/enthusia/loreitems/application/PersistingCreateDefinitionUseCaseTest.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDefinitionRepository.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteUnitOfWork.java`
- `adapters-sqlite/src/test/java/net/enthusia/loreitems/sqlite/SQLiteUnitOfWorkTest.java`
- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperHeldItemDefinitionSnapshotter.java`
- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/CreateDefinitionCommandExecutor.java`
- `adapters-paper/src/test/java/net/enthusia/loreitems/paper/PaperHeldItemDefinitionSnapshotterTest.java`
- `plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java`
- `plugin/src/main/resources/plugin.yml`
- `docs/development.md`

## Persistence, state-machine, or API changes

- `UnitOfWork.DefinitionMutations` now includes atomic creation of a definition and its first revision.
- `SQLiteDefinitionRepository.createInTransaction(...)` provides the transaction-local primitive used by both the public repository and application unit of work.
- New definition creation writes:
  - one active `lore_definitions` row;
  - one revision-1 `lore_definition_revisions` row;
  - one immutable `audit_events` row with aggregate type `lore_definition`, event type `definition_created`, actor type `player`, and the actor UUID.
- No schema migration was required; Foundation migration V1 already contained the required tables, constraints, and active-key index.
- No delivery, instance, observation, mutation, campaign, or public Bukkit service state machine was expanded.

## Verification actually performed

Local inspection and compilation:

- Downloaded an exact branch source archive produced from head `15f71ee3dae0f1c62447b2abe0fecb00e593fda9`, then applied the implementation locally and verified the generated Git tree matched the tree published to GitHub.
- Ran Java 21 strict compilation for all domain, application, and SQLite main sources:
  - `javac --release 21 -Xlint:all -Werror ...`
  - result: success.
- Reviewed the complete PR diff against `main` for item loss, duplicate creation, main-thread blocking, unbounded work, shutdown/reload races, authorization, identity leakage, architecture violations, and phase creep.

GitHub Actions:

- Initial implementation head `9c15e436b067a263f3514d55744b3804f92d6ef4` failed CI run `30791903308` / run number 243.
- Exact failure: `PaperHeldItemDefinitionSnapshotterTest` used an otherwise empty compass fixture; MockBukkit's compass-meta deserializer dereferenced a null location map. Production code compiled and all other tests reached execution.
- Fixed the test to use the existing proven diamond-sword serialization path and added assertions that the original held item remains amount four and untracked.
- Corrected head `040a6aad8ccfb2ff48f1369755056280d17f3fef` passed CI run `30792053483` / run number 244.
- The workflow checked merge ref `f757436d89dec42da44adff4f57f2d4ac76e4b52`, which GitHub recorded as merging exact head `040a6aad8ccfb2ff48f1369755056280d17f3fef` into base `62268c9197e28ff690fda095a4878aa0f0556721`.
- Exact command: `gradle --no-daemon clean check`.
- Result: `BUILD SUCCESSFUL in 26s`; 34 actionable tasks, including application, Paper adapter, SQLite adapter, plugin, and architecture tests.

## Live automation observed

- GitHub Actions CI: success on implementation head `040a6aad8ccfb2ff48f1369755056280d17f3fef` as described above.
- Codacy PR summary refreshed at `2026-08-03T07:01:02Z`, after the corrected head was pushed, and reported `Up to standards`, `0 issues`, and `0 new issues`.
- CodeRabbit's automatic review was skipped because PR #3 is draft. A one-time `@coderabbitai review` request was posted for the current code slice. At report preparation time there were no submitted reviews and no inline review threads.
- GitHub reported the branch four commits ahead of and zero commits behind `main`; the temporary workflow addition/deletion exists only in branch history and not in the PR file diff.

## Harsh review findings and fixes

Confirmed finding:

1. **MockBukkit compass test fixture could not round-trip deserialize.**
   - Impact: exact-head CI failure; no production behavior failure was demonstrated.
   - Fix: changed the fixture to `DIAMOND_SWORD`, a component-bearing path already supported by the repository's codec tests, and strengthened original-item non-mutation assertions.
   - Verification: CI run 244 passed the full `clean check` task graph.

No additional confirmed defect was found in the complete slice. In particular:

- no physical item is consumed, replaced, dropped, or assigned identity;
- duplicate active-key attempts do not append audit or create partial revisions;
- all SQLite work remains on the bounded database executor;
- the only Paper item snapshot occurs before asynchronous persistence;
- command permission and player-only requirements are explicit;
- shutdown and degraded storage replace the creation delegate with a fail-closed implementation;
- no later PR 2 listener, protection, delivery execution, GUI, tracking, or editing behavior was introduced.

## Unresolved risks or missing evidence

- No live Paper/Leaf server was started. Command registration, real-server item-component serialization, permissions behavior, startup timing, shutdown notification behavior, and operator workflow are not live-server verified.
- MockBukkit and automated codec tests do not prove every Minecraft 1.21.11 item component round-trips on the target Leaf build.
- The broader Implementation PR 2 phase remains incomplete. Adoption, physical instance creation, direct delivery execution and recovery, protections, display entities, mob pickup prevention, command/audit browsing, and anomaly warnings are still absent.
- PR #3 must remain draft and must not be merged after this slice alone.

## Exact next step

Resume draft PR #3 on `agent/loreitems-pr2-creation-delivery-protection` and complete the next logical PR 2 item: adopt the administrator's held item into an existing active definition.

The adoption slice should:

1. resolve the existing active definition and current revision;
2. snapshot the held item on the server thread without replacing its visible appearance by default;
3. persist a fresh instance UUID, initial observation/audit intent, and any required durable mutation protocol before changing the item;
4. write hidden definition/instance/revision identity and forced unstackability to the exact held item on the server thread;
5. verify the post-write identity and surface ambiguous outcomes as review-required rather than retrying blindly;
6. cover inventory/held-slot changes, shutdown/restart ambiguity, malformed identity, and duplicate-command attempts;
7. keep direct give, offline/full-inventory delivery, environmental protection, broad listeners, GUIs, and later PR 2 capabilities deferred.

Before writing, reconcile live PR #3 head, CI, Codacy, submitted reviews, unresolved threads, and any newer comments with this report.

## Required prior reports

- [`0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`](0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md) — Foundation PR #2 completion and merge evidence.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — Paper template/identity codec design and verification inherited by this slice.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — transaction-helper and atomic unit-of-work invariants.
