# Current development handoff

## Active work

- Phase: Implementation PR 4 — Editing and destructive administration
- Exact subphase: PR 4c1 bounded naturally accessible inventory-backed `TEMPLATE_UPDATE` execution
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #8 — `Phase 4c1: execute naturally accessible template updates`
- Branch: `agent/loreitems-pr4c1-accessible-template-updates`
- Starting `main`: `5938c8c3ad14c3bd6b890ac6b998e4bab9c655bc`
- Final reviewed code head: `6704415b77302744f8b9dc740b287416c94a15e4`
- Status: implementation, focused regression coverage, separate harsh review, confirmed crash-recovery fix, and exact-head code verification are complete; the documentation-only head containing report 0034 must pass permanent exact-head CI and Codacy before merge

Always reconcile this handoff with live GitHub. Obtain the current `main` SHA, PR #8 status and head, draft state, mergeability, exact-head GitHub Actions and Codacy, submitted reviews, unresolved threads, requested changes, active branches, and comments newer than the latest immutable report. Live GitHub and code take priority.

If PR #8 has already merged when this file is read from `main`, treat the merge-finalization instructions below as stale. Verify the resulting `main` and branch cleanup, then begin PR 4c2 only in a later chat.

## Latest report

- [`0034-2026-08-05-pr8-exact-head-finalization.md`](0034-2026-08-05-pr8-exact-head-finalization.md)

Report 0034 records live-state reconciliation, final Codacy remediation, the separately discovered target-revision crash-window defect and regression fix, the complete harsh review, and successful permanent verification on code head `6704415b77302744f8b9dc740b287416c94a15e4`.

## Required prior reports

- [`0033-2026-08-04-pr8-final-codacy-remediation.md`](0033-2026-08-04-pr8-final-codacy-remediation.md)
- [`0032-2026-08-04-pr8-accessible-template-updates.md`](0032-2026-08-04-pr8-accessible-template-updates.md)
- [`0031-2026-08-04-pr7-mutation-queue-controls.md`](0031-2026-08-04-pr7-mutation-queue-controls.md)

Report 0033 contains the earlier implementation-head Codacy remediation. Report 0032 contains the complete PR 4c1 implementation and focused regression coverage. Report 0031 contains the typed durable mutation queue, lease recovery, audit, retry, cancellation, and operator-review invariants inherited by PR 4c1.

## Exact next step

When PR #8 remains open:

1. verify the final documentation-only head with the permanent GitHub Actions workflow and exact-head Codacy check;
2. update the PR body with the exact final head, delivered PR 4c1 subphase, remaining PR 4 requirements, independent merge safety, permanent verification evidence, harsh-review finding and fix, Codacy remediation, and review result;
3. confirm the actionable CodeRabbit thread remains resolved, no unresolved review thread or requested-changes review exists, and no new legitimate in-scope finding has appeared;
4. confirm live `main` has not advanced incompatibly and PR #8 remains mergeable;
5. merge PR #8 with a normal merge commit under the user's authorization;
6. verify the resulting `main` SHA and normal workflow state;
7. delete `agent/loreitems-pr4c1-accessible-template-updates` when supported;
8. stop without beginning another logical item or subphase in the same chat.

When PR #8 is already merged:

1. verify the resulting `main` SHA and branch cleanup;
2. in a later chat, begin PR 4c2: bounded natural-encounter template updates for already-loaded dropped item entities, item frames, glow item frames, and item display entities.

## Preserved boundaries and limitations

- This subphase updates only naturally accessible inventory-backed items, including nested shulker boxes and bundles.
- No unloaded chunk is force-loaded and no global synchronous inventory or world scan exists.
- Dropped item entities, item frames, glow item frames, and item display entities remain PR 4c2.
- No live Paper/Leaf server behavior has been tested.
- No production system, deployment, or production database was accessed.
- No staff command/GUI wiring, destructive definition retirement/deletion workflow, campaign execution, public API expansion, or EnthusiaTags integration was added.
- No released migration was edited.
- Hidden instance UUIDs remain stable; mutable container contents come from the encountered instance rather than the definition template.
- Ambiguous identity, revision, location, claim, physical verification, or durable completion remains fail-closed in `REVIEW_REQUIRED` or safely released before an unapplied mutation.
- The crash-recovery exception is narrow: an older stored applied revision may advance only when the encountered physical identity already equals the mutation target and all other claim, identity, lifecycle, and desired-revision fences match.
- Database work remains asynchronous and bounded; Paper inventory access and physical mutation remain on the owning thread.
