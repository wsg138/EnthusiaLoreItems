# Current development handoff

## Active work

- Phase: Implementation PR 4 — Editing and destructive administration
- Exact subphase: durable template revision rollout planning core
- Repository: `wsg138/EnthusiaLoreItems`
- Pull request: #6 — `PR 4a: Add durable template revision rollout core`
- Branch: `agent/loreitems-pr4-revision-rollout-core`
- Starting `main`: `ea6f9548c0a7aea1ec82ee88d5329a7da49c4c1f`
- Verified implementation head: `1a82a3a2af339aca362fa9318320c54040c7c92d`
- Status: implementation, focused regression coverage, full-project verification, and harsh-review remediation are complete; the documentation head containing this handoff must pass exact-head Actions and Codacy, and the single substantive CodeRabbit review must finish, before merge

Always reconcile this handoff with live GitHub. Obtain the current `main` SHA, PR #6 status/head/draft state, mergeability, exact-head GitHub Actions, Codacy, submitted reviews, unresolved threads, requested changes, active branches, and comments newer than the latest immutable report. Live GitHub and code take priority.

If PR #6 has already merged when this file is read from `main`, treat the merge-finalization instructions below as stale. Verify the resulting `main` and branch cleanup, then begin the exact next PR 4 subphase only in a later chat.

## Latest report

- [`0029-2026-08-04-pr6-template-revision-rollout-core.md`](0029-2026-08-04-pr6-template-revision-rollout-core.md)

Report 0029 records stale-handoff reconciliation, the exact PR 4a scope, transaction and idempotency invariants, focused tests, all confirmed harsh-review defects and fixes, exact-head Actions and Codacy evidence for the verified implementation head, review state, preserved phase boundaries, and finalization steps.

## Required prior reports

None. Report 0029 repeats the still-relevant scope, defects, fixes, invariants, verification evidence, limitations, and next step. Read earlier reports only when investigating a specific subsystem's history.

## Exact next step

When PR #6 remains open:

1. verify the current documentation head with the permanent GitHub Actions workflow and exact-head Codacy check;
2. update the PR body with the exact final head, delivered Implementation PR 4a subphase, remaining PR 4 requirements, independent merge safety, tests, harsh-review fixes, and verification evidence;
3. allow the single substantive CodeRabbit review of the stable implementation to finish without retriggering it unnecessarily;
4. resolve every legitimate remaining in-scope finding and reverify the resulting exact head if code changes are required;
5. confirm no requested-change review or unresolved thread remains;
6. confirm `main` has not advanced incompatibly and PR #6 remains mergeable;
7. merge PR #6 with a normal merge commit under the user's authorization;
8. verify the resulting `main` SHA and normal workflow state;
9. delete `agent/loreitems-pr4-revision-rollout-core` when supported;
10. stop without beginning another logical item or subphase in the same chat.

When PR #6 is already merged:

1. verify the resulting `main` SHA and branch cleanup;
2. in a later chat, begin Implementation PR 4b: the bounded Paper `TEMPLATE_UPDATE` mutation executor and natural-encounter update integration.

## Preserved boundaries and limitations

- This subphase is an inert application/SQLite core; it does not wire commands, listeners, GUI flows, or physical item mutation.
- No live Paper/Leaf server behavior has been tested.
- No production system, deployment, or production database was accessed.
- No physical item was edited, replaced, removed, purged, deleted, or repaired.
- No GUI/chat editor, held-template replacement path, destructive administration, campaign execution, public API change, or EnthusiaTags integration was added.
- No force-loaded chunk, global synchronous scan, unbounded rollout page/batch, or retained Bukkit object across asynchronous persistence was introduced.
- Automated verification does not prove live inventory event ordering, component replacement behavior, server reload behavior, or staff workflow on a live server.
