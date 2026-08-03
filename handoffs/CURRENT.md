# Current development handoff

## Active work

- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Status: draft; held-item definition creation, held-item adoption, and durable direct-delivery execution/recovery are complete; tracked-item protection is next

Always reconcile this handoff with live GitHub. Obtain the current `main` SHA, PR head, draft/ready state, mergeability, exact-head GitHub Actions, Codacy result, submitted reviews, unresolved threads, and comments newer than the latest immutable report.

## Latest report

- [`0021-2026-08-03-pr3-direct-delivery-recovery.md`](0021-2026-08-03-pr3-direct-delivery-recovery.md)

Report 0021 records the complete durable direct-delivery slice: self/online/offline/full-inventory command handling, preallocated fresh instance identity, prepared template claims, exact-slot Paper insertion and verification, join/restart polling, bounded recovery, transactional completion, ambiguity-to-review fencing, harsh-review findings and fixes, full Gradle verification, Codacy evidence, and the preserved phase boundary.

## Required prior reports

- [`0020-2026-08-03-pr3-held-item-adoption.md`](0020-2026-08-03-pr3-held-item-adoption.md) — preceding held-item adoption state machine and exact-slot mutation invariants.
- [`0019-2026-08-03-pr3-held-item-definition-creation.md`](0019-2026-08-03-pr3-held-item-definition-creation.md) — held-item definition creation and PR #3 starting state.
- [`0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`](0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md) — Foundation PR #2 completion and merge evidence.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — Paper template/identity codec design and verification inherited by the active phase.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — transaction-helper and unit-of-work invariants.

The architecture document present in this repository is [`../docs/architecture.md`](../docs/architecture.md). Do not follow stale references to nonexistent root `ARCHITECTURE.md`, `CONTRIBUTING.md`, `AGENTS.md`, `docs/pr1-review-checklist.md`, or `docs/runtime-compatibility.md` unless those files are added later and verified live.

## EnthusiaStaff reference access

- Durable inspectable reference repository: `wsg138/EnthusiaStaff-Staging`.
- Historical verified-runtime evidence: EnthusiaStaff Actions run `30794945133`, artifact `8848768264` (`https://github.com/wsg138/EnthusiaStaff/actions/runs/30794945133/artifacts/8848768264`).
- Treat the staging repository as durable. The Actions artifact is evidence for that specific run and may expire.

## Exact next step

Resume draft PR #3 and implement the next bounded protection slice: tracked-item environmental and durability protection plus terminal void-loss handling.

Cancel or neutralize destructive environmental and durability event paths for valid tracked items while preserving malformed/duplicate evidence. Treat a true void terminal loss as durable terminal state rather than silently recreating or retrying the item. Keep all Bukkit/entity/item access on the owning Paper thread, perform persistence asynchronously through bounded ports, avoid world scans and chunk loading, and add focused tests for every protected or terminal event path.

Do not begin broad tracking/reconciliation, GUIs, editing, campaigns, deletion, or Tags integration. Item-frame/glow-frame/armor-stand support and mob pickup prevention may remain a following logical slice if combining them would make this change too broad.

## Known limitations

- PR #3 remains draft and the broader Implementation PR 2 phase is incomplete.
- No live Paper/Leaf server behavior has been tested.
- CodeRabbit automatic review is skipped while the PR remains draft unless explicitly triggered and completed.
- Environmental/durability protection, void terminal loss, display-entity support, mob pickup prevention, initial audit views, and duplicate/malformed five-minute staff warnings remain unfinished.
- Review-required direct deliveries are durably fenced but await the later complete audit/recovery administration surface in this phase.
