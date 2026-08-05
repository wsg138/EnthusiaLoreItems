# WP-02 — Destructive administration

## Objective

Complete every required destructive administration workflow and the operator controls for queued template-update and removal work. Destructive persistence primitives or read-only recovery views without safe end-to-end execution are incomplete.

## Dependencies

- WP-01 is `COMPLETE` on live `main`.
- WP-01's editor and revision rollout are the only supported template-management path and must integrate with the controls delivered here.

## Complete required scope

1. Add definition/instance GUI actions and privileged commands for:
   - removing one exact instance;
   - removing all known instances while retaining the active definition and current template;
   - fully deleting a definition and all physical instances.
2. Require explicit, operation-specific confirmations showing the definition, exact instance or known-instance count, queued/inaccessible count, anomalies, and irreversible physical effect. Full delete with any known or unresolved instance must state that physical items will be removed, not merely untracked.
3. Persist destructive intent, operation identity, targets, expected state/revision, actor, and audit event before physical mutation. Duplicate confirmation, callback replay, restart, and retry must not create a second logical operation or remove a different item.
4. Execute removals through bounded claim/apply/verify work. Paper mutations run on the server thread; persistence runs off-thread; no chunk is force-loaded; inaccessible inventories/entities remain queued until naturally accessible.
5. For exact-instance removal, require matching definition ID, instance UUID, expected location/scope evidence, and lifecycle before removal. A mismatch or ambiguous outcome enters `REVIEW_REQUIRED` without deleting another copy.
6. For purge, keep the definition active and editable, remove every known active instance, retain complete audit history, and remove late-returning copies tied to the purge operation until all durable targets are terminally resolved.
7. For full delete:
   - atomically mark the definition deleted and create the durable deletion/removal operation;
   - immediately exclude the definition from normal GUI search, give/adopt/editor selection, and tab completion after durable acceptance;
   - physically remove known and naturally reappearing copies rather than stripping metadata;
   - retain only the minimal deleted-definition identity and audit data needed for late copies, rollback/backup returns, and privileged historical inspection;
   - never expose deleted definitions in ordinary interfaces;
   - reach complete only when known work is terminal while retaining the late-copy removal marker as required by the architecture.
8. Add paginated queued-operation administration for template updates, exact removals, purge, and full delete. Show operation type, parent definition/instance, state, queue age, attempts, claim/lease data, last error, before/after evidence, and pending/review/completed counts.
9. Add pause/resume controls at the logical parent-operation level. Pausing prevents new claims but does not undo verified work; in-flight claimed work completes or expires safely. Resume is idempotent and restart-safe. Child rows cannot be selectively resumed in a way that violates the parent operation.
10. Add explicit `REVIEW_REQUIRED` inspection and only these evidence-gated resolutions:
    - requeue when persisted evidence proves no physical side effect occurred;
    - mark verified/completed only when a current supported observation proves the intended exact result;
    - close as aborted only when evidence proves the intended side effect did not occur and no target should remain scheduled.
    Never offer a blind force-complete, blind retry, or delete-by-location action.
11. Preserve duplicate/malformed evidence. A duplicate conflict must not be auto-resolved by deletion; staff must first use the existing explicit duplicate-resolution workflow or intentionally select a physical copy through a reviewed destructive action.
12. Add operational metrics, permissions, audit/history views, tab completion, messages, recovery instructions, and restart/reload/shutdown behavior for every destructive and queue-control path.

## Exact acceptance criteria

- Each of the three destructive operations can be initiated, confirmed, inspected, paused, resumed, recovered after restart, and completed from the supported admin interface.
- Exact removal cannot remove an item with a different instance UUID, definition, slot/entity identity, or changed fingerprint.
- Purge leaves the definition active with zero remaining known active instances after all reachable work completes and still removes a target that returns naturally after being inaccessible.
- Full delete disappears from every ordinary definition list and completion source immediately after durable acceptance, retains privileged history/minimal marker, and removes a late-returning copy after restart without force loading.
- Paused operations claim no new work across restart; resumed operations continue once; in-flight ambiguity is surfaced, never guessed.
- Storage failure, queue saturation, reload, shutdown, server crash between persisted intent and mutation, and crash between mutation and verification do not cause silent loss, duplicate removals, or false completion.
- All list/query/mutation work is bounded and paginated, and queue metrics expose backlog depth and oldest age.

## Required automated tests

- Domain/application state-machine tests for exact removal, purge, full delete, pause/resume, claim expiry, review transitions, and allowed evidence-gated resolutions.
- SQLite integration tests for atomic intent, target snapshotting, idempotency keys, restart recovery at every transition, parent/child pause fences, deleted-marker retention, ordinary-query exclusion, privileged history, late-returning copies, and transaction rollback.
- Paper adapter tests for player inventory, Ender Chest, physical/nested containers, dropped entities, item frames/glow frames, armor stands, changed slot/fingerprint, missing target, malformed stack, duplicate conflict, and natural chunk/entity access.
- Failure-injection tests for before apply, after apply/before verify, after verify/before commit, executor rejection, database unavailable, reload, and shutdown.
- Tests proving no force load, no overflow drop, no off-thread Bukkit access, per-tick budget enforcement, bounded retries, and paginated operation views.
- Permission/confirmation/session tests and tab-completion exclusion tests for deleted definitions.
- Regression tests for WP-01 rollouts and all earlier tracking/protection/delivery behavior.
- Full CI, repository tooling, architecture, complexity, static-analysis, and exact-head Codacy checks.

## Required review and verification gates

- Independent review focused on irreversible effects, wrong-target deletion, idempotency, late-copy behavior, pause fences, ambiguous outcomes, main-thread work, bounded queues, reload/shutdown, and history visibility.
- Review must trace each destructive flow from persisted intent through apply, verification, terminal state, and audit.
- Exact-head GitHub Actions and Codacy success after all fixes.
- No requested changes and zero unresolved review threads.
- Normal merge commit and verified live `main`.

## Explicit exclusions

- Mass-distribution campaigns; WP-03.
- Broad production failure matrix/performance release-candidate work; WP-04.
- Manual live-server acceptance and production release; WP-05.
- Restoring a deleted definition, silently stripping identity instead of removing items, force-loading chunks, global synchronous scans, or blind operator overrides.
- Starting WP-03.

## Definition of complete

WP-02 is complete only when all three destructive workflows and all required queue inspection/pause/resume/review controls work end to end, every acceptance/test/review gate is satisfied in the fixed PR, the normal merge is verified on `main`, state records are updated, and the worker stops.

## Expected status transitions

`BLOCKED -> READY -> IN_PROGRESS -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

Any failure returns to the same WP-02 branch/PR or to `BLOCKED` for a verified dependency.

## Branch and PR naming

- Branch: `agent/wp-02-destructive-administration`
- PR title: `WP-02: complete destructive administration`

## Exact next package

WP-03 — one-use mass distributions. It remains blocked until WP-02 is `COMPLETE` and requires a separate assignment.
