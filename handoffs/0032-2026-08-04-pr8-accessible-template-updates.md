# PR #8 naturally accessible template-update execution

## Scope

- Repository: `wsg138/EnthusiaLoreItems`
- Active phase: Implementation PR 4 — editing and destructive administration
- Exact logical item: PR 4c1 bounded physical `TEMPLATE_UPDATE` execution for naturally accessible inventory-backed lore items
- Pull request: #8 — `Phase 4c1: execute naturally accessible template updates`
- Branch: `agent/loreitems-pr4c1-accessible-template-updates`
- Starting `main`: `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc`
- Reviewed implementation head: `8ab9582f5cb504117804d64ee4e68d6e3ce4d548`

This report records the complete independently mergeable PR 4c1 subphase. Live GitHub and the live repository remain authoritative if later state differs.

## Reconciliation

The prior handoff still named merged PR #7. Live GitHub showed that PR #7 had merged into `main` at `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc` and that PR #8 was the only relevant unfinished pull request. PR #8 was therefore resumed rather than creating another branch or competing pull request.

The active branch remained based directly on the current `main`; no newer merge made the recorded implementation stale. The PR was kept below the review-size limit at 29 changed files.

## Delivered behavior

PR 4c1 adds bounded execution of durable `TEMPLATE_UPDATE` mutations when a lore item becomes naturally accessible in:

- an online player's main inventory;
- an online player's Ender Chest;
- an already accessible block-backed or entity-backed inventory that can be resolved without loading a chunk;
- nested shulker boxes and bundles inside those inventories.

The implementation adds:

- an application-layer asynchronous prepare, release, complete, and review contract;
- exact-instance SQLite claims that load the queued target revision template under a lease;
- claim-token and lease fences for release, completion, and `REVIEW_REQUIRED` transitions;
- transactional instance applied-revision advancement and mutation completion;
- audit events for preparation failures, releases, completion evidence, and review outcomes;
- bounded main-thread discovery with continuation limits, scan queues, retry queues, in-flight limits, and instance deduplication;
- natural-access listeners for player inventory changes, opened inventory interactions, inventory movement, pickups, joins, quits, and closes;
- nested inventory paths that re-resolve the same root and container chain immediately before mutation;
- main-thread Paper replacement that preserves the hidden instance identity and the encountered instance's mutable container contents;
- before/after item fingerprints and exact target re-read verification before durable completion;
- crash-resume recognition when the physical item already carries the verified target revision;
- safe release when the item moved before mutation;
- fail-closed review when identity, revision, physical verification, or durable completion is ambiguous;
- integration of the natural-access executor with the existing bounded expired-claim recovery worker.

## Durability and threading

Database work remains asynchronous through the existing bounded SQLite runtime. Bukkit inventory and item mutation remains on the owning Paper thread. A durable claim is created before physical mutation, and durable success is recorded only after the exact target item is re-read and verified at the same path.

An expired claim is checked before scheduling physical work and again immediately before mutation. Physical success followed by failed or fenced durable completion is routed to `REVIEW_REQUIRED` rather than retried blindly. Expired claimed work remains recoverable through the existing bounded lease sweep.

## Independent harsh review

A separate full-PR harsh review concentrated on item-loss, duplication, stale-reference, crash-window, threading, and saturation failures. It found and fixed the following confirmed defects:

1. Desired shulker or bundle contents could have been copied into every updated instance. Target templates now have mutable container contents cleared before the encountered instance's contents are preserved.
2. Recursive comparison normalized hidden identity at every nesting level, which could conceal a changed nested lore-item identity. Only the root identity is normalized; nested identities must match.
3. A claim could expire after preparation but before physical mutation. The coordinator now fences expiry both before main-thread scheduling and immediately before applying.
4. Coordinator saturation could drop a naturally encountered candidate. Rejected candidates now requeue their inventory reference for a later natural scan.
5. Scan-backlog saturation could leave an incomplete access reference without a retry. A bounded deduplicated retry backlog now retains overflow references and retries them in FIFO batches.
6. The retry backlog was initially unbounded. It is now capacity-limited with explicit fail-closed behavior.
7. Paper scheduling failure handling was too narrow. Runtime scheduler rejection during shutdown now follows the safe claim-release path.
8. Template-update comparison, access tracking, and scanner structures triggered exact-head maintainability findings. The findings were fixed without weakening analyzers.

The post-review remediation delta was harsh-reviewed again for claim fencing, duplicate suppression, retry boundedness, transient inventory coverage, listener budget consistency, and compatibility with existing plugin wiring. No additional confirmed merge blocker remained.

