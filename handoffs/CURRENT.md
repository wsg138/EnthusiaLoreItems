# Current development handoff

## Active work

- Phase: Implementation PR 1 — Foundation and durable core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #2 — Foundation and durable core
- Branch: `agent/loreitems-pr1-foundation`
- Status: in progress

Obtain the current head SHA, draft state, checks, Codacy result, and review comments from live GitHub state. Do not treat a SHA written in a handoff report as the current branch head after handoff commits.

## Latest report

- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md)

## Required prior reports

- [`0012-2026-08-02-pr2-deleted-marker-verification.md`](0012-2026-08-02-pr2-deleted-marker-verification.md) — preceding exact-head green baseline, completed persistence families, and remaining PR 1 scope.
- [`0003-2026-08-02-pr2-storage-runtime.md`](0003-2026-08-02-pr2-storage-runtime.md) — bounded database executor, connection ownership, external-delivery idempotency, claim fencing, and shared transaction rules.

## Exact next step

Continue PR #2 on `agent/loreitems-pr1-foundation`.

First verify exact-head GitHub Actions, Codacy, CodeRabbit, submitted reviews, and unresolved review threads after the handoff commits. The implementation-head build passed, but Codacy was still showing its recurring intermediate 100-issue aggregate when report 0013 was committed. If Codacy remains red, obtain stable detailed findings and fix only validated issues attributable to the transaction-helper slice. Do not suppress or guess.

Once exact-head automation is stable, implement only the remaining PR 1 codec foundation from `docs/implementation-plan.md`: platform-free versioned item-template and hidden-identity codec contracts plus focused Paper 1.21.11 implementations and round-trip tests. Preserve hidden definition ID, instance UUID, applied revision, forced unstackability, arbitrary held-item components, codec-version failure safety, and Paper thread ownership.

Do not begin item creation/adoption, physical inventory insertion, commands, protection listeners, tracking/reconciliation execution, GUIs, editing execution, group-file/campaign execution, physical deletion, or a later implementation phase.

## Focused startup reads

After reading the latest and required prior reports and verifying live PR state, inspect only:

- the PR 1 codec requirements in `REQUIREMENTS.md`, `docs/architecture.md`, and `docs/implementation-plan.md`;
- the current empty `adapters-paper` production module and its build configuration;
- existing application/domain identity and revision types suitable for platform-free codec contracts;
- Paper 1.21.11 PDC and item serialization APIs needed for focused adapter implementations;
- architecture tests and the smallest practical Paper round-trip test strategy;
- exact-head GitHub Actions, Codacy, CodeRabbit, submitted reviews, and unresolved review threads.

Do not broaden the codec foundation into creation/adoption use cases, delivery workers, commands, listeners, inventory mutation, protection, tracking, editing, distribution execution, or another phase.
