# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0010-2026-08-02-pr2-distribution-verification.md`](0010-2026-08-02-pr2-distribution-verification.md)

## Required prior reports

- [`0009-2026-08-02-pr2-distribution-persistence.md`](0009-2026-08-02-pr2-distribution-persistence.md) — distribution campaign/recipient implementation, failed-run evidence, corrected implementation CI, and transient pre-final Codacy state.
- [`0008-2026-08-02-pr2-tracking-persistence.md`](0008-2026-08-02-pr2-tracking-persistence.md) — preceding observation/current-state/anomaly persistence slice.
- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified unit-of-work boundary, prior CI state, and PR 1 limitations.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition/revision/instance persistence and soft-delete context needed by deleted-definition markers.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, storage lifecycle, connection ownership, and recovery rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. Implement only deleted-definition marker domain/application persistence plus its SQLite adapter and focused tests. Preserve immutable deleted-definition identity/history, bounded lookup/list behavior, transaction and connection-ownership rules, and compatibility with the existing definition soft-delete lifecycle.

Do not begin group-file parsing or moves, physical campaign delivery, commands, GUIs, item creation/adoption, protection listeners, tracking/reconciliation execution, editing, deletion execution, or later phases. After implementation, verify exact-head GitHub Actions, Codacy, CodeRabbit, submitted reviews, and unresolved review threads before selecting another repository family.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files relevant to:

- the existing `deleted_definition_markers` V1 table and its current constraints;
- definition soft-delete lifecycle and active-key/history behavior;
- immutable marker identity, lookup key, deletion timestamp, and any required audit linkage;
- bounded marker lookup/list repository ports and SQLite implementation;
- the verified `UnitOfWork` boundary when a definition soft-delete and marker/audit persistence must commit together;
- bounded database executor and connection ownership rules;
- focused restart and transactional rollback tests;
- exact-head GitHub Actions, Codacy, CodeRabbit, review comments, submitted reviews, and unresolved review threads.

Read broader architecture or requirements sections only when the immediate marker design is not already settled, a conflict appears, a safety/data-loss issue is suspected, or PR completion is being reviewed.
