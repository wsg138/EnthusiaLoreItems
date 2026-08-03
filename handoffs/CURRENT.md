# Current development handoff

## Active work

- Phase: Implementation PR 2 — Creation, adoption, direct delivery, and protection
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #3 — Creation, adoption, direct delivery, and protection
- Branch: `agent/loreitems-pr2-creation-delivery-protection`
- Status: draft; held-item definition creation, held-item adoption, durable direct delivery/recovery, and tracked-item environmental/durability protection with durable terminal void loss are complete; supported display entities and mob pickup prevention are next

Always reconcile this handoff with live GitHub. Obtain the current `main` SHA, PR head, draft/ready state, mergeability, exact-head GitHub Actions, Codacy result, submitted reviews, unresolved threads, and comments newer than the latest immutable report.

## Latest report

- [`0023-2026-08-03-pr3-tracked-item-void-protection.md`](0023-2026-08-03-pr3-tracked-item-void-protection.md)

Report 0023 implements and harsh-reviews the bounded tracked-item protection slice: dropped-item despawn, combustion, merge, environmental damage, player durability protection, and durable terminal void destruction. It records the intent-before-removal state machine, exact entity/identity revalidation, anomaly fencing, restart-safe review behavior, focused application/SQLite/MockBukkit tests, exact Codacy evidence, and the preserved phase boundary.

## Required prior reports

- [`0022-2026-08-03-pr3-direct-delivery-codacy-cleanup.md`](0022-2026-08-03-pr3-direct-delivery-codacy-cleanup.md) — clean starting head and complete direct-delivery slice.
- [`0021-2026-08-03-pr3-direct-delivery-recovery.md`](0021-2026-08-03-pr3-direct-delivery-recovery.md) — direct-delivery claim/recovery behavior sharing mutation-recovery infrastructure.
- [`0020-2026-08-03-pr3-held-item-adoption.md`](0020-2026-08-03-pr3-held-item-adoption.md) — held-item adoption state machine and exact identity mutation invariants.
- [`0019-2026-08-03-pr3-held-item-definition-creation.md`](0019-2026-08-03-pr3-held-item-definition-creation.md) — held-item definition creation and PR #3 starting state.
- [`0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md`](0018-2026-08-03-pr2-final-codacy-fixes-and-merge-verification.md) — Foundation PR #2 completion and current `main` baseline.
- [`0014-2026-08-02-pr2-codec-foundation-completion.md`](0014-2026-08-02-pr2-codec-foundation-completion.md) — Paper template/identity codec design and verification inherited by the active phase.
- [`0013-2026-08-02-pr2-transaction-helper-consolidation.md`](0013-2026-08-02-pr2-transaction-helper-consolidation.md) — transaction-helper and unit-of-work invariants.

The architecture document present in this repository is [`../docs/architecture.md`](../docs/architecture.md). Do not follow stale references to nonexistent root `ARCHITECTURE.md`, `CONTRIBUTING.md`, `AGENTS.md`, `docs/pr1-review-checklist.md`, or `docs/runtime-compatibility.md` unless those files are added later and verified live.

## EnthusiaStaff reference access

- Durable inspectable reference repository: `wsg138/EnthusiaStaff-Staging`.
- Historical verified-runtime evidence: EnthusiaStaff Actions run `30794945133`, artifact `8848768264` (`https://github.com/wsg138/EnthusiaStaff/actions/runs/30794945133/artifacts/8848768264`).
- Treat the staging repository as durable. The Actions artifact is evidence for that specific run and may expire.

## Exact next step

First reconcile report 0023 and the final documentation head against live exact-head GitHub Actions, Codacy, submitted reviews, unresolved threads, and the PR body. Confirm that all temporary diagnostic/remediation workflows remain deleted.

Then resume draft PR #3 with the next bounded protection slice: supported item-frame/glow-frame/armor-stand placement and break semantics plus mob pickup prevention.

Preserve the exact hidden identity and forced unstackability of every tracked physical item. Keep Bukkit entity and inventory access on the owning Paper thread, persist only authoritative location changes through bounded asynchronous ports, avoid world scans and force-loaded chunks, and fence ambiguous outcomes into review rather than guessing.

Do not begin initial audit/recovery UI, duplicate/malformed five-minute staff warnings, broad tracking/reconciliation, GUIs, editing, campaigns, deletion, or Tags integration in that slice.

## Known limitations

- PR #3 remains draft and the broader Implementation PR 2 phase is incomplete.
- No live Paper/Leaf server behavior has been tested.
- CodeRabbit automatic review is skipped while the PR remains draft unless explicitly triggered and completed.
- Item-frame/glow-frame/armor-stand support, mob pickup prevention, remaining identity-losing use/conversion restrictions, initial audit views, and duplicate/malformed five-minute staff warnings remain unfinished.
- Review-required direct deliveries and void-loss mutations are durably fenced but await the later complete audit/recovery administration surface in this phase.
