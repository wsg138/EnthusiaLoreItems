# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0004-2026-08-02-pr2-mutation-audit.md`](0004-2026-08-02-pr2-mutation-audit.md)

## Required prior reports

- [`0001-2026-08-02-pr2-foundation-start.md`](0001-2026-08-02-pr2-foundation-start.md) — original PR 1 scope, migration design, architecture boundaries, and deferred foundation work.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded storage lifecycle, direct-delivery idempotency, claim fencing, and startup recovery.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. Implement the next PR 1 persistence slice only: complete immutable definition/revision and lore-instance repository ports and SQLite implementations, including transactional definition creation, monotonic revision append with compare-and-set current revision, active/deleted lookup behavior, bounded paged reads, and focused uniqueness/rollback/restart tests.

Design the transaction boundary so later application use cases can commit authoritative state changes and audit events atomically; do not claim atomic audited workflows until that unit-of-work path is implemented and tested.

Do not begin item creation/adoption commands, physical inventory delivery, protection listeners, tracking/reconciliation, GUIs, editing execution, deletion execution, or campaign execution.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files relevant to:

- existing definition lookup ports and SQLite implementation;
- `lore_definitions`, `lore_definition_revisions`, and `lore_instances` schema constraints and indexes;
- immutable definition/revision/instance domain models and monotonic revision rules;
- transaction and unit-of-work boundaries needed for atomic state plus audit persistence;
- bounded page/query conventions established by direct delivery, pending mutation, and audit repositories;
- PR checks, Codacy, review comments, and unresolved review threads.

Read broader architecture or requirements sections only when the immediate design decision is not already settled, a conflict appears, a safety/data-loss finding requires it, or PR completion is being reviewed.
