# PR #3 merge and post-merge cleanup

Date: 2026-08-03 (America/Indiana/Indianapolis)

## Phase

Implementation PR 2 — Creation, adoption, direct delivery, and protection.

Repository: `wsg138/EnthusiaLoreItems`

Implementation pull request: #3 — Creation, adoption, direct delivery, and protection

Implementation merge commit: `e64abfe75251f90c671452c4b50df2837074b1f7`

Post-merge cleanup pull request: #4 — Clean up PR 3 merge artifacts

Cleanup branch: `agent/pr3-post-merge-cleanup`

## Reconciliation

PR #3 merged with a normal merge commit at `2026-08-04T01:32:14Z`. Its final branch head was `a1a523580c59f7213b1b06c33530e291b8d9f556`.

The merge completed the documented implementation phase, but live reconciliation found two completion defects:

1. the temporary `.github/workflows/finalize-pr3-documentation.yml` workflow remained on `main` because PR #3 was merged before that self-deleting workflow produced its intended follow-up commit;
2. exact Codacy evidence still contained one PMD `UseConcurrentHashMap` finding on `PaperVoidLossCoordinator`. The intended narrow suppression had failed with a stale-file `409` during the prior chat and was not reapplied before merge.

PR #4 exists only to repair those completion defects. It does not begin the next implementation phase.

## Completed implementation scope

Implementation PR 2 remains complete:

- held-item definition creation and exact-slot adoption;
- durable, idempotent direct delivery for self, online/cached players, and UUID targets;
- offline and full-inventory waiting, join wakeup, restart recovery, claim expiry, and review fencing;
- hidden definition, instance, and revision identity with forced unstackability;
- environmental, durability, conversion, non-player-pickup, display-entity, and terminal-void protection;
- duplicate-instance and malformed-stack evidence preservation;
- immediate and periodic staff warnings;
- read-only paginated anomaly, audit, and recovery administration.

The final source decomposition resolved Codacy's earlier size findings without broad Java metric exclusions:

- `LoreItemsAdministrationFormatter` owns pure evidence formatting;
- `PaperIdentityObservationScanner` owns event-bounded identity scanning and duplicate comparison;
- `PaperVoidLossCoordinator` owns the claimed terminal-void workflow;
- `LoreItemsPlugin` administration activation is divided into focused lifecycle helpers;
- anomaly-store fixture seeding is divided into focused helpers.

## Cleanup performed

PR #4:

- deletes the temporary finalization workflow from the repository;
- documents and narrowly suppresses PMD's `UseConcurrentHashMap` warning on `PaperVoidLossCoordinator`.

The suppression preserves a deliberate correctness property. The coordinator's `inFlight` set and `retryAfterNanos` map form one compound state machine protected by `workflowLock`. Converting only the map to `ConcurrentHashMap` would not make cross-collection transitions atomic and would obscure the actual synchronization invariant.

No runtime control flow, item mutation, database transaction, queue, retry, event handler, command, configuration key, permission, API, schema, or durable record format changed in PR #4.

## Important invariants preserved

- Durable intent precedes physical item creation, replacement, or destruction.
- Exact identity and location verification precede durable completion.
- Ambiguous outcomes enter `REVIEW_REQUIRED`; they are not retried blindly.
- Duplicate and malformed physical evidence remains preserved rather than repaired, split, deleted, or overwritten.
- Terminal void loss retains durable preparation, exact entity/identity/min-height verification, bounded completion retries, abort, and review fencing.
- Bukkit/Paper access remains on the server thread; mutable Bukkit objects do not cross asynchronous persistence boundaries.
- Database work, claims, queues, retries, cooldowns, scans, pages, and recovery batches remain bounded.
- No broad world scan or chunk force-load was introduced.
- Released migration `V1__foundation.sql` was not edited.
- The boundary before PR 3 — Tracking and reconciliation remains intact.

## Harsh review

The complete cleanup diff was reviewed for accidental runtime changes, loss of terminal-void claim/cooldown atomicity, analyzer weakening, temporary artifacts, phase-boundary drift, and direct-main modifications.

The cleanup changes only remove the merged temporary workflow and document the existing single-lock state machine. The narrow suppression applies to one analyzer rule and one class; Java analysis remains enabled. No confirmed item-loss, duplicate-creation, transaction, lifecycle, thread-ownership, bounded-work, compatibility, or merge blocker remains in the cleanup implementation head.

## Verification evidence

Exact cleanup implementation head: `84c3419d591806e490a334d623c179f460bd2cc8`

GitHub Actions:

- run `30869346405`;
- verify job `91867914047`;
- tested merge ref `4e6a49bfd25807fce9bf8c4dc2106100617e5462`;
- base `e64abfe75251f90c671452c4b50df2837074b1f7`;
- Java Temurin `21.0.11+10`;
- Gradle `8.14.3`;
- command `gradle --no-daemon clean check`;
- result `BUILD SUCCESSFUL in 29s`;
- `34 actionable tasks: 12 executed, 14 from cache, 8 up-to-date`.

Codacy:

- PR #4 summary updated at `2026-08-04T01:40:13Z`, after the exact cleanup implementation head;
- result `Up to standards`;
- issues `0 issues`;
- new issues `0`.

A dependency-capable local checkout was unavailable because the local runtime could not resolve GitHub. No local test result is claimed. No live Paper/Leaf server behavior is claimed.

## Review and cleanup state

At report creation, PR #4 is draft. CodeRabbit skipped because the PR is draft. No requested-change review or unresolved review thread was present. The original PR #3 feature branch was already deleted; only `agent/pr3-post-merge-cleanup` remained.

The documentation-only head created with this report must receive exact normal CI and Codacy success. Then PR #4 may be marked ready, review/thread state rechecked, merged with a normal merge commit, and its branch deleted.

## Phase boundary and next work

This cleanup does not implement broad player, Ender Chest, physical-container, dropped-item, nested-shulker/bundle tracking or reconciliation; general nested location paths; paginated GUIs; explicit anomaly/recovery actions; editing; deletion; campaigns; production hardening; or EnthusiaTags integration.

After PR #4 is merged and cleanup is verified, stop. A later chat may begin PR 3 — Tracking and reconciliation only after reconciling live GitHub and reading this report.
