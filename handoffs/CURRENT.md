# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md)

## Required prior reports

- [`0006-2026-08-02-pr2-unit-of-work.md`](0006-2026-08-02-pr2-unit-of-work.md) — application unit-of-work boundary, SQLite adapter, transaction helper changes, focused atomic commit/rollback tests, and implementation limitations.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — immutable definition/revision and instance persistence plus remaining PR 1 repository scope.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, connection ownership, lifecycle, and recovery rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. Implement only the next PR 1 repository family: bounded observation, current-state, and anomaly domain/application ports plus SQLite persistence and focused restart, uniqueness, compare-and-set, lifecycle, and paging tests. Use the verified application `UnitOfWork` path for any authoritative state change that must commit with an audit event.

Do not begin commands, item creation/adoption, physical inventory delivery, protection listeners, tracking/reconciliation execution, GUIs, editing execution, deletion execution, or campaign execution.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files relevant to:

- existing observation, current-state, and anomaly tables, constraints, and indexes in V1;
- bounded domain/application records and repository ports needed for those three persistence families;
- observation identity and location representation without retained Bukkit objects;
- current-state compare-and-set and last-observed rules;
- anomaly uniqueness, lifecycle, acknowledgement/resolution, and bounded history requirements;
- the verified `UnitOfWork` boundary when authoritative state and audit persistence must be atomic;
- bounded database executor and connection ownership rules;
- PR checks, Codacy, review comments, and unresolved review threads.

Read broader architecture or requirements sections only when the immediate design decision is not already settled, a conflict appears, a safety/data-loss finding requires it, or PR completion is being reviewed.
