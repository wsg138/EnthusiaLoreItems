# Handoff 0022 — direct-delivery Codacy remediation

## Session metadata
- Date/time: 2026-08-03 05:59 EDT
- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Reported implementation head: `e468e1749d28954b580609a2db086e42ce5e9236`
- Session status: in progress

## Objective

Complete one logical work item: reconcile the durable direct-delivery handoff with live GitHub, inspect the live Codacy regression in detail, fix every valid finding without changing delivery semantics or crossing the Implementation PR 2 boundary, remove temporary evidence tooling, and restore exact-head GitHub Actions and Codacy evidence.

## Work completed

Live GitHub showed that the direct-delivery implementation and report 0021 already existed. The remaining work was therefore remediation and exact-head verification rather than another direct-delivery implementation.

The live Codacy check exposed eight medium findings. Detailed file and line annotations were exported from GitHub check run `91650270991`; the evidence artifact was `8851505257`, with digest `sha256:b035c5c47003ff613a3b87ad81d5a7b08beb382201e3564bf3007fd8ecda34c9`.

All eight findings were legitimate and fixed:

| File | Finding | Resolution |
| --- | --- | --- |
| `PaperDirectDeliveryWorker.java` | non-exhaustive apply-result switch | Added a fail-closed default path that moves an unsupported result to review rather than retrying or guessing. |
| `PaperDirectDeliveryWorker.java` | explicit `pollTask = null` assignment | Removed the unnecessary assignment after task cancellation. |
| `LoreItemsPlugin.java` | explicit `directDeliveryWorker = null` assignment | Removed the unnecessary assignment after worker shutdown. |
| `PreparedDirectDelivery.java` | literal `1` in attempt-count validation | Added a named positive-attempt constant. |
| `SQLiteDirectDeliveryRepository.java` | expired-claim method exceeded the configured line limit | Extracted bounded expired-claim selection into a focused helper while preserving the existing transaction and update loop. |
| `SQLiteDirectDeliveryRepository.java` | repeated `delivery_id` column literal | Added and used a named column constant. |
| `SQLiteDirectDeliveryRepository.java` | repeated `instance_id` column literal | Added and used a named column constant. |
| `SQLiteDirectDeliveryRepository.java` | repeated `player_id` column literal | Added and used a named column constant. |

Temporary Codacy annotation, remediation, transform-diagnostic workflows, and generated evidence files were removed from the branch. A comparison from source-fix commit `b3ae2613cfda3d3e65a6ff5ab0fe2c08d7411251` to reported implementation head `e468e1749d28954b580609a2db086e42ce5e9236` showed only deletion of temporary workflow/evidence files and no production or test source change.

## Important decisions and invariants

- Unsupported Paper apply results fail closed into `REVIEW_REQUIRED`; they are not treated as successful, deferred, or safely retryable.
- Removing post-close null assignments does not change task cancellation, worker shutdown, service unregistration, executor shutdown, or intake fencing.
- Expired-claim selection remains bounded by the supplied limit and remains inside the same SQLite transaction as fenced review transitions, current-state invalidation, and audit persistence.
- No schema, migration, queue state, claim predicate, retry policy, inventory mutation, item identity, or public API contract changed.
- No broad suppression, analyzer disablement, or weakened quality threshold was added.

## Files or modules changed

Production source changes were limited to:

- `adapters-paper/src/main/java/net/enthusia/loreitems/paper/PaperDirectDeliveryWorker.java`
- `adapters-sqlite/src/main/java/net/enthusia/loreitems/sqlite/SQLiteDirectDeliveryRepository.java`
- `application/src/main/java/net/enthusia/loreitems/application/PreparedDirectDelivery.java`
- `plugin/src/main/java/net/enthusia/loreitems/plugin/LoreItemsPlugin.java`

Temporary evidence and remediation artifacts were removed before the reported implementation head.

## Persistence, state-machine, or API changes

- No migration or database schema changed.
- No direct-delivery state or transition changed.
- No repository, service API, command contract, or item codec contract changed.
- The SQLite refactor preserves transactional claim-expiry handling and its bounded batch limit.
- The new switch default only strengthens fail-closed handling for an otherwise unsupported future result value.

