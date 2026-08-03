# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0006-2026-08-02-pr2-unit-of-work.md`](0006-2026-08-02-pr2-unit-of-work.md)

## Required prior reports

- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — immutable definition/revision and instance persistence, compare-and-set rules, and remaining PR 1 repository scope.
- [`0004-2026-08-02-pr2-mutation-audit.md`](0004-2026-08-02-pr2-mutation-audit.md) — pending-mutation and append-only audit persistence decisions.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, connection ownership, lifecycle, and recovery rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. First obtain the current Codacy result and exact issue details for the final branch head, then classify and fix every real critical or high finding that applies to PR 1. Do not suppress findings merely to change the gate.

After the Codacy blocker is understood, implement only the next PR 1 repository family: bounded observation, current-state, and anomaly ports plus SQLite persistence and focused restart, uniqueness, and paging tests. Use the verified application `UnitOfWork` path for any authoritative state change that must commit with an audit event.

Do not begin commands, item creation/adoption, physical inventory delivery, protection listeners, tracking/reconciliation execution, GUIs, editing execution, deletion execution, or campaign execution.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files relevant to:

- the current Codacy critical/high findings and whether they apply to the final branch head;
- existing observation, current-state, and anomaly tables and indexes in V1;
- bounded application repository ports and domain records needed for those three persistence families;
- uniqueness, compare-and-set, anomaly lifecycle, and restart behavior required by the binding documents;
- the verified `UnitOfWork` boundary when authoritative state and audit persistence must be atomic;
- bounded database executor and connection ownership rules;
- PR checks, Codacy, review comments, and unresolved review threads.

Read broader architecture or requirements sections only when the immediate design decision is not already settled, a conflict appears, a safety/data-loss finding requires it, or PR completion is being reviewed.
