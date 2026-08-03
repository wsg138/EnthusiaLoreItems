# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0008-2026-08-02-pr2-tracking-persistence.md`](0008-2026-08-02-pr2-tracking-persistence.md)

## Required prior reports

- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified unit-of-work boundary, final prior CI/Codacy state, and PR 1 limitations.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition/revision/instance persistence and identity constraints used by observations and anomalies.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, storage lifecycle, connection ownership, and recovery rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. Implement only the next PR 1 repository family: distribution campaign and recipient domain/application persistence plus SQLite adapters and focused tests for source-fingerprint uniqueness, immutable recipient snapshots, campaign state transitions, recipient claim fencing, cancellation semantics, unresolved-name binding, bounded paging/counts, and restart recovery.

Do not begin group-file parsing or moves, physical campaign delivery, commands, GUIs, item creation/adoption, protection listeners, tracking/reconciliation execution, editing, deletion execution, or later phases. Leave deleted-definition marker persistence for the following focused repository slice unless campaign work exposes a direct prerequisite.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files relevant to:

- existing distribution campaign and recipient tables, constraints, indexes, and state values in V1;
- immutable campaign identity, source fingerprint, definition linkage, lifecycle, and completion rules;
- immutable recipient snapshot identity, original display value, UUID binding, state, claim token/lease, retry, delivery, and cancellation rules;
- case-insensitive unresolved-name keys while preserving Floodgate `*` prefixes and original values;
- bounded campaign/recipient pages and status counts;
- restart-safe claim fencing and recovery rules consistent with existing pending mutation and direct-delivery repositories;
- the verified `UnitOfWork` boundary when an authoritative campaign/recipient change must commit with an audit event;
- bounded database executor and connection ownership rules;
- PR checks, Codacy, review comments, and unresolved review threads.

Read broader architecture or requirements sections only when the immediate design decision is not already settled, a conflict appears, a safety/data-loss finding requires it, or PR completion is being reviewed.
