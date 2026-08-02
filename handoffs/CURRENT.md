# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md)

## Required prior reports

- [`0004-2026-08-02-pr2-mutation-audit.md`](0004-2026-08-02-pr2-mutation-audit.md) — pending-mutation and append-only audit persistence, claim fencing, and the unresolved atomic state-plus-audit requirement.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded SQLite runtime, external-delivery transaction behavior, startup recovery, and storage lifecycle decisions.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. Implement only a tested application `UnitOfWork` boundary and SQLite adapter that can atomically compose authoritative repository changes with audit-event persistence. Prove successful commit and full rollback with one focused in-scope workflow or transaction composition test, and consolidate transaction helpers where practical without broad unrelated refactoring.

Do not begin observation/current-state/anomaly persistence until the unit-of-work path is verified. Do not begin commands, item creation/adoption, physical inventory delivery, protection listeners, tracking/reconciliation execution, GUIs, editing execution, deletion execution, or campaign execution.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files relevant to:

- existing `SQLiteTransactions` and private transaction helpers;
- `AuditRepository`/`SQLiteAuditRepository` append behavior;
- one authoritative repository mutation suitable for proving atomic state-plus-audit commit and rollback;
- application-layer transaction boundaries that do not expose JDBC or make core code depend on SQLite;
- bounded database executor and connection ownership rules;
- PR checks, Codacy, review comments, and unresolved review threads.

Read broader architecture or requirements sections only when the immediate design decision is not already settled, a conflict appears, a safety/data-loss finding requires it, or PR completion is being reviewed.
