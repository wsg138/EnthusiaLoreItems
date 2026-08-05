# WP-03 — One-use mass distributions

## Objective

Complete the operator-facing, one-use mass-distribution campaign system from group-file discovery through immutable recipient snapshot, exactly-once durable delivery, lifecycle controls, completion/cancellation markers, recovery, and audit.

## Dependencies

- WP-02 is `COMPLETE` on live `main`.
- Direct delivery, template identity, queue controls, tracking, audit, and destructive recovery remain compatible and must not be bypassed.

## Complete required scope

1. On first startup create exactly:
   - `plugins/EnthusiaLoreItems/groups/`
   - `plugins/EnthusiaLoreItems/groups/completed/`
   - `plugins/EnthusiaLoreItems/groups/cancelled/`
2. Implement off-thread discovery, reload, and validation for `.yml` group files with required `display-name` and non-empty `players` list. Entries may be UUIDs or names; preserve original spelling and a leading Floodgate `*`; reject blank, malformed UUID, duplicate normalized recipient, path traversal, unsupported keys, and unreadable files with actionable per-file diagnostics.
3. Provide paginated command/GUI flows to reload/validate files, inspect validation results, select one valid source and one active definition, preview the immutable recipient count, and explicitly confirm campaign start.
4. On start, in one durable transaction create a permanent campaign UUID, selected definition/revision reference, immutable recipient snapshot, normalized recipient keys, original audit forms, source path/fingerprint, actor, and audit event. The database becomes authoritative before any delivery.
5. Enforce one-use source identity. Starting the same path/content fingerprint, replaying confirmation, copying an active marker back into the input directory, or restarting after an uncertain filesystem move must not create a second campaign or second recipient delivery.
6. Move/rename the source to an unambiguous active marker after database commit. If the filesystem action fails, recovery repairs marker state from the database; it never rolls back by creating another campaign.
7. Resolve cached/known names to UUIDs off the server thread without making network-dependent Mojang lookup a correctness requirement. UUID entries are authoritative. Unresolved names remain durable indefinitely.
8. On player join, match unresolved names case-insensitively while preserving original display form, including `*BedrockPlayer`; bind the UUID atomically once, and use UUID as authoritative thereafter.
9. Deliver through a bounded persistent campaign queue. Reserve a fresh instance and recipient outcome durably before insertion, verify exact insertion, and mark each recipient delivered exactly once. Offline and full-inventory recipients remain pending; no item is dropped.
10. Automatically resume active campaigns after restart and wake appropriate recipients on join/inventory-space opportunities while retaining bounded periodic retries and backpressure.
11. Provide indexed status counts for these mutually exclusive recipient states: `UNRESOLVED`, `QUEUED_OFFLINE`, `QUEUED_INVENTORY_FULL`, `RESERVED_IN_FLIGHT`, `REVIEW_REQUIRED`, `DELIVERED`, and `CANCELLED`, plus `total` and `remaining`. Define `total` as the sum of all seven states. Define `remaining` as `UNRESOLVED + QUEUED_OFFLINE + QUEUED_INVENTORY_FULL + RESERVED_IN_FLIGHT + REVIEW_REQUIRED`; a terminal completed or fully cancelled campaign has `remaining = 0`.
12. Provide campaign pause, resume, and cancel:
    - pause blocks new reservations and survives restart;
    - resume is idempotent;
    - cancel stops all future delivery, terminally cancels non-delivered recipients, preserves delivered instances, and keeps campaign/audit history visible.
13. Move the marker to `completed/` with a clear completed suffix only when every recipient is delivered exactly once. Move a cancelled marker to `cancelled/` with a clear cancelled suffix only after cancellation is durably committed. Recovery repairs missing/misplaced markers from database state.
14. Integrate campaign operation inspection with the WP-02 queue/review UI, metrics, permissions, messages, audit, reload, degraded-mode rejection, shutdown, and operator documentation.

## Exact acceptance criteria

- Valid Java names, `*`-prefixed Bedrock names, and UUID entries start one immutable campaign; case-only duplicate names and duplicate UUIDs are rejected before start.
- Editing, deleting, copying, or replacing the source after start does not alter the active recipient snapshot or duplicate a campaign.
- Every recipient can receive at most one instance for the campaign across duplicate commands, restart, claim expiry, join retries, and filesystem recovery.
- An unresolved name may remain pending across restart and bind correctly on a future first join without a network lookup.
- Offline/full recipients remain pending and no overflow item entity is created.
- Pause/resume/cancel survive restart; cancel never removes already delivered items.
- Completion and cancellation directory moves happen only after their corresponding durable terminal state, and recover correctly after failures on either side of the database/filesystem boundary.
- Status totals satisfy the exact mutually exclusive equations in required scope without double counting; completed campaigns contain only `DELIVERED`, and fully processed cancelled campaigns contain only `DELIVERED` plus `CANCELLED`.
- All parsing, filesystem, and SQLite I/O is off-thread; all queues, retries, pages, and per-tick mutations are bounded.

## Required automated tests

- YAML parser/validator tests for valid names, `*` prefix, UUIDs, casing, duplicates, unknown keys, malformed files, traversal/symlink safety, and deterministic fingerprinting.
- Domain/application tests for campaign and recipient transitions, immutable snapshots, pause/resume/cancel, name binding, exactly-once reservation, and count classification.
- SQLite integration tests for duplicate starts, unique source fingerprint, transaction rollback, restart at every state, long-lived unresolved names, UUID binding races, full inventory, claim expiry, cancel, completion, and audit.
- Filesystem/database failure tests for commit-before-move, move-before-observation, missing active marker, duplicated marker, completed/cancelled move failure, and recovery without duplicate campaign creation.
- Paper adapter tests for join matching, Java/Bedrock names, online/offline/full inventory, verified insertion, no overflow drop, bounded wakeups, and queue saturation.
- End-to-end tests with multiple campaigns and recipients proving one instance per campaign recipient and independent campaign identities.
- Regression tests for direct delivery, editing, destructive operations, tracking, reload, shutdown, and degraded mode.
- Full repository CI, architecture, tooling, complexity, static analysis, and exact-head Codacy.

## Required review and verification gates

- Independent review of source identity, immutable snapshotting, exactly-once delivery, Floodgate handling, DB/filesystem ordering, restart recovery, cancellation, counts, threading, and bounds.
- Review traces a recipient from unresolved or UUID input through reservation, physical insertion, verification, and terminal outcome.
- Exact-head Actions/Codacy success, no requested changes, and zero unresolved review threads.
- Normal merge commit and verified live `main`.

## Explicit exclusions

- General reusable mailing lists or repeatable campaigns.
- Network lookups as a required identity path.
- Cancelling by deleting already delivered instances.
- Production-wide hardening/release-candidate work; WP-04.
- Manual live acceptance/release; WP-05.
- Starting WP-04.

## Definition of complete

WP-03 is complete only when the complete file, database, delivery, control, status, recovery, and audit lifecycle meets every criterion and test, the fixed PR is normally merged and verified, durable workflow state is updated, and the worker stops.

## Expected status transitions

`BLOCKED -> READY -> IN_PROGRESS -> IN_REVIEW -> VERIFYING -> MERGED -> COMPLETE`

Failures continue WP-03 on the same branch/PR.

## Branch and PR naming

- Branch: `agent/wp-03-mass-distributions`
- PR title: `WP-03: complete one-use mass distributions`

## Exact next package

WP-04 — automated production hardening and release candidate. It remains blocked until WP-03 is `COMPLETE`.
