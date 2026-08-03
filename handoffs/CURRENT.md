# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0011-2026-08-02-pr2-deleted-marker-persistence.md`](0011-2026-08-02-pr2-deleted-marker-persistence.md)

## Required prior reports

- [`0010-2026-08-02-pr2-distribution-verification.md`](0010-2026-08-02-pr2-distribution-verification.md) — preceding exact-head green baseline, distribution persistence verification, and remaining PR 1 scope.
- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified transaction-context lifetime and rollback boundary used by marker persistence.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition soft-delete and active-key/history behavior.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, connection ownership, and recovery rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`, but do not begin another repository family yet. First verify live PR state and recheck Codacy for the exact current branch head after the handoff commits.

If Codacy remains red, obtain the stable detailed findings and fix only validated issues attributable to the deleted-definition marker slice. If Codacy has returned to `Up to standards` with zero new issues, record that exact-head evidence and then select the next still-in-scope Implementation PR 1 foundation task.

Do not begin physical deletion execution, group-file parsing or moves, campaign execution, commands, GUIs, item creation/adoption, protection listeners, tracking/reconciliation execution, editing, or a later implementation phase while Codacy status is unresolved.

## Focused startup reads

After reading the latest and required prior reports:

- verify the current PR head, draft/open/mergeable state, exact-head GitHub Actions, Codacy comment, CodeRabbit status/comment, submitted reviews, and unresolved review threads;
- compare Codacy's latest update timestamp and issue aggregate with the implementation evidence in handoff 0011;
- if Codacy is still red, inspect only stable detailed findings tied to the deleted-marker model, port, SQLite adapter, V1 constraints/triggers, unit-of-work integration, or focused tests;
- if Codacy is green, record that verification before selecting another PR 1 foundation task;
- preserve the current scope boundary and do not merge without explicit owner permission.