## Verification actually performed

The remediation content was first exercised through a guarded workflow. Its full Gradle run succeeded before publication:

- workflow run: `30803166150`
- job: `91652311647`
- command: `gradle --no-daemon clean check`
- result: `BUILD SUCCESSFUL in 32s`
- tasks: 34 actionable tasks, 26 executed, 8 up-to-date

The first push from that job was rejected because another temporary workflow advanced the branch concurrently. No force push or rebase was used. The tested source fixes were subsequently committed, and all temporary workflow/evidence residue was removed.

Exact reported-head GitHub Actions evidence:

- PR head: `e468e1749d28954b580609a2db086e42ce5e9236`
- workflow run: `30803568599`
- job: `91653657649`
- tested merge ref: `16eb66f5532af1425ee1824dece3819e9507f58d`
- base: `62268c9197e28ff690fda095a4878aa0f0556721`
- Java: Temurin `21.0.11+10`
- Gradle: `8.14.3`
- command: `gradle --no-daemon clean check`
- result: `BUILD SUCCESSFUL in 32s`
- tasks: 34 actionable tasks, 26 executed, 8 up-to-date

Domain, application, SQLite, Paper/MockBukkit, plugin, and architecture test tasks completed successfully. Only hosted-runner action/Node deprecation warnings were emitted.

Codacy refreshed at `2026-08-03T09:58:52Z`, after the reported implementation head was created, and reported:

- **Up to standards**
- **0 issues**
- **0 new issues**

No live Paper/Leaf server was started.

## Live automation observed

At the reported implementation head:

- PR #3 remained open, draft, and unmerged.
- GitHub Actions CI passed on the exact head.
- Codacy reported zero issues after the exact head.
- CodeRabbit reported success but skipped substantive automatic review because the pull request remains draft.
- Submitted reviews: none.
- Unresolved review threads: none.

## Harsh review findings

The complete direct-delivery diff and these remediation changes were reviewed again for item loss, duplicate insertion, stale claim replay, transaction splitting, main-thread blocking, unbounded work, shutdown races, and phase leakage.

- The switch default is conservative and cannot mark an unknown outcome successful.
- The extracted SQLite query retains the same SQL, ordering, limit, connection, transaction, and result mapping.
- The fenced update and audit loop remains unchanged after selection.
- Removing null assignments does not reopen intake or leave scheduled work active; cancellation and close calls still execute.
- No inventory or identity behavior was modified.
- No new confirmed behavioral defect or merge blocker was found.

## Unresolved risks or missing evidence

- The broader Implementation PR 2 phase remains incomplete, so PR #3 must remain draft and must not merge.
- No live Paper/Leaf server evidence exists for actual inventory insertion, restart recovery, shutdown timing, or player join behavior.
- Review-required deliveries remain durably fenced but await the later complete audit/recovery administration surface.
- Environmental and durability protection, terminal void loss, supported display entities, mob pickup prevention, initial audit views, and duplicate/malformed five-minute staff warnings remain unfinished.

## Exact next step

Resume draft PR #3 and implement one bounded protection slice: tracked-item environmental and durability protection plus terminal void-loss handling.

Keep Bukkit item/entity access on the owning Paper thread, persist terminal or audit outcomes asynchronously through bounded ports, do not scan worlds or force-load chunks, preserve malformed and duplicate evidence, and add focused tests for every protected and terminal event path. Do not begin broad tracking/reconciliation, GUIs, editing, campaigns, deletion, or EnthusiaTags integration.

## Required prior reports

- [`0021-2026-08-03-pr3-direct-delivery-recovery.md`](0021-2026-08-03-pr3-direct-delivery-recovery.md) — complete direct-delivery implementation, behavioral harsh review, and delivery/recovery invariants.
- [`0020-2026-08-03-pr3-held-item-adoption.md`](0020-2026-08-03-pr3-held-item-adoption.md) — exact-slot adoption state machine and item mutation invariants.
- [`0019-2026-08-03-pr3-held-item-definition-creation.md`](0019-2026-08-03-pr3-held-item-definition-creation.md) — held-item definition creation and PR #3 starting state.
