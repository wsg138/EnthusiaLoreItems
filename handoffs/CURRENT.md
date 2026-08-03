# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0009-2026-08-02-pr2-distribution-persistence.md`](0009-2026-08-02-pr2-distribution-persistence.md)

## Required prior reports

- [`0008-2026-08-02-pr2-tracking-persistence.md`](0008-2026-08-02-pr2-tracking-persistence.md) — preceding observation/current-state/anomaly slice and the distribution-persistence scope resumed by report 0009.
- [`0007-2026-08-02-pr2-unit-of-work-verification.md`](0007-2026-08-02-pr2-unit-of-work-verification.md) — verified unit-of-work boundary, prior CI/Codacy state, and PR 1 limitations.
- [`0005-2026-08-02-pr2-definition-instance-persistence.md`](0005-2026-08-02-pr2-definition-instance-persistence.md) — definition/revision/instance persistence and identity constraints used by campaign delivery references.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, storage lifecycle, connection ownership, and recovery rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`. Do not begin deleted-definition marker persistence yet. First inspect Codacy's detailed issue list for the current PR head and triage only findings introduced or exposed by the distribution campaign/recipient persistence slice and the migration-runner trigger support. Fix every validated compatibility, error-prone, security, performance, or correctness issue, rerun `gradle --no-daemon clean check`, and verify the refreshed Codacy result, PR comments, submitted reviews, and unresolved threads.

After that focused Codacy triage is resolved or each remaining finding is specifically demonstrated to be false or out of scope, the next repository family is deleted-definition marker domain/application persistence plus its SQLite adapter and focused tests.

Do not begin group-file parsing or moves, physical campaign delivery, commands, GUIs, item creation/adoption, protection listeners, tracking/reconciliation execution, editing, deletion execution, or later phases.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only the files and Codacy findings relevant to:

- the new distribution campaign and recipient domain models and repository ports;
- `SQLiteDistributionCampaignRepository` and `SQLiteDistributionRecipientRepository`;
- V1 distribution tables, constraints, indexes, and immutable-snapshot triggers;
- `MigrationRunner` trigger statement splitting;
- source-fingerprint normalization and uniqueness;
- campaign lifecycle, activation/completion guards, cancellation semantics, claim fencing, unresolved-name binding, bounded pages/counts, and restart recovery;
- any Codacy issue reported against the new or directly modified files;
- exact-head GitHub Actions, Codacy, CodeRabbit, review comments, submitted reviews, and unresolved review threads.

Read broader architecture or requirements sections only when a reported finding requires the binding rule, a conflict appears, a safety/data-loss issue is suspected, or PR completion is being reviewed.
