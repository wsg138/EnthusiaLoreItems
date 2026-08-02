# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0001-2026-08-02-pr2-foundation-start.md`](0001-2026-08-02-pr2-foundation-start.md)

## Required prior reports

None beyond the latest report.

## Exact next step

Continue PR #2 on its existing branch. Implement immutable validated configuration and atomic reload, the bounded database worker/lifecycle, degraded read-only storage startup, repository interfaces and SQLite implementations, and compare-and-set claim behavior with focused restart/idempotency tests.

Do not begin creation/adoption, physical inventory delivery, protection listeners, tracking, GUIs, editing, deletion execution, or campaign execution.

## Focused startup reads

After reading the latest report and live PR state, inspect only the files relevant to:

- configuration parsing/validation and reload boundaries;
- application repository ports;
- SQLite connection, migration, repository, and transaction code;
- worker/queue/lifecycle abstractions;
- delivery state transitions and their tests;
- PR checks and review comments.

Read broader architecture or requirements sections only when the immediate design decision is not already settled, a conflict appears, or PR completion is being reviewed.
