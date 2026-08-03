# Current development handoff

## Active work

- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Status: draft; held-item definition creation and held-item adoption are complete, durable direct-delivery execution and recovery are next

Always reconcile this handoff with live GitHub. Obtain the current `main` SHA, PR head, draft/ready state, mergeability, exact-head GitHub Actions, Codacy result, submitted reviews, unresolved threads, and comments newer than the latest immutable report.

## Latest report

- [`0020-2026-08-03-pr3-held-item-adoption.md`](0020-2026-08-03-pr3-held-item-adoption.md)

Report 0020 records the complete held-item adoption slice: durable preparation before physical mutation, fresh instance identity, exact-hotbar-slot fingerprinting and verification, hidden identity and forced unstackability, transactional observation/current-state/mutation finalization, explicit review-required handling, bounded lifecycle behavior, the six Codacy findings and fixes, full Gradle verification, harsh review, and preserved phase boundary.

## Required prior reports

- [`0019-2026-08-03-pr3-held-item-definition-creation.md`](0019-2026-08-03-pr3-held-item-definition-creation.md) — preceding held-item definition-creation slice and PR #3 starting state.
- [`0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`](0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md) — Foundation PR #2 completion and merge evidence.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — Paper template/identity codec design and verification inherited by the active phase.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — transaction-helper and unit-of-work invariants.

The architecture document present in this repository is [`../docs/architecture.md`](../docs/architecture.md). Do not follow stale references to nonexistent root `ARCHITECTURE.md`, `CONTRIBUTING.md`, `AGENTS.md`, `docs/pr1-review-checklist.md`, or `docs/runtime-compatibility.md` unless those files are added later and verified live.

## EnthusiaStaff reference access

- Durable inspectable reference repository: `wsg138/EnthusiaStaff-Staging`.
- Historical verified-runtime evidence: EnthusiaStaff Actions run `30794945133`, artifact `8848768264` (`https://github.com/wsg138/EnthusiaStaff/actions/runs/30794945133/artifacts/8848768264`).
- Treat the staging repository as durable. The Actions artifact is evidence for that specific run and may expire.

## Exact next step

Resume draft PR #3 and implement durable direct-delivery execution and recovery as the next complete logical slice.

Consume the existing direct-delivery intent model for self, online, offline, and full-inventory recipients. Claim work durably before physical creation or insertion, create one fresh instance identity per accepted delivery, perform and verify Paper inventory mutation on the server thread, leave offline/full-inventory work queued, resume safely on join and restart, and route ambiguous outcomes to review instead of duplicating delivery.

Do not begin environmental/durability protection, void terminal-loss handling, item-frame/glow-frame/armor-stand support, mob pickup prevention, broad observation/reconciliation, GUIs, editing, campaigns, deletion, or Tags integration in that chat unless the direct-delivery slice is fully complete and a new logical item is explicitly started.

## Known limitations

- PR #3 remains draft and the broader Implementation PR 2 phase is incomplete.
- No live Paper/Leaf server behavior has been tested.
- CodeRabbit automatic review is skipped while the PR remains draft unless explicitly triggered and completed.
- Direct delivery/recovery, protections, display entities, mob pickup prevention, initial audit views, and duplicate/malformed staff warnings remain unfinished.