## Tests and verification

Focused regression coverage includes:

- application delegation, validation, and physical completion evidence;
- SQLite preparation, release/retry, restart completion, audit rollback, and sibling-mutation fencing;
- direct inventory and nested shulker/bundle mutation;
- current container-content preservation and desired-template content clearing;
- nested hidden-identity mismatch detection;
- stale identity and target-revision mismatch review;
- continuation scanning without partial candidate publication;
- duplicate identity reporting across continuation passes and accessible inventories;
- complete online-player inventory coverage before duplicate-fence release;
- bounded scan, overflow, and retry FIFO behavior;
- coordinator saturation retry and in-flight instance deduplication;
- expired claims never reaching physical mutation;
- bounded expired-claim recovery without overlapping runs.

No authenticated local checkout or outbound GitHub access was available in this runtime, so no local Gradle result is claimed. Permanent GitHub Actions supplied the executable evidence.

Exact implementation-head evidence before handoff finalization:

- `1141587568e0e04f035202f7bd5835cf7b92165b`, CI run #692 / job `92091499908`: `gradle --no-daemon clean check` succeeded, repository-tooling tests succeeded, the new-code complexity gate found no violations, and exact-head Codacy succeeded.
- `8ab9582f5cb504117804d64ee4e68d6e3ce4d548`, CI run #701 / job `92096412100`: Gradle verification, repository tooling, and the new-code complexity gate completed successfully; exact-head Codacy was the remaining running step when this immutable report was written. The final handoff-documentation head must pass the full permanent workflow, including exact-head Codacy, before merge.

No live Paper/Leaf server behavior, deployment, production system, or production database was tested or accessed.

## Automated review

PR #8 was marked ready only after the implementation stabilized. CodeRabbit performed one substantive full-PR review of the 27-file implementation range from `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc` through `1141587568e0e04f035202f7bd5835cf7b92165b`.

It produced one actionable correctness thread: a persisted preparation-review diagnostic blamed the observed physical revision even though the detected condition was a database applied revision ahead of the queued desired revision. Commit `436ade4b83f435a707142ce71dc99aaaee4c31a7` corrected the diagnostic, and CodeRabbit automatically resolved the thread.

Useful boundedness, maintainability, and regression suggestions were also applied. Remaining notes were non-functional cleanup or artificial test-seam expansion and were not allowed to broaden this subphase. The incremental bot review of the eight-file remediation delta was rate-limited; it was not repeatedly retriggered. The delta instead received a separate harsh review and remains subject to exact-head permanent CI and Codacy.

## Preserved boundaries

- No chunk is force-loaded and no unloaded inventory or entity is searched.
- No global synchronous inventory or world sweep was added.
- No dropped-item, item-frame, glow-item-frame, or item-display physical update executor was added; those are PR 4c2.
- No staff command, GUI, campaign executor, public API expansion, EnthusiaTags integration, destructive definition retirement/deletion workflow, or later-phase feature was added.
- Existing released migrations were not edited.
- Hidden instance UUIDs remain stable.
- Container contents come from the encountered instance, never from the definition template.
- Ambiguous identity, location, revision, claim, physical write, or durable completion remains fail-closed.
- Shutdown, backpressure, recovery, and scan work remain bounded.

## Merge finalization

Before merging PR #8:

1. verify the final handoff-documentation head with the permanent CI workflow and exact-head Codacy;
2. update the PR body with the exact final head and verification evidence;
3. confirm no unresolved review thread or requested-changes review exists;
4. confirm current `main` remains compatible and the PR is mergeable;
5. merge using a normal merge commit;
6. verify the resulting `main` SHA;
7. delete the feature branch when the available GitHub tooling supports it;
8. stop without beginning another logical item.

## Exact next work

The next independently reviewable Phase 4 item is PR 4c2: bounded natural-encounter template updates for already-loaded non-inventory item locations, specifically dropped item entities, item frames, glow item frames, and item display entities.

PR 4c2 must reuse the same durable prepare/claim/apply/verify/complete-or-review protocol, keep Paper entity access on the owning thread, preserve hidden instance identities, avoid chunk or entity force-loading and global sweeps, retain bounded retry/backpressure, and fail closed when uniqueness or physical outcome cannot be proven.

## Required reading for the next agent

- This report.
- [`0031-2026-08-04-pr7-mutation-queue-controls.md`](0031-2026-08-04-pr7-mutation-queue-controls.md), for the typed mutation queue, recovery, and operator-review invariants inherited by this executor.
